package dev.piannoli.scorza.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.piannoli.scorza.util.ErrorLog
import dev.piannoli.ui.components.LocalUiActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal enum class ArtworkKind { GAME, SYSTEM }

/**
 * Decodes selected artwork with a single background worker. Cache hits publish immediately; misses are queued
 * without delay. The worker decodes the newest pending focused selection first, then a small latest-first
 * backlog of recently selected sources. Decoded artwork is cached even after its selection is superseded, but only
 * while at least one owning artwork category remains enabled. Hiding a category drops its pending work, not the
 * shared bounded cache. Artwork is only published to subscribers that still request that exact source while
 * their UI is active.
 */
internal class SelectionArtworkLoader<T>(
    private val decoder: (String) -> T?,
    private val sizeOf: (T) -> Int,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxCacheBytes: Int = 24 * 1024 * 1024,
    private val maxBacklog: Int = 6,
) {
    private data class Source(val path: String, val revision: Long)
    private data class Entry<T>(val artwork: T, val bytes: Int)
    private class Subscriber<T>(val source: Source, val kind: ArtworkKind) {
        val result = CompletableDeferred<T?>()
        var focused = false
    }

    private val lock = Any()
    private val cache = LinkedHashMap<Source, Entry<T>>(16, 0.75f, true)
    private var cacheBytes = 0L
    private val backlog = ArrayDeque<Source>()
    private val pendingOwners = mutableMapOf<Source, MutableSet<ArtworkKind>>()
    private val enabledKinds = ArtworkKind.entries.toMutableSet()
    private val subscribers = mutableListOf<Subscriber<T>>()
    private var inFlight: Source? = null
    private val inFlightOwners = mutableSetOf<ArtworkKind>()
    private var inFlightSuperseded = false
    private var worker: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        require(maxCacheBytes > 0)
        require(maxBacklog > 0)
    }

    fun setEnabled(kind: ArtworkKind, enabled: Boolean) = synchronized(lock) {
        if (enabled) {
            enabledKinds.add(kind)
        } else {
            enabledKinds.remove(kind)
            pendingOwners.values.forEach { it.remove(kind) }
            backlog.removeAll { pendingOwners[it].isNullOrEmpty() }
            pendingOwners.entries.removeAll { it.value.isEmpty() }
            inFlightOwners.remove(kind)
            subscribers.filter { it.kind == kind }.forEach {
                it.focused = false
                it.result.complete(null)
            }
        }
    }

    fun cached(path: String, revision: Long, kind: ArtworkKind = ArtworkKind.GAME): T? = synchronized(lock) {
        if (kind in enabledKinds) cache[Source(path, revision)]?.artwork else null
    }

    suspend fun load(
        path: String,
        uiActive: Flow<Boolean>,
        revision: Long = 0L,
        kind: ArtworkKind = ArtworkKind.GAME,
        publish: (T?) -> Unit,
    ) {
        val subscriber = register(Source(path, revision), kind) ?: return
        try {
            var published = false
            uiActive.collectLatest { focused ->
                setFocused(subscriber, focused)
                if (!focused || published) return@collectLatest
                val artwork = cached(path, revision, kind) ?: subscriber.result.await()
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (kind in enabledKinds) publish(artwork)
                }
                published = true
            }
        } finally {
            synchronized(lock) { subscribers.remove(subscriber) }
        }
    }

    private fun register(source: Source, kind: ArtworkKind): Subscriber<T>? {
        val subscriber = Subscriber<T>(source, kind)
        synchronized(lock) {
            if (kind !in enabledKinds) return null
            subscribers += subscriber
            // A new revision makes queued, cached, and in-flight artwork for older revisions of this path stale.
            backlog.removeAll { it.path == source.path }
            pendingOwners.keys.removeAll { it.path == source.path && it != source }
            removeStaleRevisions(source)
            if (inFlight?.path == source.path && inFlight != source) inFlightSuperseded = true
            val artwork = cache[source]?.artwork
            if (artwork != null) {
                subscriber.result.complete(artwork)
                return subscriber
            }
            if (source != inFlight) {
                pendingOwners.getOrPut(source) { mutableSetOf() }.add(kind)
                backlog.addLast(source)
                while (backlog.size > maxBacklog) pendingOwners.remove(backlog.removeFirst())
            } else {
                inFlightOwners.add(kind)
            }
            if (worker == null) worker = scope.launch(dispatcher) { work() }
        }
        wake.trySend(Unit)
        return subscriber
    }

    private fun setFocused(subscriber: Subscriber<T>, focused: Boolean) {
        synchronized(lock) { subscriber.focused = focused && subscriber.kind in enabledKinds }
        if (focused) wake.trySend(Unit)
    }

    private suspend fun work() {
        while (true) {
            val source = next()
            if (source == null) {
                wake.receive()
                continue
            }
            // Native decoding cannot be interrupted; this single worker is the only caller of the decoder.
            val artwork = try {
                decoder(source.path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            }
            finish(source, artwork)
        }
    }

    private fun next(): Source? = synchronized(lock) {
        var source: Source? = null
        // Pause queued work while no subscriber's UI is active.
        while (source == null && subscribers.any { it.focused && it.kind in enabledKinds }) {
            val current = subscribers.lastOrNull { it.focused && !it.result.isCompleted }?.source
            val candidate = current?.also { backlog.remove(it) } ?: backlog.removeLastOrNull() ?: break
            val owners = pendingOwners.remove(candidate).orEmpty().toMutableSet()
            // A still-requested selection can outlive its eviction from the recent-selection backlog.
            subscribers.filter { it.source == candidate }.forEach { owners.add(it.kind) }
            owners.retainAll(enabledKinds)
            if (owners.isEmpty()) continue
            val artwork = cache[candidate]?.artwork
            if (artwork == null) {
                source = candidate
                inFlightOwners.clear()
                inFlightOwners.addAll(owners)
            } else {
                complete(candidate, artwork)
            }
        }
        inFlight = source
        inFlightSuperseded = false
        source
    }

    private fun finish(source: Source, artwork: T?) = synchronized(lock) {
        inFlight = null
        val stillWanted = inFlightOwners.any { it in enabledKinds }
        inFlightOwners.clear()
        if (artwork != null && stillWanted && !inFlightSuperseded) store(source, artwork)
        // Failures complete with null so active requests stop waiting without retrying forever.
        if (stillWanted) complete(source, artwork.takeUnless { inFlightSuperseded })
    }

    private fun complete(source: Source, artwork: T?) {
        subscribers.forEach {
            if (it.source == source && it.kind in enabledKinds) it.result.complete(artwork)
        }
    }

    private fun removeStaleRevisions(source: Source) {
        val entries = cache.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key.path == source.path && entry.key != source) {
                cacheBytes -= entry.value.bytes
                entries.remove()
            }
        }
    }

    private fun store(source: Source, artwork: T) {
        val bytes = sizeOf(artwork)
        if (bytes <= 0 || bytes > maxCacheBytes) return
        cache.remove(source)?.let { cacheBytes -= it.bytes }
        cache[source] = Entry(artwork, bytes)
        cacheBytes += bytes
        val entries = cache.entries.iterator()
        while (cacheBytes > maxCacheBytes && entries.hasNext()) {
            cacheBytes -= entries.next().value.bytes
            // Drop only the cache reference; Compose may still be displaying this bitmap.
            entries.remove()
        }
    }
}

// Shared across library screens and composition restarts, bounded by decoded bitmap bytes. Its worker scope
// intentionally lives for the app process, like the cache.
private val libraryArtworkLoader = SelectionArtworkLoader<ImageBitmap>(decoder = { path ->
    try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        var sampleSize = 1
        while (options.outWidth / sampleSize > 1024 || options.outHeight / sampleSize > 1024) {
            sampleSize *= 2
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ErrorLog.error("Unable to decode selected artwork: $path", error)
        null
    }
}, sizeOf = { it.asAndroidBitmap().byteCount }, scope = CoroutineScope(SupervisorJob()))

@Composable
internal fun ArtworkVisibility(gameEnabled: Boolean, systemEnabled: Boolean) {
    SideEffect {
        libraryArtworkLoader.setEnabled(ArtworkKind.GAME, gameEnabled)
        libraryArtworkLoader.setEnabled(ArtworkKind.SYSTEM, systemEnabled)
    }
}

@Composable
internal fun rememberSelectedArtwork(
    path: String?,
    revision: Long,
    enabled: Boolean,
    kind: ArtworkKind = ArtworkKind.GAME,
): State<ImageBitmap?> = rememberSelectedArtwork(
    path, revision, enabled, LocalUiActive.current, libraryArtworkLoader, kind,
)

@Composable
internal fun <T> rememberSelectedArtwork(
    path: String?,
    revision: Long,
    enabled: Boolean,
    uiActive: StateFlow<Boolean>,
    loader: SelectionArtworkLoader<T>,
    kind: ArtworkKind = ArtworkKind.GAME,
): State<T?> = key(path, revision, enabled, uiActive, loader, kind) {
    // produceState otherwise retains the previous selection's image until its new producer runs.
    val initialArtwork = if (enabled && path != null && uiActive.value) loader.cached(path, revision, kind) else null
    produceState(initialArtwork) {
        if (enabled && path != null) loader.load(path, uiActive, revision, kind) { value = it }
    }
}
