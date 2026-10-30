package dev.piannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.piannoli.scorza.config.PiannoliPaths
import dev.piannoli.scorza.input.autoconfig.AssetCfgSource
import dev.piannoli.scorza.input.autoconfig.AutoconfigLoader
import dev.piannoli.scorza.input.autoconfig.BundledAutoconfigEntries
import dev.piannoli.scorza.input.repo.MappingRepository
import dev.piannoli.scorza.input.resolver.MappingResolver
import dev.piannoli.scorza.input.runtime.ActiveMappingHolder
import dev.piannoli.scorza.input.runtime.ControllerBridge
import dev.piannoli.scorza.input.runtime.PortRouter
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BundledRetroArchAutoconfig

@Module
@InstallIn(SingletonComponent::class)
object ControllerBindingsModule {

    @Provides
    @Singleton
    fun provideMappingRepository(paths: PiannoliPathsProvider): MappingRepository =
        MappingRepository { PiannoliPaths(paths.root).configInputMappings }

    @Provides
    @Singleton
    fun provideBundledAutoconfigEntries(
        @ApplicationContext context: Context,
    ): BundledAutoconfigEntries =
        BundledAutoconfigEntries(AutoconfigLoader(AssetCfgSource(context)).entries())

    @Provides
    @Singleton
    fun provideMappingResolver(
        repository: MappingRepository,
        bundled: BundledAutoconfigEntries,
        paths: PiannoliPathsProvider,
        hints: dev.piannoli.scorza.input.hints.ControllerHintTable,
    ): MappingResolver = MappingResolver(
        repository = repository,
        bundledRetroArchEntries = bundled,
        hints = hints,
        mappingsDir = PiannoliPaths(paths.root).configInputMappings,
    )

    @Provides
    @Singleton
    fun provideControllerHintTable(
        @ApplicationContext context: Context,
    ): dev.piannoli.scorza.input.hints.ControllerHintTable =
        dev.piannoli.scorza.input.hints.ControllerHintTable.fromAssets(context)

    @Provides
    @Singleton
    fun providePortRouter(): PortRouter = PortRouter()

    @Provides
    @Singleton
    fun provideActiveMappingHolder(): ActiveMappingHolder = ActiveMappingHolder()

    @Provides
    @Singleton
    fun provideControllerBridge(
        resolver: MappingResolver,
        portRouter: PortRouter,
        activeMappingHolder: ActiveMappingHolder,
        mappingRepository: MappingRepository,
        blacklist: dev.piannoli.scorza.input.ControllerBlacklist,
        bundled: BundledAutoconfigEntries,
        hints: dev.piannoli.scorza.input.hints.ControllerHintTable,
    ): ControllerBridge = ControllerBridge(
        resolver = resolver,
        portRouter = portRouter,
        activeMappingHolder = activeMappingHolder,
        mappingRepository = mappingRepository,
        blacklist = blacklist,
        bundledCfgs = bundled,
        hints = hints,
        devKeyboardEnabled = dev.piannoli.scorza.BuildConfig.DEBUG &&
            dev.piannoli.scorza.util.DeviceType.isAvd(),
    )
}
