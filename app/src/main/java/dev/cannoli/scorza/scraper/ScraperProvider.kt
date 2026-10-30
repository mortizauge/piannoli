package dev.cannoli.scorza.scraper

import okhttp3.Response
import java.io.ByteArrayOutputStream

data class ScraperAccount(val username: String, val password: String)
data class ScraperSystem(val id: Int, val name: String)
data class ScraperArtwork(val type: String, val region: String)
data class ScraperMatch(
    val id: Int,
    val name: String,
    val system: ScraperSystem,
    val artwork: ScraperArtwork?,
)

enum class ScraperError {
    NOT_CONFIGURED, ACCOUNT_REQUIRED, AUTHENTICATION, RATE_LIMIT, DAILY_LIMIT,
    UNAVAILABLE, INVALID_RESPONSE, NETWORK, NO_ARTWORK, EXISTING_ARTWORK, STORAGE,
}

class ScraperException(val error: ScraperError) : java.io.IOException(error.name)

interface ScraperProvider {
    val configured: Boolean
    fun systems(account: ScraperAccount): List<ScraperSystem>
    fun search(account: ScraperAccount, system: ScraperSystem, query: String): List<ScraperMatch>
    fun download(account: ScraperAccount, match: ScraperMatch): ByteArray
}

internal fun Response.readScraperBytes(limit: Int): ByteArray {
    val content = body ?: throw ScraperException(ScraperError.INVALID_RESPONSE)
    if (content.contentLength() > limit) throw ScraperException(ScraperError.INVALID_RESPONSE)
    val output = ByteArrayOutputStream()
    content.byteStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw ScraperException(ScraperError.INVALID_RESPONSE)
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}
