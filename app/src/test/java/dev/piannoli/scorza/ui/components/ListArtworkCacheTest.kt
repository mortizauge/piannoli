package dev.piannoli.scorza.ui.components

import dev.piannoli.scorza.settings.ArtScale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListArtworkCacheTest {
    private val decoded = mutableListOf<String>()
    private var onDecode: (String) -> Unit = {}

    private fun request(path: String) = ArtworkRequest(path, 0L, 100, 100, ArtScale.FIT)

    private fun rows(count: Int, prefix: String = "r") = List(count) { "$prefix${it.toString().padStart(2, '0')}" }

    private fun window(paths: List<String?>, selected: Int) =
        artworkWindow(paths.size, selected) { index -> paths[index]?.let(::request) }

    private fun TestScope.cache(maxCacheBytes: Int = 1024, decode: (String) -> String? = { it }) = ListArtworkCache(
        decoder = { request: ArtworkRequest -> decoded += request.path; onDecode(request.path); decode(request.path) },
        sizeOf = String::length,
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        maxCacheBytes = maxCacheBytes,
    )

    @Test fun `paused session starts no decode, then selected goes first and alternates favouring movement`() = runTest {
        val cache = cache()
        val paths = rows(30)
        val session = cache.open(active = false)
        cache.update(session, window(paths, 15))
        cache.update(session, window(paths, 14))
        runCurrent()
        assertEquals(emptyList<String>(), decoded)
        cache.setActive(session, true)
        runCurrent()
        assertEquals(listOf("r14", "r13", "r15", "r12", "r16"), decoded.take(5))
        assertEquals(21, decoded.size)
    }

    @Test fun `moving inside the window keeps the overlap and evicts the opposite side`() = runTest {
        val cache = cache()
        val paths = rows(30)
        val session = cache.open(active = true)
        cache.update(session, window(paths, 10))
        runCurrent()
        cache.update(session, window(paths, 12))
        assertNull(cache.peek(request("r00")))
        assertNull(cache.peek(request("r01")))
        assertNotNull(cache.peek(request("r02")))
        assertEquals("r12", cache.peek(request("r12")))
        runCurrent()
        assertEquals(listOf("r21", "r22"), decoded.drop(21))
    }

    @Test fun `selection moving to a pending row reprioritises without restarting or dropping the running decode`() =
        runTest {
            val cache = cache()
            val paths = rows(30)
            val session = cache.open(active = true)
            onDecode = { if (it == "r11") cache.update(session, window(paths, 18)) }
            cache.update(session, window(paths, 10))
            runCurrent()
            assertEquals(listOf("r10", "r11", "r18", "r19", "r17"), decoded.take(5))
            assertEquals(decoded.distinct(), decoded)
            assertEquals("r11", cache.peek(request("r11")))
        }

    @Test fun `jump beyond the window drops cached artwork and restarts at the destination`() = runTest {
        val cache = cache()
        val paths = rows(60)
        val session = cache.open(active = true)
        cache.update(session, window(paths, 10))
        runCurrent()
        cache.update(session, window(paths, 50))
        assertNull(cache.peek(request("r10")))
        runCurrent()
        assertEquals("r50", decoded[21])
        assertEquals(41, decoded.size)
    }

    @Test fun `new list drops previous cache, discards its running decode, and ignores the stale close`() = runTest {
        val cache = cache()
        val first = cache.open(active = true)
        var second: ListArtworkCache.Session? = null
        onDecode = {
            if (it == "a00") second = cache.open(active = true).also { s -> cache.update(s, window(rows(2, "b"), 0)) }
        }
        cache.update(first, window(rows(2, "a"), 0))
        runCurrent()
        cache.close(first)
        cache.update(first, window(rows(2, "a"), 1))
        runCurrent()
        assertEquals(listOf("a00", "b00", "b01"), decoded)
        assertNull(cache.peek(request("a00")))
        assertEquals("b00", cache.peek(request("b00")))
        cache.close(second!!)
        assertNull(cache.peek(request("b00")))
    }

    @Test fun `byte budget keeps selected and nearest rows without reloading rejected rows`() = runTest {
        val cache = cache(maxCacheBytes = 10)
        val paths = rows(30)
        val session = cache.open(active = true)
        cache.update(session, window(paths, 15))
        runCurrent()
        assertEquals(listOf("r15", "r16", "r14", "r17"), decoded)
        assertNull(cache.peek(request("r17")))
        cache.update(session, window(paths, 16))
        runCurrent()
        // The nearer rejected row now displaces the farthest one; the next farther row is rejected once.
        assertEquals(listOf("r17", "r18"), decoded.drop(4))
        for (path in listOf("r15", "r16", "r17")) assertNotNull(cache.peek(request(path)))
        assertNull(cache.peek(request("r14")))
        assertNull(cache.peek(request("r18")))
    }

    @Test fun `oversized selected art is shown but never cached, failures are not retried`() = runTest {
        val cache = cache(maxCacheBytes = 6) { path -> path.takeUnless { it == "bad" } }
        val paths = listOf("oversized", "bad", "ok")
        val session = cache.open(active = true)
        cache.update(session, window(paths, 0))
        runCurrent()
        assertEquals("oversized", cache.peek(request("oversized")))
        assertEquals("ok", cache.peek(request("ok")))
        cache.update(session, window(paths, 2))
        assertNull(cache.peek(request("oversized")))
        cache.update(session, window(paths, 1))
        runCurrent()
        assertEquals(listOf("oversized", "bad", "ok", "oversized"), decoded)
    }

    @Test fun `duplicate paths share one decode and revisions or panel sizes replace stale artwork`() = runTest {
        val cache = cache()
        val session = cache.open(active = true)
        cache.update(session, window(listOf("same", null, "same"), 0))
        runCurrent()
        val resized = request("same").copy(width = 200)
        cache.update(session, artworkWindow(1, 0) { request("same").copy(revision = 1L) })
        cache.update(session, artworkWindow(1, 0) { resized })
        runCurrent()
        assertEquals(listOf("same", "same"), decoded)
        assertNull(cache.peek(request("same")))
        assertEquals("same", cache.peek(resized))
    }

    @Test fun `sample size keeps panel density without upscaling`() {
        val panel = ArtworkRequest("", 0L, 400, 400, ArtScale.FIT)
        assertEquals(4, artworkSampleSize(2000, 1000, panel))
        assertEquals(2, artworkSampleSize(2000, 1000, panel.copy(scale = ArtScale.FIT_HEIGHT)))
        assertEquals(1, artworkSampleSize(300, 300, panel))
        assertEquals(4, artworkSampleSize(4000, 1000, panel.copy(scale = ArtScale.ORIGINAL)))
    }
}