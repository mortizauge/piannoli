package dev.piannoli.scorza.romm.art

import dev.piannoli.scorza.romm.RommArtUrl
import dev.piannoli.scorza.romm.RommHttp
import dev.piannoli.scorza.scraper.ScrapedArtworkStore
import dev.piannoli.scorza.scraper.readScraperBytes
import dev.piannoli.scorza.util.ScanLog
import okhttp3.Request

class RommArtDownloader(
    private val http: RommHttp,
    private val artwork: ScrapedArtworkStore,
) {
    /** Downloads [coverPath] for [tag]/[baseName] into the Art dir. Returns true on success. */
    fun download(host: String, coverPath: String?, tag: String, baseName: String): Boolean {
        val url = RommArtUrl.resolve(host, coverPath) ?: return false
        return try {
            http.client().newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                artwork.installGame(tag, baseName, resp.readScraperBytes(10 * 1024 * 1024), overwrite = true)
                true
            }
        } catch (e: Exception) {
            ScanLog.write("romm art download failed for $tag/$baseName: ${e.message}")
            false
        }
    }
}
