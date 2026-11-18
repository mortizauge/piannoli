package dev.piannoli.scorza.scraper

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import dev.piannoli.scorza.settings.ScreenScraperMedia
import dev.piannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

internal class ScreenScraperPreviewFetcher(
    private val match: ScraperMatch,
    private val provider: ScraperProvider,
    private val settings: SettingsRepository,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): SourceResult = withContext(Dispatchers.IO) {
        val bytes = ScreenScraperSession(provider, settings).preview(match)
        SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = "image/png",
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(
        private val provider: ScraperProvider,
        private val settings: SettingsRepository,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != SCHEME) return null
            val parts = data.pathSegments
            val systemId = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 }
            val gameId = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
            val type = parts.getOrNull(2)?.takeIf { type -> ScreenScraperMedia.entries.any { it.apiType == type } }
            if (data.authority != "artwork" || parts.size != 3 || systemId == null || gameId == null || type == null) {
                throw ScraperException(ScraperError.INVALID_RESPONSE)
            }
            val match = ScraperMatch(gameId, "", ScraperSystem(systemId, ""),
                ScraperArtwork(type, data.getQueryParameter("region").orEmpty()))
            return ScreenScraperPreviewFetcher(match, provider, settings, options)
        }
    }

    companion object {
        private const val SCHEME = "piannoli-screenscraper"

        fun uri(match: ScraperMatch): String? {
            val art = match.artwork ?: return null
            // ScreenScraper's media URLs embed credentials; image models and cache keys must not.
            return Uri.Builder().scheme(SCHEME).authority("artwork")
                .appendPath(match.system.id.toString()).appendPath(match.id.toString())
                .appendPath(art.type).appendQueryParameter("region", art.region).build().toString()
        }
    }
}
