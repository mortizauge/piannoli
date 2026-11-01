package dev.piannoli.scorza.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.util.ArtworkLookup
import dev.piannoli.scorza.util.AtomicRename
import dev.piannoli.scorza.util.RomDirectoryWalker
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideScraperProvider(provider: dev.piannoli.scorza.scraper.ScreenScraperProvider): dev.piannoli.scorza.scraper.ScraperProvider =
        provider


    @Provides @Singleton
    fun provideAtomicRename(settings: SettingsRepository, walker: RomDirectoryWalker, artwork: ArtworkLookup): AtomicRename =
        AtomicRename(File(settings.sdCardRoot), walker, artwork)
}
