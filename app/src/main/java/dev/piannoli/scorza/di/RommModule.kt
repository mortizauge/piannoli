package dev.piannoli.scorza.di

import android.content.Context
import coil.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.piannoli.scorza.config.PiannoliPaths
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.PiannoliDatabase
import dev.piannoli.scorza.db.RommLinkRepository
import dev.piannoli.scorza.db.ScanScheduler
import dev.piannoli.scorza.romm.PlatformMap
import dev.piannoli.scorza.romm.RommClient
import dev.piannoli.scorza.romm.RommConnectionStore
import dev.piannoli.scorza.romm.RommHttp
import dev.piannoli.scorza.romm.RommImageLoader
import dev.piannoli.scorza.romm.RommLibrary
import dev.piannoli.scorza.romm.RommSlugMap
import dev.piannoli.scorza.romm.cache.CachedRommLibrary
import dev.piannoli.scorza.romm.cache.RommDatabase
import dev.piannoli.scorza.romm.cache.RommSyncCoordinator
import dev.piannoli.scorza.romm.download.RommDownloadQueue
import dev.piannoli.scorza.romm.download.RommDownloader
import dev.piannoli.scorza.romm.download.RommInstaller
import dev.piannoli.scorza.romm.sync.DeviceRegistrar
import dev.piannoli.scorza.romm.sync.LocalSaveResolver
import dev.piannoli.scorza.romm.sync.PendingConflictStore
import dev.piannoli.scorza.romm.sync.RestorePromotionStore
import dev.piannoli.scorza.romm.sync.SaveBackupManager
import dev.piannoli.scorza.romm.sync.SaveSyncService
import dev.piannoli.scorza.romm.sync.SaveSyncStatusHolder
import dev.piannoli.scorza.romm.sync.SaveSyncStore
import dev.piannoli.scorza.romm.sync.SlotManager
import dev.piannoli.scorza.romm.sync.SyncHistoryStore
import dev.piannoli.scorza.romm.sync.SyncScheduler
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.ui.viewmodel.RommBrowseViewModel
import dev.piannoli.scorza.util.ArtworkLookup
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RommModule {

    @Provides @Singleton
    fun provideRommHttp(store: RommConnectionStore): RommHttp =
        RommHttp(
            tokenProvider = { store.token },
            allowSelfSignedProvider = { store.allowSelfSigned },
            hostProvider = { store.host },
        )

    @Provides @Singleton
    fun provideRommClient(store: RommConnectionStore, http: RommHttp): RommClient =
        RommClient(
            baseUrlProvider = { store.host },
            clientProvider = { http.client() },
            downloadClientProvider = { http.downloadClient() },
        )

    @Provides @Singleton
    fun provideRommSlugMap(@ApplicationContext context: Context): RommSlugMap =
        RommSlugMap.load(context.assets)

    @Provides @Singleton
    fun providePlatformMap(slugMap: RommSlugMap, platformConfig: PlatformConfig): PlatformMap =
        PlatformMap(slugMap, isSupported = { platformConfig.isKnownTag(it) })

    @Provides @Singleton
    fun provideRommDatabase(paths: PiannoliPathsProvider): RommDatabase =
        RommDatabase { PiannoliPaths(paths.root).rommDatabase }

    @Provides @Singleton
    fun provideRommSyncCoordinator(
        client: RommClient,
        platformMap: PlatformMap,
        db: RommDatabase,
        rommStore: RommConnectionStore,
        @ApplicationContext context: Context,
    ): RommSyncCoordinator = RommSyncCoordinator(
        client, platformMap, db,
        enabledGroups = { rommStore.enabledCollectionGroups() },
        collectionsLabel = { context.getString(dev.piannoli.scorza.R.string.romm_sync_collections) },
    )

    @Provides @Singleton
    fun provideDeviceRegistrar(
        settings: SettingsRepository,
        client: RommClient,
    ): DeviceRegistrar = DeviceRegistrar(settings, client)

    @Provides @Singleton
    fun provideRommDevicePairing(
        client: RommClient,
        store: RommConnectionStore,
        settings: SettingsRepository,
        registrar: DeviceRegistrar,
        @ApplicationContext context: Context,
        @IoScope ioScope: CoroutineScope,
    ): dev.piannoli.scorza.romm.RommDevicePairing = dev.piannoli.scorza.romm.RommDevicePairing(
        client = client,
        store = store,
        settings = settings,
        scope = ioScope,
        io = kotlinx.coroutines.Dispatchers.IO,
        deviceIdentifier = {
            android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "piannoli-unknown"
        },
        deviceName = { registrar.defaultDeviceName() },
        clientVersion = { dev.piannoli.scorza.BuildConfig.VERSION_NAME },
    )

    @Provides @Singleton
    fun provideLocalSaveResolver(paths: PiannoliPathsProvider): LocalSaveResolver =
        LocalSaveResolver(paths.root)

    @Provides @Singleton
    fun provideSaveBackupManager(paths: PiannoliPathsProvider, resolver: LocalSaveResolver): SaveBackupManager =
        SaveBackupManager(paths.root, resolver)

    @Provides @Singleton
    fun provideSyncHistoryStore(db: PiannoliDatabase): SyncHistoryStore = SyncHistoryStore(db)

    @Provides @Singleton
    fun providePendingConflictStore(db: PiannoliDatabase): PendingConflictStore = PendingConflictStore(db)

    @Provides @Singleton
    fun provideRestorePromotionStore(db: PiannoliDatabase): RestorePromotionStore = RestorePromotionStore(db)

    @Provides @Singleton
    fun provideSaveSyncStatusHolder(): SaveSyncStatusHolder = SaveSyncStatusHolder()

    @Provides @Singleton
    fun provideSaveSyncService(
        client: RommClient,
        connStore: RommConnectionStore,
        settings: SettingsRepository,
        registrar: DeviceRegistrar,
        store: SaveSyncStore,
        resolver: LocalSaveResolver,
        links: RommLinkRepository,
        paths: PiannoliPathsProvider,
        backupManager: SaveBackupManager,
        history: SyncHistoryStore,
        pendingConflicts: PendingConflictStore,
        promotions: RestorePromotionStore,
        statusHolder: SaveSyncStatusHolder,
        matcher: dev.piannoli.scorza.romm.sync.RommCacheMatcher,
        roms: dev.piannoli.scorza.db.RomsRepository,
    ): SaveSyncService = SaveSyncService(client, connStore, settings, registrar, store, resolver, links, paths, backupManager, history, pendingConflicts, promotions, statusHolder, matcher, roms)

    @Provides @Singleton
    fun provideRommCacheMatcher(
        cache: RommDatabase,
    ): dev.piannoli.scorza.romm.sync.RommCacheMatcher = dev.piannoli.scorza.romm.sync.RommCacheMatcher(cache)

    @Provides @Singleton
    fun provideSlotManager(
        client: RommClient,
        store: SaveSyncStore,
        resolver: LocalSaveResolver,
        registrar: DeviceRegistrar,
        paths: PiannoliPathsProvider,
        service: SaveSyncService,
    ): SlotManager = SlotManager(client, store, resolver, registrar, paths, service)

    @Provides @Singleton
    fun provideRommLibrary(db: RommDatabase): RommLibrary =
        CachedRommLibrary(db)

    @Provides @Singleton
    fun provideRommBrowseViewModel(
        library: RommLibrary,
        syncCoordinator: RommSyncCoordinator,
        db: RommDatabase,
        romsRepository: dev.piannoli.scorza.db.RomsRepository,
        linkRepository: RommLinkRepository,
        settings: dev.piannoli.scorza.settings.SettingsRepository,
        client: RommClient,
        paths: PiannoliPathsProvider,
        rommStore: RommConnectionStore,
    ): RommBrowseViewModel =
        RommBrowseViewModel(
            library = library,
            syncCoordinator = syncCoordinator,
            db = db,
            presentNamesFor = { tag -> romsRepository.presentFileNames(tag) },
            linkedIdsProvider = { linkRepository.presentRommIds() },
            hiddenTagsProvider = { settings.hiddenRommPlatforms },
            firmwareFor = { platformId -> client.getFirmware(platformId) },
            biosDirFor = { tag -> dev.piannoli.scorza.config.PiannoliPaths(paths.root).biosFor(tag) },
            enabledCollectionGroups = { rommStore.enabledCollectionGroups() },
            serverUnsupported = { dev.piannoli.scorza.romm.RommCapabilities.isKnownUnsupported(rommStore.serverVersion) },
        )

    @Provides @Singleton
    fun provideRommImageLoader(
        @ApplicationContext context: Context,
        http: RommHttp,
        paths: PiannoliPathsProvider,
    ): ImageLoader =
        RommImageLoader.build(context, http, java.io.File(dev.piannoli.scorza.config.PiannoliPaths(paths.root).configCache, "RommArt"))

    @Provides @Singleton
    fun provideRommArtDownloader(
        http: RommHttp,
        paths: PiannoliPathsProvider,
    ): dev.piannoli.scorza.romm.art.RommArtDownloader =
        dev.piannoli.scorza.romm.art.RommArtDownloader(http, paths)

    @Provides @Singleton
    fun provideRommArtFetcher(
        roms: dev.piannoli.scorza.db.RomsRepository,
        artwork: ArtworkLookup,
        db: RommDatabase,
        links: RommLinkRepository,
        store: RommConnectionStore,
        artDownloader: dev.piannoli.scorza.romm.art.RommArtDownloader,
        paths: PiannoliPathsProvider,
        settings: dev.piannoli.scorza.settings.SettingsRepository,
        @IoScope ioScope: CoroutineScope,
    ): dev.piannoli.scorza.romm.art.RommArtFetcher = dev.piannoli.scorza.romm.art.RommArtFetcher(
        roms = roms,
        artwork = artwork,
        db = db,
        links = links,
        store = store,
        artDownloader = artDownloader,
        paths = paths,
        concurrency = { settings.concurrentDownloads },
        scope = ioScope,
    )

    @Provides @Singleton
    fun provideRommDownloader(
        client: RommClient,
        links: RommLinkRepository,
        artwork: ArtworkLookup,
        artDownloader: dev.piannoli.scorza.romm.art.RommArtDownloader,
        scanScheduler: ScanScheduler,
        store: RommConnectionStore,
        http: RommHttp,
        paths: PiannoliPathsProvider,
        settings: dev.piannoli.scorza.settings.SettingsRepository,
        @IoScope ioScope: CoroutineScope,
    ): RommDownloader = RommDownloader(
        queue = RommDownloadQueue(),
        client = client,
        installer = RommInstaller(),
        links = links,
        artwork = artwork,
        artDownloader = artDownloader,
        scanScheduler = scanScheduler,
        store = store,
        http = http,
        paths = paths,
        concurrency = { settings.concurrentDownloads },
        scope = ioScope,
    )

    @Provides @Singleton
    fun provideSyncScheduler(
        @ApplicationContext context: Context,
        service: SaveSyncService,
        statusHolder: SaveSyncStatusHolder,
        platformConfig: PlatformConfig,
        settings: SettingsRepository,
        paths: PiannoliPathsProvider,
        @IoScope ioScope: CoroutineScope,
        http: RommHttp,
    ): SyncScheduler = SyncScheduler(context, service, statusHolder, platformConfig, settings, { paths.romDir }, ioScope, http)
}
