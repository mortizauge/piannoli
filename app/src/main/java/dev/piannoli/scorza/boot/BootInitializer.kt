package dev.piannoli.scorza.boot

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.R
import dev.piannoli.scorza.config.PiannoliPaths
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.PiannoliDatabase
import dev.piannoli.scorza.db.CollectionsRepository
import dev.piannoli.scorza.db.ScanScheduler
import dev.piannoli.scorza.db.importer.ImportProgress
import dev.piannoli.scorza.db.importer.ImportResult
import dev.piannoli.scorza.db.importer.Importer
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.di.IoScope
import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.launcher.InstalledCoreService
import dev.piannoli.scorza.launcher.LaunchManager
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.ContentMode
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.setup.SetupCoordinator
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.piannoli.scorza.updater.UpdateManager
import dev.piannoli.scorza.util.ScanLog
import dev.piannoli.scorza.util.StorageLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

sealed interface BootResult {
    data object Success : BootResult
    data class Failure(val message: String) : BootResult
}

@ActivityScoped
class BootInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val piannoliDatabase: PiannoliDatabase,
    private val scanScheduler: ScanScheduler,
    private val piannoliPaths: PiannoliPathsProvider,
    @IoScope private val ioScope: CoroutineScope,
    private val installedCoreService: InstalledCoreService,
    private val gameListViewModel: GameListViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val collectionsRepository: CollectionsRepository,
    private val updateManager: UpdateManager,
    private val nav: NavigationController,
    private val launchManager: LaunchManager,
    private val launcherActions: LauncherActions,
    private val setupCoordinator: SetupCoordinator,
    private val browseSession: dev.piannoli.scorza.navigation.BrowseSession,
) {

    suspend fun run(onPhase: (BootPhase, Float, String) -> Unit): BootResult {
        val root = piannoliPaths.root
        val romDir = piannoliPaths.romDir
        val importPhase = if (hasLegacyData(root)) BootPhase.IMPORT else BootPhase.INITIAL_SCAN
        onPhase(BootPhase.LIBRARY_REFRESH, 0f, context.getString(R.string.boot_preparing))

        ScanLog.init(root.absolutePath)
        dev.piannoli.scorza.util.InputLog.init(root.absolutePath)
        StorageLog.init(root.absolutePath)
        dev.piannoli.scorza.util.RommLog.init(root.absolutePath)
        dev.piannoli.scorza.util.ErrorLog.init(root.absolutePath)
        setupCoordinator.logStorageDiagnostics()
        platformConfig.load()
        val bookmark = browseSession.pending()
        val scanDisk = settings.scanLibraryAutomatically
        // Backfill only. Fills platforms that have no stored choice and never touches one the
        // user made, so it is safe to run on every boot rather than only on first run.
        platformConfig.seedUnsetPlatforms(context.packageManager)
        ioScope.launch {
            launchManager.syncRetroArchAssets(root)
            launchManager.syncRetroArchConfig(root)
        }
        ioScope.launch { dev.piannoli.scorza.util.DirectoryLayout.ensure(root, romDir, context.assets, platformConfig) }

        val importer = Importer(
            context = context,
            piannoliRoot = root,
            romDirectory = romDir,
            db = piannoliDatabase,
            platformConfig = platformConfig,
            scanScheduler = scanScheduler,
            onProgress = ImportProgress { progress, label ->
                onPhase(importPhase, progress, label)
            },
            scanDisk = scanDisk,
        )

        val result = withContext(Dispatchers.IO) { importer.run() }

        if (result is ImportResult.Failure) {
            ScanLog.write("ERROR import returned Failure: ${result.cause.message}")
            return BootResult.Failure(result.cause.message ?: context.getString(R.string.boot_import_failed))
        }

        ioScope.launch {
            installedCoreService.queryAllPackages()
            platformConfig.seedUnsetPlatforms(context.packageManager, installedCoreService.configuredCores())
        }

        withContext(Dispatchers.Main) {
            gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
            settingsViewModel.reinitialize(root, context.packageManager, context.packageName, collectionsRepository)

            ioScope.launch { updateManager.purgeStaleDownloads() }

            if (updateManager.shouldAutoCheck()) {
                ioScope.launch { updateManager.checkForUpdate() }
            }
            // updateAvailable -> settingsViewModel.updateInfo is now pushed lifecycle-bound
            // from MainActivity.ReadyNavGraph so it cancels on Activity destroy.

            nav.screenStack.clear()
            nav.screenStack.add(LauncherScreen.SystemList)
        }

        return suspendCancellableCoroutine { cont ->
            launcherActions.rescanSystemList(
                scanDisk = scanDisk,
                onProgress = { tag, current, total ->
                    onPhase(BootPhase.LIBRARY_REFRESH, current.toFloat() / total.coerceAtLeast(1), tag)
                },
                onComplete = {
                    if (bookmark != null) {
                        browseSession.restore(bookmark) { if (cont.isActive) cont.resume(BootResult.Success) }
                    } else if (cont.isActive) cont.resume(BootResult.Success)
                },
            )
        }
    }

    private fun hasLegacyData(root: File): Boolean {
        val p = PiannoliPaths(root)
        return p.collectionsDir.exists() ||
            p.coresJson.exists() ||
            p.raGameIdsFile.exists() ||
            p.raGameIdsLegacyFile.exists() ||
            p.recentlyPlayedFile.exists() ||
            p.configOrdering.exists() ||
            p.toolsDir.exists() ||
            p.portsDir.exists() ||
            p.platformCacheFile.exists() ||
            p.gameCacheFile.exists()
    }
}
