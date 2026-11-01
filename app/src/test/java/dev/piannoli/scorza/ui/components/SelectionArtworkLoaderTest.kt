package dev.piannoli.scorza.ui.components

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionArtworkLoaderTest {
    private fun TestScope.loader(
        decoded: MutableList<String>,
        maxCacheBytes: Int = 1024,
        maxBacklog: Int = 6,
        decode: (String) -> String? = { it },
    ) = SelectionArtworkLoader(
        decoder = { path: String -> decoded.add(path); decode(path) },
        sizeOf = String::length,
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        maxCacheBytes = maxCacheBytes,
        maxBacklog = maxBacklog,
    )

    private suspend fun TestScope.select(
        loader: SelectionArtworkLoader<String>,
        path: String,
        active: MutableStateFlow<Boolean>,
        revision: Long = 0L,
        kind: ArtworkKind = ArtworkKind.GAME,
    ) {
        val request = launch { loader.load(path, active, revision, kind) {} }
        runCurrent()
        request.cancelAndJoin()
    }

    @Test fun `misses decode without delay, cache hits skip the queue, and revisions replace stale cache`() = runTest {
        val decoded = mutableListOf<String>()
        val published = mutableListOf<String?>()
        val loader = loader(decoded) { "$it-${decoded.size}" }
        val active = MutableStateFlow(true)
        var request = launch { loader.load("art", active, publish = published::add) }
        runCurrent()
        assertEquals(listOf("art-1"), published)
        request.cancelAndJoin()
        request = launch { loader.load("art", active, publish = published::add) }
        runCurrent()
        assertEquals(listOf("art-1", "art-1"), published)
        assertEquals(listOf("art"), decoded)
        request.cancelAndJoin()
        request = launch { loader.load("art", active, revision = 1L, publish = published::add) }
        runCurrent()
        assertEquals(listOf("art-1", "art-1", "art-2"), published)
        assertEquals(null, loader.cached("art", 0L))
        assertEquals("art-2", loader.cached("art", 1L))
        assertEquals(0L, currentTime)
        request.cancelAndJoin()
    }

    @Test fun `byte budget evicts least recently used decoded artwork`() = runTest {
        val decoded = mutableListOf<String>()
        val loader = loader(decoded, maxCacheBytes = 6)
        val active = MutableStateFlow(true)
        select(loader, "aa", active)
        select(loader, "bbbb", active)
        select(loader, "aa", active)
        select(loader, "ccc", active)
        assertEquals("aa", loader.cached("aa", 0L))
        assertEquals(null, loader.cached("bbbb", 0L))
        assertEquals("ccc", loader.cached("ccc", 0L))
        select(loader, "bbbb", active)
        assertEquals(listOf("aa", "bbbb", "ccc", "bbbb"), decoded)
    }

    @Test fun `failed decodes publish null once and failed or oversized art is not cached`() = runTest {
        val decoded = mutableListOf<String>()
        val loader = loader(decoded, maxCacheBytes = 3) { path -> path.takeUnless { it == "missing" } }
        val active = MutableStateFlow(true)
        for (path in listOf("ok", "missing", "large", "large")) select(loader, path, active)
        assertEquals(listOf("ok", "missing", "large", "large"), decoded)
        assertEquals("ok", loader.cached("ok", 0L))
        assertEquals(null, loader.cached("missing", 0L))
        assertEquals(null, loader.cached("large", 0L))
        val published = mutableListOf<String?>()
        val request = launch { loader.load("missing", active, publish = published::add) }
        runCurrent()
        active.value = false
        runCurrent()
        active.value = true
        runCurrent()
        assertEquals(listOf<String?>(null), published)
        assertEquals(5, decoded.size)
        request.cancelAndJoin()
    }

    @Test fun `concurrent requests for same artwork share one decoded result`() = runTest {
        val decoded = mutableListOf<String>()
        val published = mutableListOf<String?>()
        val loader = loader(decoded)
        val active = MutableStateFlow(true)
        val requests = List(2) { launch { loader.load("art", active, publish = published::add) } }
        runCurrent()
        assertEquals(listOf("art"), decoded)
        assertEquals(listOf("art", "art"), published)
        requests.forEach { it.cancelAndJoin() }
    }

    @Test fun `paused backlog merges duplicates, drops oldest, and resumes current then newest first`() = runTest {
        val decoded = mutableListOf<String>()
        val published = mutableListOf<String?>()
        val loader = loader(decoded, maxBacklog = 4)
        val active = MutableStateFlow(false)
        for (path in listOf("a", "b", "c", "d", "b", "e", "f")) select(loader, path, active)
        val current = launch { loader.load("x", active, publish = published::add) }
        runCurrent()
        assertTrue(decoded.isEmpty())
        active.value = true
        runCurrent()
        assertEquals(listOf("x", "f", "e", "b"), decoded)
        assertEquals(listOf("x"), published)
        assertEquals("f", loader.cached("f", 0L))
        current.cancelAndJoin()
    }

    @Test fun `new revision invalidates queued older revision of same path`() = runTest {
        val decoded = mutableListOf<String>()
        val loader = loader(decoded)
        val active = MutableStateFlow(false)
        select(loader, "art", active)
        select(loader, "art", active, revision = 1L)
        val current = launch { loader.load("other", active) {} }
        runCurrent()
        active.value = true
        runCurrent()
        assertEquals(listOf("other", "art"), decoded)
        assertEquals(null, loader.cached("art", 0L))
        assertEquals("art", loader.cached("art", 1L))
        current.cancelAndJoin()
    }

    @Test fun `hiding a category drops its backlog but preserves shared and visible category requests`() = runTest {
        val decoded = mutableListOf<String>()
        val loader = loader(decoded)
        val active = MutableStateFlow(false)
        select(loader, "hidden-old", active)
        select(loader, "shared", active)
        select(loader, "shared", active, kind = ArtworkKind.SYSTEM)
        select(loader, "system-old", active, kind = ArtworkKind.SYSTEM)
        loader.setEnabled(ArtworkKind.GAME, false)
        val visible = launch { loader.load("system-current", active, kind = ArtworkKind.SYSTEM) {} }
        runCurrent()
        active.value = true
        runCurrent()
        assertEquals(listOf("system-current", "system-old", "shared"), decoded)
        visible.cancelAndJoin()
        loader.setEnabled(ArtworkKind.GAME, true)
        select(loader, "game-current", active)
        assertEquals(listOf("system-current", "system-old", "shared", "game-current"), decoded)
        assertEquals(null, loader.cached("hidden-old", 0L))
    }

    @Test fun `disabled category neither touches cache recency nor registers new work`() = runTest {
        val decoded = mutableListOf<String>()
        val loader = loader(decoded, maxCacheBytes = 4)
        val active = MutableStateFlow(true)
        select(loader, "aa", active)
        select(loader, "bb", active, kind = ArtworkKind.SYSTEM)
        loader.setEnabled(ArtworkKind.GAME, false)
        assertEquals(null, loader.cached("aa", 0L))
        select(loader, "hidden-new", active)
        select(loader, "cc", active, kind = ArtworkKind.SYSTEM)
        loader.setEnabled(ArtworkKind.GAME, true)
        assertEquals(null, loader.cached("aa", 0L))
        assertEquals("bb", loader.cached("bb", 0L, ArtworkKind.SYSTEM))
        assertEquals(listOf("aa", "bb", "cc"), decoded)
    }

    @Test fun `backlog eviction does not discard a selection still requested by a visible category`() = runTest {
        val decoded = mutableListOf<String>()
        val loader = loader(decoded, maxBacklog = 1)
        val active = MutableStateFlow(false)
        val requests = listOf(
            launch { loader.load("game", active) {} },
            launch { loader.load("system", active, kind = ArtworkKind.SYSTEM) {} },
        )
        runCurrent()
        active.value = true
        runCurrent()
        assertEquals(listOf("system", "game"), decoded)
        requests.forEach { it.cancelAndJoin() }
    }

    private class BlockingDecoder(private val blockedPath: String) : (String) -> String {
        val release = CountDownLatch(1)
        val decoded: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val maximum = AtomicInteger()
        private val started = CountDownLatch(1)
        private val running = AtomicInteger()

        override fun invoke(path: String): String {
            val count = running.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            try {
                decoded.add(path)
                if (path == blockedPath && started.count > 0) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return "$path-${decoded.size}"
            } finally {
                running.decrementAndGet()
            }
        }

        suspend fun awaitStarted() = withContext(Dispatchers.IO) {
            assertTrue(started.await(5, TimeUnit.SECONDS))
        }
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private fun blockingTest(blockedPath: String, body: suspend CoroutineScope.(
        BlockingDecoder,
        SelectionArtworkLoader<String>,
    ) -> Unit) = runBlocking {
        val decoder = BlockingDecoder(blockedPath)
        val workerScope = CoroutineScope(SupervisorJob())
        val loader = SelectionArtworkLoader(decoder = decoder, sizeOf = String::length, scope = workerScope)
        try {
            body(decoder, loader)
        } finally {
            decoder.release.countDown()
            workerScope.cancel()
        }
    }

    @Test fun `hiding during native decode discards result and backlog while other category keeps working`() =
        blockingTest("game-running") { decoder, loader ->
            val active = MutableStateFlow(true)
            val hiddenPublished = mutableListOf<String?>()
            val visiblePublished: MutableList<String?> = Collections.synchronizedList(mutableListOf())
            val running = launch(start = CoroutineStart.UNDISPATCHED) {
                loader.load("game-running", active, publish = hiddenPublished::add)
            }
            decoder.awaitStarted()
            val queued = launch(start = CoroutineStart.UNDISPATCHED) { loader.load("game-queued", active) {} }
            queued.cancelAndJoin()
            val visible = launch(start = CoroutineStart.UNDISPATCHED) {
                loader.load("system-current", active, kind = ArtworkKind.SYSTEM, publish = visiblePublished::add)
            }
            try {
                loader.setEnabled(ArtworkKind.GAME, false)
                decoder.release.countDown()
                eventually { visiblePublished.isNotEmpty() }
                assertEquals(listOf("game-running", "system-current"), decoder.decoded.toList())
                loader.setEnabled(ArtworkKind.GAME, true)
                assertEquals(null, loader.cached("game-running", 0L))
                assertEquals(null, loader.cached("game-queued", 0L))
                assertTrue(hiddenPublished.isEmpty())
                assertEquals(listOf("system-current-2"), visiblePublished.toList())
            } finally {
                running.cancelAndJoin()
                visible.cancelAndJoin()
            }
        }

    @Test fun `in flight image shared with visible category is cached and published only there`() =
        blockingTest("shared") { decoder, loader ->
            val active = MutableStateFlow(true)
            val hiddenPublished = mutableListOf<String?>()
            val visiblePublished: MutableList<String?> = Collections.synchronizedList(mutableListOf())
            val game = launch(start = CoroutineStart.UNDISPATCHED) {
                loader.load("shared", active, publish = hiddenPublished::add)
            }
            decoder.awaitStarted()
            val system = launch(start = CoroutineStart.UNDISPATCHED) {
                loader.load("shared", active, kind = ArtworkKind.SYSTEM, publish = visiblePublished::add)
            }
            try {
                loader.setEnabled(ArtworkKind.GAME, false)
                decoder.release.countDown()
                eventually { visiblePublished.isNotEmpty() }
                assertTrue(hiddenPublished.isEmpty())
                assertEquals(listOf("shared-1"), visiblePublished.toList())
                assertEquals("shared-1", loader.cached("shared", 0L, ArtworkKind.SYSTEM))
                assertEquals(1, decoder.decoded.size)
            } finally {
                game.cancelAndJoin()
                system.cancelAndJoin()
            }
        }

    @Test fun `running native decode finishes into cache before current selection and recent backlog`() =
        blockingTest("first") { decoder, loader ->
            val active = MutableStateFlow(true)
            val published: MutableList<String?> = Collections.synchronizedList(mutableListOf())
            val first = launch { loader.load("first", active, publish = published::add) }
            var latest: Job? = null
            try {
                decoder.awaitStarted()
                first.cancelAndJoin()
                for (path in listOf("older", "obsolete")) {
                    val request = launch { loader.load(path, active, publish = published::add) }
                    delay(20)
                    request.cancelAndJoin()
                }
                latest = launch { loader.load("latest", active, publish = published::add) }
                delay(100)
                assertEquals(listOf("first"), decoder.decoded.toList())
                decoder.release.countDown()
                eventually { decoder.decoded.size == 4 && published.isNotEmpty() }
                assertEquals(listOf("first", "latest", "obsolete", "older"), decoder.decoded.toList())
                assertEquals(1, decoder.maximum.get())
                assertEquals(listOf("latest-2"), published.toList())
                assertEquals("first-1", loader.cached("first", 0L))
            } finally {
                first.cancelAndJoin()
                latest?.cancelAndJoin()
            }
        }

    @Test fun `focus loss pauses queue while running decode caches without publishing`() =
        blockingTest("a") { decoder, loader ->
            val active = MutableStateFlow(true)
            val published: MutableList<String?> = Collections.synchronizedList(mutableListOf())
            val first = launch { loader.load("a", active, publish = published::add) }
            var second: Job? = null
            try {
                decoder.awaitStarted()
                active.value = false
                first.cancelAndJoin()
                second = launch { loader.load("b", active, publish = published::add) }
                delay(50)
                decoder.release.countDown()
                eventually { loader.cached("a", 0L) != null }
                delay(100)
                assertEquals(listOf("a"), decoder.decoded.toList())
                assertTrue(published.isEmpty())
                active.value = true
                eventually { published.isNotEmpty() }
                assertEquals(listOf("b-2"), published.toList())
            } finally {
                first.cancelAndJoin()
                second?.cancelAndJoin()
            }
        }

    @Test fun `retained selection publishes decode finished while unfocused on return`() =
        blockingTest("current") { decoder, loader ->
            val active = MutableStateFlow(true)
            val published: MutableList<String?> = Collections.synchronizedList(mutableListOf())
            val request = launch { loader.load("current", active, publish = published::add) }
            try {
                decoder.awaitStarted()
                active.value = false
                delay(20)
                decoder.release.countDown()
                eventually { loader.cached("current", 0L) != null }
                delay(50)
                assertTrue(published.isEmpty())
                active.value = true
                eventually { published.isNotEmpty() }
                assertEquals(listOf("current-1"), published.toList())
                assertEquals(1, decoder.decoded.size)
            } finally {
                request.cancelAndJoin()
            }
        }

    @Test fun `running decode of superseded revision is not cached`() = blockingTest("art") { decoder, loader ->
        val active = MutableStateFlow(true)
        val published: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val old = launch { loader.load("art", active) {} }
        var revised: Job? = null
        try {
            decoder.awaitStarted()
            old.cancelAndJoin()
            revised = launch { loader.load("art", active, revision = 1L, publish = published::add) }
            delay(20)
            decoder.release.countDown()
            eventually { published.isNotEmpty() }
            assertEquals(listOf("art-2"), published.toList())
            assertEquals(null, loader.cached("art", 0L))
            assertEquals("art-2", loader.cached("art", 1L))
        } finally {
            old.cancelAndJoin()
            revised?.cancelAndJoin()
        }
    }
}
