package dev.piannoli.scorza.scraper

import dev.piannoli.scorza.config.PlatformFolders
import dev.piannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.delay

internal class ScreenScraperSession(
    private val provider: ScraperProvider,
    settings: SettingsRepository,
) {
    private val account = ScraperAccount(settings.screenScraperUsername, settings.screenScraperPassword)
    private val media = settings.screenScraperMedia
    private var systems: List<ScraperSystem>? = null

    fun validate() {
        if (!provider.configured) throw ScraperException(ScraperError.NOT_CONFIGURED)
        if (account.username.isBlank() || account.password.isEmpty()) throw ScraperException(ScraperError.ACCOUNT_REQUIRED)
    }

    suspend fun systems(): List<ScraperSystem> {
        systems?.let { return it }
        return request { provider.systems(it) }.also { systems = it }
    }

    suspend fun search(tag: String, query: String, selectedSystem: ScraperSystem? = null): List<ScraperMatch> {
        val canonical = PlatformFolders.canonicalTag(tag)
        val system = selectedSystem ?: systems().filter { candidate ->
            (candidate.aliases + candidate.name).any { PlatformFolders.canonicalTag(it) == canonical }
        }.singleOrNull() ?: throw ScraperException(ScraperError.PLATFORM_UNSUPPORTED)
        return request { provider.search(it, system, query, media) }
    }

    suspend fun download(match: ScraperMatch): ByteArray = request { provider.download(it, match) }

    suspend fun preview(match: ScraperMatch): ByteArray = request { provider.preview(it, match) }

    suspend fun authenticate() = request { provider.authenticate(it) }

    private suspend fun <T> request(block: (ScraperAccount) -> T): T {
        validate()
        delay(provider.requestDelayMillis(account))
        return block(account)
    }
}
