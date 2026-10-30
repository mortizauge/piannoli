package dev.cannoli.scorza.scraper

import dev.cannoli.scorza.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenScraperProvider internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val developerId: String,
    private val developerPassword: String,
    private val now: () -> Long = System::currentTimeMillis,
) : ScraperProvider {
    @Inject constructor() : this(
        OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followSslRedirects(false).build(),
        "https://api.screenscraper.fr/api2/".toHttpUrl(),
        BuildConfig.SCREENSCRAPER_DEV_ID,
        BuildConfig.SCREENSCRAPER_DEV_PASSWORD,
    )

    override val configured: Boolean get() = developerId.isNotBlank() && developerPassword.isNotBlank()
    private data class Quota(var nextRequestAt: Long = 0L, var dailyLimitUntil: Long = 0L)
    private val quotas = mutableMapOf<String, Quota>()
    private fun quota(account: ScraperAccount): Quota = quotas.getOrPut(account.username) { Quota() }

    override fun systems(account: ScraperAccount): List<ScraperSystem> {
        try {
            val response = json(account, "systemesListe.php")
            val systems = response.getJSONArray("systemes")
            if (systems.length() == 0) throw ScraperException(ScraperError.INVALID_RESPONSE)
            return List(systems.length()) { index ->
                val system = systems.getJSONObject(index)
                val names = system.getJSONObject("noms")
                ScraperSystem(
                    system.getInt("id"),
                    names.optString("nom_eu").ifBlank { names.getString("nom_us") },
                )
            }.sortedBy { it.name.lowercase() }
        } catch (e: JSONException) {
            throw ScraperException(ScraperError.INVALID_RESPONSE)
        }
    }

    override fun search(account: ScraperAccount, system: ScraperSystem, query: String): List<ScraperMatch> {
        require(query.isNotBlank())
        val response = try {
            json(account, "jeuRecherche.php", mapOf("systemeid" to system.id.toString(), "recherche" to query))
        } catch (e: NoMatchesException) {
            return emptyList()
        }
        return parseMatches(response, system)
    }

    override fun download(account: ScraperAccount, match: ScraperMatch): ByteArray {
        val art = match.artwork ?: throw ScraperException(ScraperError.NO_ARTWORK)
        val media = if (art.region.isBlank()) art.type else "${art.type}(${art.region})"
        return request(account, "mediaJeu.php", mapOf(
            "jeuid" to match.id.toString(),
            "systemeid" to match.system.id.toString(),
            "media" to media,
            "outputformat" to "png",
            "maxwidth" to "1024",
            "maxheight" to "1024",
        ), image = true)
    }

    @Synchronized
    private fun json(account: ScraperAccount, endpoint: String, params: Map<String, String> = emptyMap()): JSONObject =
        try {
            val bytes = request(account, endpoint, params)
            val response = JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("response")
            updateQuota(account, response.optJSONObject("ssuser"))
            response
        } catch (e: JSONException) {
            throw ScraperException(ScraperError.INVALID_RESPONSE)
        }

    // One in-flight request, with conservative spacing from the account's advertised quota.
    @Synchronized
    private fun request(
        account: ScraperAccount,
        endpoint: String,
        params: Map<String, String>,
        image: Boolean = false,
    ): ByteArray {
        if (!configured) throw ScraperException(ScraperError.NOT_CONFIGURED)
        if (account.username.isBlank() || account.password.isEmpty()) throw ScraperException(ScraperError.ACCOUNT_REQUIRED)
        val quota = quota(account)
        if (now() < quota.dailyLimitUntil) throw ScraperException(ScraperError.DAILY_LIMIT)
        if (now() < quota.nextRequestAt) throw ScraperException(ScraperError.RATE_LIMIT)
        quota.nextRequestAt = now() + 1_000
        val url = baseUrl.newBuilder().addPathSegment(endpoint)
            .addQueryParameter("devid", developerId)
            .addQueryParameter("devpassword", developerPassword)
            .addQueryParameter("softname", "Piannoli")
            .addQueryParameter("ssid", account.username)
            .addQueryParameter("sspassword", account.password)
            .addQueryParameter("output", "json")
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                when (response.code) {
                    404 -> {
                        if (endpoint == "jeuRecherche.php") throw NoMatchesException()
                        throw ScraperException(if (image) ScraperError.NO_ARTWORK else ScraperError.UNAVAILABLE)
                    }
                    401, 403 -> throw ScraperException(ScraperError.AUTHENTICATION)
                    429 -> {
                        quota.nextRequestAt = now() + 60_000
                        throw ScraperException(ScraperError.RATE_LIMIT)
                    }
                    430, 431 -> {
                        quota.dailyLimitUntil = nextDay()
                        throw ScraperException(ScraperError.DAILY_LIMIT)
                    }
                }
                if (!response.isSuccessful) throw ScraperException(ScraperError.UNAVAILABLE)
                val limit = if (image) 10 * 1024 * 1024 else 4 * 1024 * 1024
                return response.readScraperBytes(limit)
            }
        } catch (e: ScraperException) {
            throw e
        } catch (e: NoMatchesException) {
            throw e
        } catch (e: IOException) {
            // OkHttp exception messages can contain credential-bearing URLs.
            throw ScraperException(ScraperError.NETWORK)
        }
    }

    @Synchronized
    private fun updateQuota(account: ScraperAccount, user: JSONObject?) {
        if (user == null) return
        val quota = quota(account)
        val perMinute = user.optInt("maxrequestspermin", user.optInt("maxrequestsperdmin"))
        if (perMinute > 0) quota.nextRequestAt = maxOf(quota.nextRequestAt, now() + (60_000L + perMinute - 1) / perMinute)
        val daily = user.optInt("maxrequestsperday")
        val failedDaily = user.optInt("maxrequestskoperday")
        if ((daily > 0 && user.optInt("requeststoday") >= daily) ||
            (failedDaily > 0 && user.optInt("requestskotoday") >= failedDaily)
        ) quota.dailyLimitUntil = nextDay()
    }

    private fun nextDay(): Long = Instant.ofEpochMilli(now()).atZone(ZoneId.of("Europe/Paris"))
        .toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()

    private class NoMatchesException : IOException()

    companion object {
        internal fun parseMatches(response: JSONObject, system: ScraperSystem): List<ScraperMatch> {
            try {
                val games = response.getJSONArray("jeux")
                return List(games.length()) { index ->
                    val game = games.getJSONObject(index)
                    val platform = game.getJSONObject("systeme")
                    val names = game.getJSONArray("noms")
                    val titles = List(names.length()) { names.getJSONObject(it) }
                    val title = listOf("wor", "us", "eu", "ss", "jp").firstNotNullOfOrNull { region ->
                        titles.firstOrNull { it.optString("region") == region }?.getString("text")
                    } ?: titles.firstOrNull()?.getString("text")
                        ?: throw ScraperException(ScraperError.INVALID_RESPONSE)
                    val media = game.optJSONArray("medias")
                    val covers = if (media == null) emptyList() else
                        List(media.length()) { media.getJSONObject(it) }.filter { it.optString("type") == "box-2D" }
                    val cover = listOf("wor", "us", "eu", "jp").firstNotNullOfOrNull { region ->
                        covers.firstOrNull { it.optString("region") == region }
                    } ?: covers.firstOrNull()
                    ScraperMatch(
                        game.getInt("id"), title,
                        ScraperSystem(platform.getInt("id"), platform.getString("text")),
                        cover?.let { ScraperArtwork(it.getString("type"), it.optString("region")) },
                    )
                }.filter { it.system.id == system.id }.distinctBy { it.id }
            } catch (e: JSONException) {
                throw ScraperException(ScraperError.INVALID_RESPONSE)
            }
        }
    }
}
