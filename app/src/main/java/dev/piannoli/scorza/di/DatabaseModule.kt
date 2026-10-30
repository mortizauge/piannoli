package dev.piannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.piannoli.scorza.db.AppsRepository
import dev.piannoli.scorza.db.PiannoliDatabase
import dev.piannoli.scorza.db.CollectionsRepository
import dev.piannoli.scorza.db.GameOverrideStore
import dev.piannoli.scorza.db.RecentlyPlayedRepository
import dev.piannoli.scorza.db.RomScanner
import dev.piannoli.scorza.db.RommLinkRepository
import dev.piannoli.scorza.romm.sync.SaveSyncStore
import dev.piannoli.scorza.db.RomsRepository
import dev.piannoli.scorza.util.ArcadeTitleLookup
import dev.piannoli.scorza.util.ArtworkLookup
import dev.piannoli.scorza.util.RomDirectoryWalker
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun providePiannoliDatabase(paths: PiannoliPathsProvider): PiannoliDatabase =
        PiannoliDatabase(paths)

    @Provides @Singleton
    fun provideGameOverrideStore(db: PiannoliDatabase): GameOverrideStore =
        GameOverrideStore(db)

    @Provides @Singleton
    fun provideArtworkLookup(paths: PiannoliPathsProvider): ArtworkLookup =
        ArtworkLookup(paths)

    @Provides @Singleton
    fun provideArcadeTitleLookup(paths: PiannoliPathsProvider): ArcadeTitleLookup =
        ArcadeTitleLookup(paths)

    @Provides @Singleton
    fun provideRomDirectoryWalker(
        paths: PiannoliPathsProvider,
        arcadeTitleLookup: ArcadeTitleLookup,
        @ApplicationContext context: Context,
    ): RomDirectoryWalker = RomDirectoryWalker(paths, context.assets, arcadeTitleLookup)

    @Provides @Singleton
    fun provideRomScanner(
        db: PiannoliDatabase,
        walker: RomDirectoryWalker,
        artwork: ArtworkLookup,
    ): RomScanner = RomScanner(db, walker, artwork)

    @Provides @Singleton
    fun provideRomsRepository(
        paths: PiannoliPathsProvider,
        db: PiannoliDatabase,
        artwork: ArtworkLookup,
    ): RomsRepository = RomsRepository(paths, db, artwork)

    @Provides @Singleton
    fun provideAppsRepository(db: PiannoliDatabase, artwork: ArtworkLookup): AppsRepository =
        AppsRepository(db, artwork)

    @Provides @Singleton
    fun provideCollectionsRepository(db: PiannoliDatabase): CollectionsRepository =
        CollectionsRepository(db)

    @Provides @Singleton
    fun provideRecentlyPlayedRepository(db: PiannoliDatabase): RecentlyPlayedRepository =
        RecentlyPlayedRepository(db)

    @Provides @Singleton
    fun provideRommLinkRepository(
        db: PiannoliDatabase,
        paths: PiannoliPathsProvider,
    ): RommLinkRepository = RommLinkRepository(db) { paths.romDir }

    @Provides
    @Singleton
    fun provideSaveSyncStore(db: PiannoliDatabase): SaveSyncStore = SaveSyncStore(db)
}
