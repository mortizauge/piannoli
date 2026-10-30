package dev.piannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.piannoli.scorza.config.CoreInfoRepository
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.launcher.LaunchManager
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScanModule {

    @Provides @Singleton
    fun provideCoreInfoRepository(@ApplicationContext context: Context): CoreInfoRepository {
        val repo = CoreInfoRepository(
            context.assets,
            context.filesDir,
            File(context.applicationInfo.sourceDir).lastModified()
        )
        repo.load()
        return repo
    }

    @Provides @Singleton
    fun providePlatformConfig(
        paths: PiannoliPathsProvider,
        @ApplicationContext context: Context,
        coreInfo: CoreInfoRepository,
    ): PlatformConfig {
        val bundledCoresDir = LaunchManager.extractBundledCores(context)
        return PlatformConfig(
            { paths.root }, context.assets, coreInfo, bundledCoresDir,
            context.getString(dev.piannoli.scorza.R.string.value_empty_override),
            context.getString(dev.piannoli.scorza.R.string.value_needs_setup),
        )
    }
}
