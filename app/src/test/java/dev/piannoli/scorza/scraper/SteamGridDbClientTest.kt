package dev.piannoli.scorza.scraper

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SteamGridDbClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SteamGridDbClient
    private var time = 1_000_000L
    private val key = "test-api-key"

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder().followRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == "cdn2.steamgriddb.com") {
                    chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
                } else {
                    chain.proceed(request)
                }
            }.build()
        client = SteamGridDbClient(http, server.url("/api/v2/")) { time }
    }

    @After fun cleanup() { server.shutdown() }

    private fun response(data: String, extra: String = "") {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":$data$extra}"""))
    }

    private fun cover(url: String = "https://cdn2.steamgriddb.com/grid/cover.png"): String = """
        {"id":20,"author":{"name":"Artist"},"width":600,"height":900,
        "url":"$url","thumb":"https://cdn2.steamgriddb.com/thumb/cover.png"}
    """

    @Test fun `search encodes title as one path segment and authenticates only in header`() {
        response("""[{"id":10,"name":"Zelda / Link"}]""")
        assertEquals(listOf(SteamGridGame(10, "Zelda / Link")), client.search(key, "Zelda / Link"))
        val request = server.takeRequest()
        assertEquals("Bearer $key", request.getHeader("Authorization"))
        assertEquals("/api/v2/search/autocomplete/Zelda%20%2F%20Link", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.query)
    }

    @Test fun `cover listing requests safe static portrait images and supports pagination`() {
        response("[${cover()}]", ""","total":21,"page":0,"limit":20""")
        val found = client.covers(key, 10)
        assertTrue(found.hasNextPage)
        assertEquals("Artist", found.covers.single().author)
        assertEquals(600, found.covers.single().width)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/v2/grids/game/10", url.encodedPath)
        assertEquals("600x900,342x482", url.queryParameter("dimensions"))
        assertEquals("static", url.queryParameter("types"))
        assertEquals("image/png,image/jpeg,image/webp", url.queryParameter("mimes"))
        assertEquals("false", url.queryParameter("nsfw"))
        assertEquals("false", url.queryParameter("humor"))
        assertEquals("false", url.queryParameter("epilepsy"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("page"))

        response("[${cover()}]", ""","total":21,"page":1,"limit":20""")
        assertFalse(client.covers(key, 10, page = 1).hasNextPage)
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("page"))
    }

    @Test fun `CDN download never receives API key`() {
        response("[${cover()}]")
        val selected = client.covers(key, 10).covers.single()
        server.takeRequest()
        server.enqueue(MockResponse().setBody("image"))
        assertArrayEquals("image".toByteArray(), client.download(selected))
        val request = server.takeRequest()
        assertEquals("/grid/cover.png", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun `system artwork uses selected resource endpoint without portrait restriction and paginates`() {
        val endpoints = mapOf(
            dev.piannoli.scorza.settings.SystemArtworkType.LOGO to "logos",
            dev.piannoli.scorza.settings.SystemArtworkType.ICON to "icons",
            dev.piannoli.scorza.settings.SystemArtworkType.GRID to "grids",
        )
        endpoints.forEach { (type, endpoint) ->
            response("[${cover()}]", ""","total":41""")
            val result = client.systemArtwork(key, 10, type, 1)
            assertTrue(result.hasNextPage)
            assertEquals(20, result.covers.single().id)
            val request = server.takeRequest()
            val url = request.requestUrl!!
            assertEquals("/api/v2/$endpoint/game/10", url.encodedPath)
            assertNull(url.queryParameter("dimensions"))
            assertEquals("static", url.queryParameter("types"))
            assertEquals(when (type) {
                dev.piannoli.scorza.settings.SystemArtworkType.LOGO -> "image/png,image/webp"
                dev.piannoli.scorza.settings.SystemArtworkType.ICON -> "image/png"
                dev.piannoli.scorza.settings.SystemArtworkType.GRID -> "image/png,image/jpeg,image/webp"
            }, url.queryParameter("mimes"))
            assertEquals("1", url.queryParameter("page"))
            assertEquals("Bearer $key", request.getHeader("Authorization"))
        }
    }

    @Test fun `unexpected image origins and redirects fail without following them`() {
        response("""[${cover("https://example.com/image.png")}]""")
        assertError(ScraperError.INVALID_RESPONSE) { client.covers(key, 10) }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/not-allowed")))
        assertError(ScraperError.UNAVAILABLE) { client.search(key, "Game") }
        assertEquals(2, server.requestCount)
        val selected = SteamGridCover(20, "Artist", 600, 900, "http://cdn2.steamgriddb.com/image.png", "")
        assertError(ScraperError.INVALID_RESPONSE) { client.download(selected) }
        assertEquals(2, server.requestCount)
    }

    @Test fun `missing and invalid keys do not make requests`() {
        assertError(ScraperError.ACCOUNT_REQUIRED) { client.search("", "Game") }
        assertError(ScraperError.AUTHENTICATION) { client.search("bad\nkey", "Game") }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(401))
        assertError(ScraperError.AUTHENTICATION) { client.search(key, "Game") }
    }

    @Test fun `rate limit honors retry after without automatic requests`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        assertError(ScraperError.RATE_LIMIT) { client.search(key, "Game") }
        time += 119_999
        assertError(ScraperError.RATE_LIMIT) { client.search(key, "Game") }
        assertEquals(1, server.requestCount)
        time += 1
        response("[]")
        assertTrue(client.search(key, "Game").isEmpty())
    }

    @Test fun `empty results are distinct from invalid responses and unavailable service`() {
        response("[]")
        assertTrue(client.search(key, "Unknown").isEmpty())
        response("{}")
        assertError(ScraperError.INVALID_RESPONSE) { client.search(key, "Game") }
        server.enqueue(MockResponse().setBody("""{"success":false,"data":[]}"""))
        assertError(ScraperError.INVALID_RESPONSE) { client.search(key, "Game") }
        server.enqueue(MockResponse().setResponseCode(503))
        assertError(ScraperError.UNAVAILABLE) { client.search(key, "Game") }
    }

    @Test fun `oversized API responses are rejected`() {
        server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
        assertError(ScraperError.INVALID_RESPONSE) { client.search(key, "Game") }
    }

    private fun assertError(error: ScraperError, block: () -> Unit) {
        assertEquals(error, assertThrows(ScraperException::class.java, block).error)
    }
}
