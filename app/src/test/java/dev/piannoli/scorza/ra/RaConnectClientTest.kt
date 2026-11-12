package dev.piannoli.scorza.ra

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RaConnectClientTest {
    private fun client(server: MockWebServer): RaConnectClient {
        val ok = OkHttpClient()
        return RaConnectClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            clientProvider = { ok },
            userAgent = "Piannoli/test",
        )
    }

    @Test fun passwordLogin_usesManagedEndpointAndRequiresSuccessfulToken() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"Bob","Token":"tok"}"""))
        server.enqueue(MockResponse().setBody("""{"Success":false,"Error":"Invalid credentials"}"""))
        server.enqueue(MockResponse().setBody("""{"Success":true,"User":"Bob"}"""))
        server.start()
        val client = client(server)
        assertEquals(RaConnectClient.Login("Bob", "tok"), client.loginWithPassword("bob", "a&b"))
        val request = server.takeRequest()
        assertTrue(request.body.readUtf8().contains("p=a%26b"))
        assertNull(client.loginWithPassword("bob", "wrong"))
        assertNull(client.loginWithPassword("bob", "missing-token"))
        server.shutdown()
    }

    @Test
    fun networkFailure_doesNotLogIn() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString().trimEnd('/')
        server.shutdown()
        val c = RaConnectClient(baseUrlProvider = { url }, clientProvider = { OkHttpClient() }, userAgent = "x")
        assertNull(c.loginWithPassword("bob", "password"))
    }
}
