package dev.cannoli.scorza.scraper

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ScreenScraperProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: ScreenScraperProvider
    private var time = 1_000_000L
    private val account = ScraperAccount("test-user", "test-password")
    private val system = ScraperSystem(4, "Super Nintendo")

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        provider = ScreenScraperProvider(OkHttpClient(), server.url("/api2/"), "test-dev", "test-dev-password") { time }
    }

    @After fun cleanup() { server.shutdown() }

    private fun response(body: String) {
        server.enqueue(MockResponse().setBody("""{"response":$body}"""))
    }

    @Test fun `search encodes title and constrains system without sending local paths`() {
        response("""{"jeux":[]}""")
        assertEquals(emptyList<ScraperMatch>(), provider.search(account, system, "Zelda & Link"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api2/jeuRecherche.php", url.encodedPath)
        assertEquals("Zelda & Link", url.queryParameter("recherche"))
        assertEquals("4", url.queryParameter("systemeid"))
        assertEquals("test-user", url.queryParameter("ssid"))
        assertEquals("test-password", url.queryParameter("sspassword"))
        assertNull(url.queryParameter("romnom"))
    }

    @Test fun `parses localized names and front cover region and excludes other platforms`() {
        response("""{"jeux":[
            {"id":"10","systeme":{"id":"4","text":"Super Nintendo"},
             "noms":[{"region":"jp","text":"Japanese title"},{"region":"us","text":"US title"}],
             "medias":[{"type":"ss","region":"wor"},{"type":"box-2D","region":"jp"},{"type":"box-2D","region":"us"}]},
            {"id":"11","systeme":{"id":"3","text":"NES"},"noms":[{"region":"ss","text":"Wrong system"}]}
        ]}""")
        val matches = provider.search(account, system, "Title")
        assertEquals(1, matches.size)
        assertEquals("US title", matches.single().name)
        assertEquals(ScraperArtwork("box-2D", "us"), matches.single().artwork)
    }

    @Test fun `missing cover is retained for explicit user feedback`() {
        response("""{"jeux":[{"id":"10","systeme":{"id":"4","text":"SNES"},
            "noms":[{"region":"ss","text":"Game"}]}]}""")
        assertNull(provider.search(account, system, "Game").single().artwork)
    }

    @Test fun `system list sorts regional display names`() {
        response("""{"systemes":[
            {"id":"4","noms":{"nom_eu":"Super Nintendo"}},
            {"id":"3","noms":{"nom_eu":"","nom_us":"NES"}}
        ]}""")
        assertEquals(listOf(3, 4), provider.systems(account).map { it.id })
    }

    @Test fun `download uses documented media endpoint and selected match`() {
        server.enqueue(MockResponse().setBody("image data"))
        provider.download(account, ScraperMatch(10, "Game", system, ScraperArtwork("box-2D", "us")))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api2/mediaJeu.php", url.encodedPath)
        assertEquals("10", url.queryParameter("jeuid"))
        assertEquals("box-2D(us)", url.queryParameter("media"))
        assertEquals("png", url.queryParameter("outputformat"))
        assertEquals("1024", url.queryParameter("maxwidth"))
    }

    @Test fun `not found is distinct from authentication and malformed responses`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(emptyList<ScraperMatch>(), provider.search(account, system, "Missing"))
        time += 60_000
        server.enqueue(MockResponse().setResponseCode(403))
        assertError(ScraperError.AUTHENTICATION) { provider.search(account, system, "Game") }
        time += 60_000
        server.enqueue(MockResponse().setBody("<html>Unavailable</html>"))
        assertError(ScraperError.INVALID_RESPONSE) { provider.search(account, system, "Game") }
        time += 60_000
        response("""{"systemes":[{"id":"4"}]}""")
        assertError(ScraperError.INVALID_RESPONSE) { provider.systems(account) }
    }

    @Test fun `rate and daily limits block further calls without automatic retries`() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertError(ScraperError.RATE_LIMIT) { provider.search(account, system, "Game") }
        assertError(ScraperError.RATE_LIMIT) { provider.search(account, system, "Game") }
        assertEquals(1, server.requestCount)
        time += 60_000
        server.enqueue(MockResponse().setResponseCode(430))
        assertError(ScraperError.DAILY_LIMIT) { provider.search(account, system, "Game") }
        time += 60_000
        assertError(ScraperError.DAILY_LIMIT) { provider.search(account, system, "Game") }
        assertEquals(2, server.requestCount)
    }

    @Test fun `advertised quotas are enforced before making another request`() {
        response("""{"jeux":[],"ssuser":{"maxrequestspermin":"6","maxrequestsperday":"50","requeststoday":"49"}}""")
        provider.search(account, system, "Game")
        time += 9_999
        assertError(ScraperError.RATE_LIMIT) { provider.search(account, system, "Game") }
        time += 1
        response("""{"jeux":[],"ssuser":{"maxrequestsperday":"50","requeststoday":"50"}}""")
        provider.search(account, system, "Game")
        time += 60_000
        assertError(ScraperError.DAILY_LIMIT) { provider.search(account, system, "Game") }
        assertEquals(2, server.requestCount)
    }

    @Test fun `missing credentials do not contact provider`() {
        val unconfigured = ScreenScraperProvider(OkHttpClient(), server.url("/"), "", "")
        assertFalse(unconfigured.configured)
        assertError(ScraperError.NOT_CONFIGURED) { unconfigured.search(account, system, "Game") }
        assertError(ScraperError.ACCOUNT_REQUIRED) { provider.search(ScraperAccount("", ""), system, "Game") }
        assertEquals(0, server.requestCount)
    }

    @Test fun `daily quotas are account scoped and reset the next day`() {
        server.enqueue(MockResponse().setResponseCode(430))
        assertError(ScraperError.DAILY_LIMIT) { provider.search(account, system, "Game") }
        response("""{"jeux":[]}""")
        provider.search(ScraperAccount("another-user", "another-password"), system, "Game")
        assertError(ScraperError.DAILY_LIMIT) { provider.search(account, system, "Game") }
        time += 86_400_000
        response("""{"jeux":[]}""")
        assertEquals(emptyList<ScraperMatch>(), provider.search(account, system, "Game"))
        assertEquals(3, server.requestCount)
    }

    private fun assertError(error: ScraperError, block: () -> Unit) {
        assertEquals(error, assertThrows(ScraperException::class.java, block).error)
    }
}
