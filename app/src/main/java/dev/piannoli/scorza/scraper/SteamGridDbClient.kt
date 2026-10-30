package dev.piannoli.scorza.scraper

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SteamGridGame(val id: Int, val name: String)
data class SteamGridCover(
    val id: Int,
    val author: String,
    val width: Int,
    val height: Int,
    val url: String,
    val thumbnail: String,
)
data class SteamGridCovers(val covers: List<SteamGridCover>, val hasNextPage: Boolean)

@Singleton
class SteamGridDbClient internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor() : this(
        OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).build(),
        "https://www.steamgriddb.com/api/v2/".toHttpUrl(),
    )

    private val images = client.newBuilder().followRedirects(false).build()
    private var retryAt = 0L

    fun search(apiKey: String, query: String): List<SteamGridGame> {
        require(query.isNotBlank())
        val data = json(apiKey, listOf("search", "autocomplete", query)).array()
        return parse {
            List(data.length()) { index ->
                val game = data.getJSONObject(index)
                SteamGridGame(game.getInt("id"), game.getString("name"))
            }.distinctBy { it.id }
        }
    }

    fun covers(apiKey: String, gameId: Int, page: Int = 0): SteamGridCovers {
        return artwork(apiKey, gameId, page, "grids", portraitOnly = true)
    }

    fun systemArtwork(
        apiKey: String,
        gameId: Int,
        type: dev.piannoli.scorza.settings.SystemArtworkType,
        page: Int = 0,
    ): SteamGridCovers = artwork(apiKey, gameId, page, when (type) {
        dev.piannoli.scorza.settings.SystemArtworkType.LOGO -> "logos"
        dev.piannoli.scorza.settings.SystemArtworkType.ICON -> "icons"
        dev.piannoli.scorza.settings.SystemArtworkType.GRID -> "grids"
    })

    private fun artwork(
        apiKey: String, gameId: Int, page: Int, resource: String, portraitOnly: Boolean = false,
    ): SteamGridCovers {
        require(gameId > 0 && page >= 0)
        val params = mapOf(
            "types" to "static",
            "mimes" to when (resource) {
                "logos" -> "image/png,image/webp"
                "icons" -> "image/png"
                else -> "image/png,image/jpeg,image/webp"
            },
            "nsfw" to "false",
            "humor" to "false",
            "epilepsy" to "false",
            "limit" to PAGE_SIZE.toString(),
            "page" to page.toString(),
        ) + if (portraitOnly) mapOf("dimensions" to "600x900,342x482") else emptyMap()
        val response = json(apiKey, listOf(resource, "game", gameId.toString()), params)
        return parse {
            val data = response.getJSONArray("data")
            val covers = List(data.length()) { index ->
                val cover = data.getJSONObject(index)
                val url = cover.getString("url")
                val thumb = cover.getString("thumb")
                checkedImageUrl(url)
                checkedImageUrl(thumb)
                SteamGridCover(
                    cover.getInt("id"), cover.getJSONObject("author").getString("name"),
                    cover.getInt("width"), cover.getInt("height"), url, thumb,
                )
            }
            val total = response.optInt("total", -1)
            SteamGridCovers(covers, if (total >= 0) (page + 1L) * PAGE_SIZE < total else data.length() == PAGE_SIZE)
        }
    }

    fun download(cover: SteamGridCover): ByteArray {
        val url = checkedImageUrl(cover.url)
        try {
            // CDN requests deliberately carry no API key.
            images.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == 404) throw ScraperException(ScraperError.NO_ARTWORK)
                if (response.code == 429) throw ScraperException(ScraperError.RATE_LIMIT)
                if (!response.isSuccessful) throw ScraperException(ScraperError.UNAVAILABLE)
                return response.readScraperBytes(10 * 1024 * 1024)
            }
        } catch (e: ScraperException) {
            throw e
        } catch (e: IOException) {
            throw ScraperException(ScraperError.NETWORK)
        }
    }

    @Synchronized
    private fun json(apiKey: String, path: List<String>, params: Map<String, String> = emptyMap()): JSONObject {
        if (apiKey.isBlank()) throw ScraperException(ScraperError.ACCOUNT_REQUIRED)
        if (apiKey.any { it <= ' ' || it >= '\u007f' }) throw ScraperException(ScraperError.AUTHENTICATION)
        if (now() < retryAt) throw ScraperException(ScraperError.RATE_LIMIT)
        val url = baseUrl.newBuilder()
            .apply {
                path.forEach { addPathSegment(it) }
                params.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
        try {
            client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build())
                .execute().use { response ->
                    when (response.code) {
                        401, 403 -> throw ScraperException(ScraperError.AUTHENTICATION)
                        404 -> throw ScraperException(ScraperError.NO_ARTWORK)
                        429 -> {
                            val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400) ?: 60
                            retryAt = now() + seconds * 1_000
                            throw ScraperException(ScraperError.RATE_LIMIT)
                        }
                    }
                    if (!response.isSuccessful) throw ScraperException(ScraperError.UNAVAILABLE)
                    return parse {
                        val result = JSONObject(String(response.readScraperBytes(2 * 1024 * 1024), Charsets.UTF_8))
                        if (!result.getBoolean("success")) throw ScraperException(ScraperError.INVALID_RESPONSE)
                        result
                    }
                }
        } catch (e: ScraperException) {
            throw e
        } catch (e: IOException) {
            throw ScraperException(ScraperError.NETWORK)
        }
    }

    private fun JSONObject.array(): JSONArray = parse { getJSONArray("data") }

    private fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw ScraperException(ScraperError.INVALID_RESPONSE)
    }

    companion object {
        const val PAGE_SIZE = 20

        internal fun checkedImageUrl(value: String): HttpUrl {
            val url = value.toHttpUrlOrNull()
            if (url == null || url.scheme != "https" ||
                url.host !in setOf("cdn.steamgriddb.com", "cdn2.steamgriddb.com") ||
                url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()
            ) throw ScraperException(ScraperError.INVALID_RESPONSE)
            return url
        }
    }
}
