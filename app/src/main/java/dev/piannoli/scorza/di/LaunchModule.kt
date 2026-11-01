package dev.piannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.launcher.ApkLauncher
import dev.piannoli.scorza.launcher.DelfinoLauncher
import dev.piannoli.scorza.launcher.EmuLauncher
import dev.piannoli.scorza.launcher.InstalledCoreService
import dev.piannoli.scorza.launcher.LaunchManager
import dev.piannoli.scorza.launcher.LaunchState
import dev.piannoli.scorza.launcher.RetroArchLauncher
import dev.piannoli.scorza.settings.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LaunchModule {

    @Provides @Singleton
    fun provideRetroArchLauncher(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
    ): RetroArchLauncher = RetroArchLauncher(context) { settings.retroArchPackage }

    @Provides @Singleton
    fun provideDelfinoLauncher(
        @ApplicationContext context: Context,
    ): DelfinoLauncher = DelfinoLauncher(context)

    @Provides @Singleton
    fun provideLaunchManager(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
        platformConfig: PlatformConfig,
        retroArchLauncher: RetroArchLauncher,
        emuLauncher: EmuLauncher,
        apkLauncher: ApkLauncher,
        delfinoLauncher: DelfinoLauncher,
        launchState: LaunchState,
        activeMappingHolder: dev.piannoli.scorza.input.runtime.ActiveMappingHolder,
        installedCoreService: InstalledCoreService,
        gameOverrides: dev.piannoli.scorza.db.GameOverrideStore,
    ): LaunchManager = LaunchManager(
        context, settings, platformConfig,
        retroArchLauncher, emuLauncher, apkLauncher, delfinoLauncher, launchState, activeMappingHolder,
        installedCoreService, gameOverrides
    )
}
