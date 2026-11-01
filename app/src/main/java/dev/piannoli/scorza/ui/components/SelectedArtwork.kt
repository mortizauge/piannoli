package dev.piannoli.scorza.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piannoli.scorza.settings.ArtScale
import dev.piannoli.scorza.util.ErrorLog
import dev.piannoli.ui.components.LocalUiActive
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal const val ARTWORK_WINDOW_RADIUS = 10
private const val MAX_ORIGINAL_ARTWORK_PX = 1024

/** One decodable artwork file for a list row, sized for the panel that displays it (in pixels). */
internal data class ArtworkRequest(
    val path: String,
    val revision: Long,
    val width: Int,
    val height: Int,
    val scale: ArtScale,
)

/** The selected row plus at most [ARTWORK_WINDOW_RADIUS] list rows on each side; rows without artwork are null. */
internal data class ArtworkWindow(val selectedIndex: Int, val start: Int, val requests: List<ArtworkRequest?>) {
    val selected: ArtworkRequest? get() = requests.getOrNull(selectedIndex - start)

    companion object {
        val EMPTY = ArtworkWindow(0, 0, emptyList())
    }
}

internal inline fun artworkWindow(
    size: Int,
    selectedIndex: Int,
    request: (Int) -> ArtworkRequest?,
): ArtworkWindow {
    if (selectedIndex !in 0 until size) return ArtworkWindow.EMPTY
    val start = max(0, selectedIndex - ARTWORK_WINDOW_RADIUS)
    val end = min(size - 1, selectedIndex + ARTWORK_WINDOW_RADIUS)
    return ArtworkWindow(selectedIndex, start, (start..end).map(request))
}

/**
 * Largest power-of-two sample size whose decoded image still covers what [request]'s panel can display, so
 * artwork is never decoded larger than needed nor degraded below the panel's pixel density. Never upscales.
 */
internal fun artworkSampleSize(sourceWidth: Int, sourceHeight: Int, request: ArtworkRequest): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    val widthScale = request.width.toDouble() / sourceWidth
    val heightScale = request.height.toDouble() / sourceHeight
    val displayScale = when (request.scale) {
        ArtScale.FIT -> min(widthScale, heightScale)
        ArtScale.FIT_WIDTH -> widthScale
        ArtScale.FIT_HEIGHT -> heightScale
        ArtScale.ORIGINAL -> 1.0
    }
    var sample = 1
    if (displayScale > 0) {
        while (displayScale * sample * 2 <= 1.0) sample *= 2
    }
    if (request.scale == ArtScale.ORIGINAL || displayScale <= 0) {
        while (max(sourceWidth, sourceHeight) / sample > MAX_ORIGINAL_ARTWORK_PX) sample *= 2
    }
    return sample
}

/**
 * Decoded artwork for the single list currently on screen. A list owns a [Session]; opening another session or
 * closing the owner drops every cached reference and pending request, and a stale owner's close is ignored.
 *
 * Only artwork in the current [ArtworkWindow] is wanted. One worker decodes the selected row first, then rows
 * alternately outward, starting on the side the selection last moved towards. Moving inside the window keeps the
 * overlap and only reprioritises; any cached artwork that leaves the window (or a jump beyond it) is dropped. A
 * decode that is already running is allowed to finish but its result is discarded once it is no longer wanted.
 * The byte budget evicts the rows farthest from the selection, never the selected one, and rows rejected for budget
 * or failing to decode are not retried until the window changes. Paused sessions keep their window but start no
 * decode. References are only dropped, never recycled, because Compose may still be drawing them.
 */
internal class ListArtworkCache<T>(
    private val decoder: (ArtworkRequest) -> T?,
    private val sizeOf: (T) -> Int,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxCacheBytes: Int = 24 * 1024 * 1024,
) {
    class Session internal constructor()

    private class Entry<T>(val artwork: T, val bytes: Int)

    private val lock = Any()
    private var session: Session? = null
    private var active = false
    private var window = ArtworkWindow.EMPTY
    private var direction = 1
    private var priority: List<ArtworkRequest> = emptyList()
    private var budgetRank = Int.MAX_VALUE
    private val cache = HashMap<ArtworkRequest, Entry<T>>()
    private var cacheBytes = 0L
    private var oversized: Pair<ArtworkRequest, T>? = null
    private val failed = HashSet<ArtworkRequest>()
    private var inFlightSession: Session? = null
    private var worker: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val selectedDecoded = MutableStateFlow(0L)

    /** Changes whenever the selected row's artwork finishes decoding. */
    val changes: StateFlow<Long> = selectedDecoded

    init {
        require(maxCacheBytes > 0)
    }

    fun open(active: Boolean): Session = synchronized(lock) {
        clear()
        this.active = active
        Session().also { session = it }
    }

    fun close(owner: Session) = synchronized(lock) {
        if (owner !== session) return
        clear()
        session = null
        active = false
    }

    fun setActive(owner: Session, active: Boolean) {
        synchronized(lock) {
            if (owner !== session) return
            this.active = active
        }
        if (active) start()
    }

    fun update(owner: Session, window: ArtworkWindow) {
        synchronized(lock) {
            if (owner !== session || window == this.window) return
            val previous = this.window
            if (previous.requests.isNotEmpty() && window.selectedIndex != previous.selectedIndex) {
                direction = if (window.selectedIndex > previous.selectedIndex) 1 else -1
            }
            this.window = window
            priority = priorityOf(window)
            budgetRank = Int.MAX_VALUE
            val wanted = priority.toHashSet()
            val entries = cache.entries.iterator()
            while (entries.hasNext()) {
                val entry = entries.next()
                if (entry.key !in wanted) {
                    cacheBytes -= entry.value.bytes
                    entries.remove()
                }
            }
            failed.retainAll(wanted)
            if (oversized?.first != window.selected) oversized = null
        }
        start()
    }

    fun peek(request: ArtworkRequest): T? = synchronized(lock) {
        cache[request]?.artwork ?: oversized?.takeIf { it.first == request }?.second
    }

    private fun priorityOf(window: ArtworkWindow): List<ArtworkRequest> {
        val order = LinkedHashSet<ArtworkRequest>()
        fun add(index: Int) {
            window.requests.getOrNull(index - window.start)?.let(order::add)
        }
        add(window.selectedIndex)
        for (distance in 1..ARTWORK_WINDOW_RADIUS) {
            add(window.selectedIndex + distance * direction)
            add(window.selectedIndex - distance * direction)
        }
        return order.toList()
    }

    private fun clear() {
        cache.clear()
        cacheBytes = 0
        oversized = null
        failed.clear()
        window = ArtworkWindow.EMPTY
        priority = emptyList()
        budgetRank = Int.MAX_VALUE
        direction = 1
    }

    private fun start() {
        synchronized(lock) {
            if (worker == null) worker = scope.launch(dispatcher) { work() }
        }
        wake.trySend(Unit)
    }

    private suspend fun work() {
        while (true) {
            val request = next()
            if (request == null) {
                wake.receive()
                continue
            }
            // Native decoding cannot be interrupted; this single worker is the only caller of the decoder.
            val artwork = try {
                decoder(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            }
            finish(request, artwork)
        }
    }

    private fun next(): ArtworkRequest? = synchronized(lock) {
        val owner = session
        if (owner == null || !active) return null
        priority.withIndex().firstOrNull { (rank, request) ->
            rank < budgetRank && request !in cache && request !in failed && oversized?.first != request
        }?.value?.also { inFlightSession = owner }
    }

    private fun finish(request: ArtworkRequest, artwork: T?) = synchronized(lock) {
        val owner = inFlightSession
        inFlightSession = null
        val rank = priority.indexOf(request)
        if (owner == null || owner !== session || rank < 0) return
        val selected = request == window.selected
        if (artwork == null) {
            failed += request
            if (selected) selectedDecoded.value++
            return
        }
        val bytes = sizeOf(artwork)
        if (bytes <= 0 || bytes > maxCacheBytes) {
            // The selected row is still shown, but oversized artwork never displaces the window's cache.
            if (selected) oversized = request to artwork else failed += request
        } else {
            cache[request] = Entry(artwork, bytes)
            cacheBytes += bytes
            evictFarthest()
        }
        if (selected) selectedDecoded.value++
    }

    private fun evictFarthest() {
        while (cacheBytes > maxCacheBytes) {
            val victim = cache.keys.maxBy { priority.indexOf(it) }
            cacheBytes -= cache.remove(victim)?.bytes ?: 0
            // Rows at or beyond the evicted rank would only displace nearer artwork again.
            budgetRank = min(budgetRank, priority.indexOf(victim))
        }
    }
}

private fun decodeArtwork(request: ArtworkRequest): ImageBitmap? {
    // Unassigned system and category art resolves to a conventional path that usually does not exist.
    if (!File(request.path).isFile) return null
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(request.path, options)
        options.inJustDecodeBounds = false
        options.inSampleSize = artworkSampleSize(options.outWidth, options.outHeight, request)
        val bitmap = BitmapFactory.decodeFile(request.path, options)
        if (bitmap == null) ErrorLog.write("Unable to decode artwork: ${request.path}")
        bitmap?.prepareToDraw()
        bitmap?.asImageBitmap()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ErrorLog.error("Unable to decode artwork: ${request.path}", error)
        null
    }
}

// One list is on screen at a time, so a single process-wide cache and worker serve every library list.
private val libraryArtworkCache = ListArtworkCache(
    decoder = ::decodeArtwork,
    sizeOf = { it.asAndroidBitmap().byteCount },
    scope = CoroutineScope(SupervisorJob()),
)

/** Pixel size of an artwork panel taking [widthPercent] of a list row measured as [rowSize]. */
@Composable
internal fun artworkPanelSize(
    rowSize: IntSize,
    widthPercent: Int,
    topPadding: Dp = 0.dp,
    heightFraction: Float = 1f,
): IntSize? {
    if (widthPercent <= 0 || rowSize.width <= 0 || rowSize.height <= 0) return null
    val verticalPadding = maxOf(topPadding, LocalArtworkStatusBarHeight.current.value)
    val (startPx, verticalPx) = with(LocalDensity.current) { 16.dp.roundToPx() to verticalPadding.roundToPx() }
    val width = (rowSize.width * widthPercent / 100f).roundToInt() - startPx
    val height = ((rowSize.height - 2 * verticalPx) * heightFraction).roundToInt()
    return if (width > 0 && height > 0) IntSize(width, height) else null
}

/**
 * Opens a cache session for the list identified by [listKey] (null when artwork is hidden), keeps the cache's
 * window in sync with [window] and returns the selected row's artwork if it is already decoded.
 */
@Composable
internal fun rememberListArtwork(listKey: Any?, window: ArtworkWindow): ImageBitmap? =
    rememberListArtwork(listKey, window, LocalUiActive.current, libraryArtworkCache)

@Composable
internal fun <T> rememberListArtwork(
    listKey: Any?,
    window: ArtworkWindow,
    uiActive: StateFlow<Boolean>,
    cache: ListArtworkCache<T>,
): T? {
    val latestWindow by rememberUpdatedState(window)
    var session by remember(cache) { mutableStateOf<ListArtworkCache.Session?>(null) }
    DisposableEffect(cache, listKey) {
        val opened = listKey?.let { cache.open(uiActive.value) }
        opened?.let { cache.update(it, latestWindow) }
        session = opened
        onDispose { opened?.let(cache::close) }
    }
    SideEffect { session?.let { cache.update(it, window) } }
    LaunchedEffect(session, uiActive) {
        val owner = session ?: return@LaunchedEffect
        uiActive.collect { cache.setActive(owner, it) }
    }
    cache.changes.collectAsState().value
    if (listKey == null) return null
    return window.selected?.let(cache::peek)
}