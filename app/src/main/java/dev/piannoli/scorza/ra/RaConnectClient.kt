package dev.piannoli.scorza.ra

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class RaConnectClient(
    private val baseUrlProvider: () -> String = { "https://retroachievements.org" },
    private val clientProvider: () -> OkHttpClient = { sharedClient },
    private val userAgent: String = "Piannoli",
) {
    private data class RawResponse(val code: Int, val body: String)
    data class Login(val username: String, val token: String)

    fun loginWithPassword(username: String, password: String): Login? {
        val response = post(FormBody.Builder().add("r", "login2").add("u", username)
            .add("p", password).build())
        if (response.code !in 200..299) return null
        return try {
            val json = JSONObject(response.body)
            val token = json.optString("Token")
            val name = json.optString("User", username)
            if (!json.optBoolean("Success") || token.isBlank() || name.isBlank()) null else Login(name, token)
        } catch (_: org.json.JSONException) { null }
    }

    private fun post(form: FormBody): RawResponse {
        val url = baseUrlProvider().trimEnd('/') + "/dorequest.php"
        val request = Request.Builder().url(url).header("User-Agent", userAgent).post(form).build()
        return try {
            clientProvider().newCall(request).execute().use { resp ->
                RawResponse(resp.code, resp.body?.string() ?: "")
            }
        } catch (_: IOException) {
            RawResponse(-1, "")
        }
    }

    companion object {
        private val sharedClient: OkHttpClient by lazy { OkHttpClient() }
    }
}
