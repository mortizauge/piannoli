package dev.piannoli.scorza

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.piannoli.scorza.di.AppFonts
import javax.inject.Inject

@HiltAndroidApp
class PiannoliApp : Application(), coil.ImageLoaderFactory {
    @Inject lateinit var appFonts: AppFonts
    @Inject lateinit var scraperProvider: dev.piannoli.scorza.scraper.ScraperProvider
    @Inject lateinit var settings: dev.piannoli.scorza.settings.SettingsRepository

    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .components {
            add(dev.piannoli.scorza.scraper.ScreenScraperPreviewFetcher.Factory(scraperProvider, settings))
        }
        .build()

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(dev.piannoli.scorza.i18n.LocaleOverride.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        installLogFlushOnCrash()
    }

    // Log writes are queued, so a crash would otherwise take the tail of every log with it,
    // which is the part worth reading. Native crashes still bypass this; tombstones cover those.
    private fun installLogFlushOnCrash() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                dev.piannoli.scorza.util.ErrorLog.error("uncaught on ${thread.name}", error)
                dev.piannoli.scorza.util.LogWriter.flush(CRASH_FLUSH_TIMEOUT_MS)
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    private companion object {
        const val CRASH_FLUSH_TIMEOUT_MS = 2000L
    }
}
