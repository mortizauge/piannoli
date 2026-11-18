package dev.piannoli.scorza.scraper

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

    @Test fun `selected media type is used for parsing and download without falling back to covers`() {
        for (media in dev.piannoli.scorza.settings.ScreenScraperMedia.entries) {
            time += 60_000
            response("""{"jeux":[{"id":"10","systeme":{"id":"4","text":"SNES"},
                "noms":[{"region":"wor","text":"Game"}],
                "medias":[{"type":"wheel","region":"us"},{"type":"box-2D","region":"us"},
                    {"type":"ss","region":"wor"},{"type":"sstitle","region":"jp"}]}]}""")
            val result = provider.search(account, system, "Game", media).single()
            server.takeRequest()
            assertEquals(media.apiType, result.artwork?.type)
            time += 60_000
            server.enqueue(MockResponse().setBody("image"))
            provider.download(account, result)
            val url = server.takeRequest().requestUrl!!
            assertEquals("${media.apiType}(${result.artwork?.region})", url.queryParameter("media"))
        }
        time += 60_000
        response("""{"jeux":[{"id":"10","systeme":{"id":"4","text":"SNES"},
            "noms":[{"region":"wor","text":"Game"}],"medias":[{"type":"box-2D","region":"us"}]}]}""")
        assertNull(provider.search(account, system, "Game", dev.piannoli.scorza.settings.ScreenScraperMedia.LOGO).single().artwork)
    }

    @Test fun `login validates an authenticated account and never treats guest access as success`() {
        response("""{"ssuser":{"id":"test-user","numid":"42"}}""")
        provider.authenticate(account)
        assertEquals("/api2/ssuserInfos.php", server.takeRequest().requestUrl?.encodedPath)
        time += 60_000
        response("""{"ssuser":{"id":"guest","numid":"0"}}""")
        assertError(ScraperError.ACCOUNT_AUTHENTICATION) { provider.authenticate(account) }
        time += 60_000
        response("""{"ssuser":{"id":"42"}}""")
        assertError(ScraperError.ACCOUNT_AUTHENTICATION) { provider.authenticate(account) }
    }

    @Test fun `provider distinguishes incorrect account credentials from developer credentials`() {
        server.enqueue(MockResponse().setResponseCode(403)
            .setBody("Erreur de login : V\u00e9rifier les identifiants utilisateurs !"))
        assertError(ScraperError.ACCOUNT_AUTHENTICATION) { provider.authenticate(account) }
        time += 60_000
        server.enqueue(MockResponse().setResponseCode(403)
            .setBody("Erreur de login : V\u00e9rifier vos identifiants d\u00e9veloppeur !"))
        assertError(ScraperError.DEVELOPER_AUTHENTICATION) { provider.authenticate(account) }
    }

    @Test fun `system list sorts regional display names`() {
        response("""{"systemes":[
            {"id":"4","noms":{"nom_eu":"Super Nintendo"}},
            {"id":"3","noms":{"nom_eu":"","nom_us":"NES"}}
        ]}""")
        assertEquals(listOf(3, 4), provider.systems(account).map { it.id })
    }

    @Test fun `system catalogue larger than four MiB is accepted with fixed or chunked transfer`() {
        val body = """{"response":{"systemes":[{"id":"4","noms":{"nom_eu":"Super Nintendo"},
            "metadata":"${"x".repeat(4 * 1024 * 1024)}"}]}}"""
        for (chunked in listOf(false, true)) {
            time += 60_000
            server.enqueue(if (chunked) MockResponse().setChunkedBody(body, 8192) else MockResponse().setBody(body))
            assertEquals(listOf(ScraperSystem(4, "Super Nintendo", listOf("Super Nintendo"))),
                provider.systems(account))
        }
    }

    @Test fun `system catalogue still rejects responses exceeding eight MiB`() {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(8 * 1024 * 1024 + 1), 8192))
        assertError(ScraperError.INVALID_RESPONSE) { provider.systems(account) }
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

    @Test fun `preview requests use selected media with smaller dimensions than the final download`() {
        server.enqueue(MockResponse().setBody("preview image"))
        provider.preview(account, ScraperMatch(10, "Game", system, ScraperArtwork("wheel", "us")))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api2/mediaJeu.php", url.encodedPath)
        assertEquals("wheel(us)", url.queryParameter("media"))
        assertEquals("320", url.queryParameter("maxwidth"))
        assertEquals("320", url.queryParameter("maxheight"))
        assertEquals("png", url.queryParameter("outputformat"))
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
