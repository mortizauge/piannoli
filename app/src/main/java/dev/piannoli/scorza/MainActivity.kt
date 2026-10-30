package dev.piannoli.scorza

import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.AndroidEntryPoint
import dev.piannoli.scorza.boot.BootSequencer
import dev.piannoli.scorza.boot.BootState
import dev.piannoli.scorza.boot.StartStorageDependentHolder
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.PiannoliDatabase
import dev.piannoli.scorza.db.CollectionsRepository
import dev.piannoli.scorza.db.RomScanner
import dev.piannoli.scorza.db.RomsRepository
import dev.piannoli.scorza.di.AppFonts
import dev.piannoli.scorza.input.ActivityActions
import dev.piannoli.scorza.input.BindingController
import dev.piannoli.scorza.input.AndroidGamepadKeyNames
import dev.piannoli.scorza.input.runtime.InputDispatcher
import dev.piannoli.scorza.input.InputRouter
import dev.piannoli.scorza.input.InputTesterController
import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.input.runtime.ControllerBridge
import dev.piannoli.scorza.launcher.InstalledCoreService
import dev.piannoli.scorza.launcher.LaunchManager
import dev.piannoli.scorza.libretro.LibretroActivity
import dev.piannoli.scorza.libretro.RetroAchievementsManager
import dev.piannoli.scorza.navigation.AppNavGraph
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.setup.SetupCoordinator
import dev.piannoli.scorza.ui.LocalViewportInsets
import dev.piannoli.scorza.ui.ViewportInsetsPx
import dev.piannoli.scorza.ui.screens.BootErrorScreen
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.screens.KeyboardHost
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.piannoli.scorza.romm.sync.RomKeys
import dev.piannoli.scorza.updater.UpdateManager
import dev.piannoli.ui.theme.PiannoliTheme
import dev.piannoli.ui.components.collectAsStateWhileActive
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class MainActivity : ComponentActivity(), ActivityActions {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var platformConfig: Provider<PlatformConfig>
    @Inject lateinit var nav: NavigationController
    @Inject lateinit var router: InputRouter
    @Inject lateinit var onboardingHandler: dev.piannoli.scorza.input.screen.OnboardingInputHandler
    @Inject lateinit var inputDispatcher: InputDispatcher
    @Inject lateinit var menuSounds: dev.piannoli.scorza.util.MenuSounds
    @Inject lateinit var screenInputRegistry: dev.piannoli.scorza.input.runtime.ScreenInputRegistry
    @Inject lateinit var menuNavigationPoller: dev.piannoli.scorza.input.runtime.MenuNavigationPoller
    @Inject lateinit var stickAutoRepeat: dev.piannoli.scorza.input.runtime.StickAutoRepeat
    @Inject lateinit var controllerBridge: ControllerBridge
    @Inject lateinit var portRouter: dev.piannoli.scorza.input.runtime.PortRouter
    @Inject lateinit var activeMappingHolder: dev.piannoli.scorza.input.runtime.ActiveMappingHolder
    @Inject lateinit var bindingController: BindingController
    @Inject lateinit var osdController: dev.piannoli.ui.components.OsdController
    @Inject lateinit var inputTesterController: InputTesterController
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var setupCoordinator: SetupCoordinator
    @Inject lateinit var launchManager: Provider<LaunchManager>
    @Inject lateinit var launchState: dev.piannoli.scorza.launcher.LaunchState
    @Inject lateinit var browseSession: dev.piannoli.scorza.navigation.BrowseSession
    @Inject lateinit var romDirectoryWatcher: Provider<dev.piannoli.scorza.scanner.RomDirectoryWatcher>
    @Inject lateinit var installedCoreService: Provider<InstalledCoreService>
    @Inject lateinit var romsRepository: Provider<RomsRepository>
    @Inject lateinit var appsRepository: Provider<dev.piannoli.scorza.db.AppsRepository>
    @Inject lateinit var romScanner: Provider<RomScanner>
    @Inject lateinit var collectionsRepository: Provider<CollectionsRepository>
    @Inject lateinit var piannoliDatabase: Provider<PiannoliDatabase>
    @Inject lateinit var launcherActions: Provider<LauncherActions>
    @Inject lateinit var systemListViewModel: Provider<SystemListViewModel>
    @Inject lateinit var gameListViewModel: Provider<GameListViewModel>
    @Inject lateinit var settingsViewModel: Provider<SettingsViewModel>
    @Inject lateinit var inputTesterViewModel: Provider<InputTesterViewModel>
    @Inject lateinit var controllersViewModel: Provider<dev.piannoli.scorza.ui.viewmodel.ControllersViewModel>
    @Inject lateinit var editButtonsController: dev.piannoli.scorza.input.EditButtonsController
    @Inject lateinit var mappingRepository: Provider<dev.piannoli.scorza.input.repo.MappingRepository>
    @Inject lateinit var bootSequencer: BootSequencer
    @Inject lateinit var startStorageDependentHolder: StartStorageDependentHolder
    @Inject lateinit var appFonts: AppFonts
    @Inject lateinit var controllerBlacklist: dev.piannoli.scorza.input.ControllerBlacklist
    @Inject lateinit var rommStore: dev.piannoli.scorza.romm.RommConnectionStore
    @Inject lateinit var rommClient: dev.piannoli.scorza.romm.RommClient
    @Inject lateinit var rommDevicePairing: dev.piannoli.scorza.romm.RommDevicePairing
    @Inject lateinit var rommBrowseViewModel: dev.piannoli.scorza.ui.viewmodel.RommBrowseViewModel
    @Inject lateinit var rommImageLoader: coil.ImageLoader
    @Inject lateinit var rommDownloader: dev.piannoli.scorza.romm.download.RommDownloader
    @Inject lateinit var rommArtFetcher: dev.piannoli.scorza.romm.art.RommArtFetcher
    @Inject lateinit var syncScheduler: dev.piannoli.scorza.romm.sync.SyncScheduler
    @Inject lateinit var saveSyncStatusHolder: dev.piannoli.scorza.romm.sync.SaveSyncStatusHolder
    @Inject lateinit var piannoliPathsProvider: dev.piannoli.scorza.di.PiannoliPathsProvider
    @Inject lateinit var artworkStore: Provider<dev.piannoli.scorza.scraper.ScrapedArtworkStore>
    @field:dev.piannoli.scorza.di.IoScope @Inject lateinit var ioScope: kotlinx.coroutines.CoroutineScope

    private val isTv: Boolean by lazy { dev.piannoli.scorza.util.DeviceType.isTv(this) }

    private val isReady: Boolean get() = bootSequencer.state.value is BootState.Ready

    private var coreQueryReceiver: android.content.BroadcastReceiver? = null
    private var loginManager: RetroAchievementsManager? = null
    private var pairingUiJob: kotlinx.coroutines.Job? = null
    private val loginPollHandler = Handler(Looper.getMainLooper())
    private val loginPollRunnable: Runnable = object : Runnable {
        override fun run() {
            loginManager?.idle()
            if (loginManager != null) loginPollHandler.postDelayed(this, 100)
        }
    }
    private var coldStart = true
    private var launcherResumed = false
    private val launcherUiActive = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun updateLauncherActivity() {
        val active = launcherResumed && hasWindowFocus() && !LibretroActivity.isRunning
        launcherUiActive.value = active
        if (active) menuNavigationPoller.start() else {
            menuNavigationPoller.stop()
            stickAutoRepeat.stop()
        }
        if (isReady) romDirectoryWatcher.get().setLauncherActive(active)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        updateLauncherActivity()
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        bootSequencer.onStoragePermissionResult()
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        bootSequencer.onStoragePermissionResult()
    }

    private sealed interface ArtworkPickerTarget {
        data class System(val tag: String, val type: dev.piannoli.scorza.settings.SystemArtworkType) : ArtworkPickerTarget
        data class Game(val path: String) : ArtworkPickerTarget
        data class Port(val packageName: String) : ArtworkPickerTarget
        data class Folder(val platformTag: String, val relativePath: String) : ArtworkPickerTarget
    }

    private var artworkPickerTarget: ArtworkPickerTarget? = null
    private val artworkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = artworkPickerTarget
        artworkPickerTarget = null
        if (uri != null && target != null) {
            val loading = DialogState.ArtworkImporting
            nav.dialogState.value = loading
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val input = contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("Cannot open selected artwork")
                        input.use {
                            when (target) {
                                is ArtworkPickerTarget.System -> artworkStore.get().importSystem(target.tag, target.type, it)
                                is ArtworkPickerTarget.Game -> {
                                    val rom = romsRepository.get().gameByPath(target.path)
                                        ?: throw java.io.IOException("The game is no longer in the library")
                                    artworkStore.get().importGame(rom, it)
                                }
                                is ArtworkPickerTarget.Port -> {
                                    val app = appsRepository.get().byPackage(
                                        dev.piannoli.scorza.model.AppType.PORT, target.packageName,
                                    ) ?: throw java.io.IOException("The port is no longer in the library")
                                    artworkStore.get().importPortArtwork(app, it)
                                }
                                is ArtworkPickerTarget.Folder ->
                                    artworkStore.get().importFolderArtwork(target.platformTag, target.relativePath, it)
                            }
                        }
                    }
                    if (nav.dialogState.value === loading) {
                        nav.dialogState.value = DialogState.None
                        if (target !is ArtworkPickerTarget.System) gameListViewModel.get().reload()
                    }
                } catch (e: java.io.IOException) {
                    artworkImportFailed(e, loading)
                } catch (e: SecurityException) {
                    artworkImportFailed(e, loading)
                } catch (e: dev.piannoli.scorza.scraper.ScraperException) {
                    artworkImportFailed(e, loading)
                }
            }
        }
    }

    private fun artworkImportFailed(error: Exception, loading: DialogState) {
        android.util.Log.w("Artwork", "Cannot import selected artwork", error)
        if (nav.dialogState.value === loading) {
            nav.dialogState.value = DialogState.LaunchError(getString(R.string.artwork_import_failed))
        }
    }

    override fun chooseSystemArtwork(tag: String, type: dev.piannoli.scorza.settings.SystemArtworkType) {
        chooseArtwork(ArtworkPickerTarget.System(tag, type))
    }

    override fun chooseGameArtwork(path: String) {
        chooseArtwork(ArtworkPickerTarget.Game(path))
    }

    override fun choosePortArtwork(packageName: String) {
        chooseArtwork(ArtworkPickerTarget.Port(packageName))
    }

    override fun chooseFolderArtwork(platformTag: String, relativePath: String) {
        chooseArtwork(ArtworkPickerTarget.Folder(platformTag, relativePath))
    }

    private fun chooseArtwork(target: ArtworkPickerTarget) {
        if (artworkPickerTarget != null) return
        artworkPickerTarget = target
        try {
            artworkPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/gif"))
        } catch (e: android.content.ActivityNotFoundException) {
            artworkPickerTarget = null
            nav.dialogState.value = DialogState.LaunchError(getString(R.string.artwork_picker_unavailable))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        when (val target = artworkPickerTarget) {
            is ArtworkPickerTarget.System -> {
                outState.putString("artworkPickerKind", "system")
                outState.putString("artworkPickerTag", target.tag)
                outState.putString("artworkPickerType", target.type.name)
            }
            is ArtworkPickerTarget.Game -> {
                outState.putString("artworkPickerKind", "game")
                outState.putString("artworkPickerPath", target.path)
            }
            is ArtworkPickerTarget.Port -> {
                outState.putString("artworkPickerKind", "port")
                outState.putString("artworkPickerPackage", target.packageName)
            }
            is ArtworkPickerTarget.Folder -> {
                outState.putString("artworkPickerKind", "folder")
                outState.putString("artworkPickerTag", target.platformTag)
                outState.putString("artworkPickerPath", target.relativePath)
            }
            null -> {}
        }
        super.onSaveInstanceState(outState)
    }

    private fun loadLoggingPrefs() {
        dev.piannoli.scorza.util.LoggingPrefs.romScan = settings.loggingRomScan
        dev.piannoli.scorza.util.LoggingPrefs.input = settings.loggingInput
        dev.piannoli.scorza.util.LoggingPrefs.session = settings.loggingSession
        dev.piannoli.scorza.util.LoggingPrefs.storage = settings.loggingStorage
        dev.piannoli.scorza.util.LoggingPrefs.romm = settings.loggingRomm
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition {
            if (!::bootSequencer.isInitialized) return@setKeepOnScreenCondition true
            val state = bootSequencer.state.value
            when (state) {
                BootState.Resolving -> true
                is BootState.Initializing -> !settings.scanLibraryAutomatically
                else -> false
            }
        }
        super.onCreate(savedInstanceState)
        artworkPickerTarget = when (savedInstanceState?.getString("artworkPickerKind")) {
            "system" -> {
                val type = savedInstanceState.getString("artworkPickerType")
                    ?.let { runCatching { dev.piannoli.scorza.settings.SystemArtworkType.valueOf(it) }.getOrNull() }
                val tag = savedInstanceState.getString("artworkPickerTag")
                if (type != null && tag != null) ArtworkPickerTarget.System(tag, type) else null
            }
            "game" -> savedInstanceState.getString("artworkPickerPath")?.let(ArtworkPickerTarget::Game)
            "port" -> savedInstanceState.getString("artworkPickerPackage")?.let(ArtworkPickerTarget::Port)
            "folder" -> {
                val tag = savedInstanceState.getString("artworkPickerTag")
                val path = savedInstanceState.getString("artworkPickerPath")
                if (tag != null && path != null) ArtworkPickerTarget.Folder(tag, path) else null
            }
            else -> null
        }

        // Belt-and-suspenders: ensure the launcher window does not hold FLAG_KEEP_SCREEN_ON,
        // so the system display timeout applies. The IGM activity manages its own flag.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        setTaskDescription(
            ActivityManager.TaskDescription(getString(R.string.app_name), R.mipmap.ic_launcher)
        )

        hideSystemUI()
        editButtonsController.cancelListening()
        loadLoggingPrefs()

        startStorageDependentHolder.register { startStorageDependent() }
        onboardingHandler.onFolderChosen = { target -> bootSequencer.onFolderChosen(target) }
        onboardingHandler.onRequestPermission = { perm ->
            when (perm) {
                dev.piannoli.scorza.navigation.OnboardingPermission.STORAGE -> requestStoragePermission()
            }
        }
        router.unregisterCoreQueryReceiver = { unregisterCoreQueryReceiver() }

        controllerBlacklist.load(this)
        controllerBridge.start(this)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        bootSequencer.advance()

        setContent {
            val boot by bootSequencer.state.collectAsState()
            LaunchedEffect(boot) {
                if (boot is BootState.Ready) updateLauncherActivity()
            }
            val appSettings = if (boot is BootState.Ready) settingsViewModel.get().appSettings.collectAsState().value else null
            val themeFont = appSettings?.fontFamily ?: appFonts.mplus1Code
            dev.piannoli.scorza.i18n.ProvideLocalizedResources(appSettings?.languageTag) {
            PiannoliTheme(fontFamily = themeFont, iconFontFamily = appFonts.mplus1Code) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        dev.piannoli.ui.components.LocalUiActive provides launcherUiActive,
                        dev.piannoli.ui.components.LocalScreenBackground provides dev.piannoli.ui.components.ScreenBackgroundStyle(
                            appSettings?.backgroundImagePath ?: settings.backgroundImagePath,
                            appSettings?.backgroundTint ?: settings.backgroundTint,
                        ),
                        dev.piannoli.ui.theme.LocalPiannoliColors provides dev.piannoli.ui.theme.PiannoliColors(
                            highlight = dev.piannoli.ui.theme.hexToColor(settings.colorHighlight) ?: androidx.compose.ui.graphics.Color.White,
                            text = dev.piannoli.ui.theme.hexToColor(settings.colorText) ?: androidx.compose.ui.graphics.Color.White,
                            highlightText = dev.piannoli.ui.theme.hexToColor(settings.colorHighlightText) ?: androidx.compose.ui.graphics.Color.Black,
                            accent = dev.piannoli.ui.theme.hexToColor(settings.colorAccent) ?: androidx.compose.ui.graphics.Color.White,
                            title = dev.piannoli.ui.theme.hexToColor(settings.colorTitle) ?: androidx.compose.ui.graphics.Color.White,
                            background = dev.piannoli.ui.theme.hexToColor(settings.colorBackground) ?: androidx.compose.ui.graphics.Color.Black,
                            statusBar = dev.piannoli.ui.theme.hexToColor(settings.colorStatusBar) ?: androidx.compose.ui.graphics.Color.White,
                        ),
                        LocalViewportInsets provides ViewportInsetsPx(
                            geometryWidthPct = settings.screenGeometryWidth,
                            geometryHeightPct = settings.screenGeometryHeight,
                            geometryXPct = settings.screenGeometryX,
                            geometryYPct = settings.screenGeometryY,
                            portraitMarginPx = settings.portraitMarginPx,
                        ),
                        dev.piannoli.scorza.input.screen.compose.LocalScreenInputRegistry provides screenInputRegistry,
                    ) {
                    when (val s = boot) {
                        is BootState.Resolving -> Box(modifier = Modifier.fillMaxSize()) {}
                        is BootState.NeedsPermission, is BootState.NeedsSetup -> {
                            val storageGranted = (s as? BootState.NeedsPermission)?.storageGranted ?: true
                            LaunchedEffect(storageGranted) {
                                val perms = listOf(dev.piannoli.scorza.navigation.OnboardingPermission.STORAGE)
                                val granted = buildSet {
                                    if (storageGranted) add(dev.piannoli.scorza.navigation.OnboardingPermission.STORAGE)
                                }
                                val volumes = setupCoordinator.detectStorageVolumes() + ("Custom" to "")
                                val existingIndex = setupCoordinator.existingPiannoliVolumeIndex(volumes)
                                val preselectIndex = existingIndex ?: 0
                                val top = nav.currentScreen
                                if (top is LauncherScreen.OnboardingPermissions) {
                                    val nowAllGranted = granted.containsAll(perms)
                                    val newSelected = if (nowAllGranted && !top.allGranted) perms.size else top.selectedIndex
                                    nav.replaceTop(top.copy(
                                        permissions = perms,
                                        granted = granted,
                                        volumes = volumes,
                                        volumeIndex = preselectIndex,
                                        selectedIndex = newSelected,
                                        existingInstallVolumeIndex = existingIndex,
                                    ))
                                } else if (top !is LauncherScreen.DirectoryBrowser) {
                                    nav.screenStack.clear()
                                    nav.screenStack.add(LauncherScreen.OnboardingPermissions(
                                        permissions = perms,
                                        granted = granted,
                                        volumes = volumes,
                                        volumeIndex = preselectIndex,
                                        existingInstallVolumeIndex = existingIndex,
                                    ))
                                }
                            }
                            ReadyNavGraph()
                        }
                        is BootState.Initializing -> {
                            if (settings.scanLibraryAutomatically) {
                                val kind = when (s.phase) {
                                    dev.piannoli.scorza.boot.BootPhase.IMPORT ->
                                        dev.piannoli.scorza.ui.screens.HousekeepingKind.DATABASE_MIGRATION
                                    dev.piannoli.scorza.boot.BootPhase.INITIAL_SCAN ->
                                        dev.piannoli.scorza.ui.screens.HousekeepingKind.INITIAL_SCAN
                                    dev.piannoli.scorza.boot.BootPhase.LIBRARY_REFRESH ->
                                        dev.piannoli.scorza.ui.screens.HousekeepingKind.LIBRARY_REFRESH
                                }
                                Box(modifier = Modifier.fillMaxSize().padding(dev.piannoli.scorza.ui.effectiveViewportPadding())) {
                                    dev.piannoli.scorza.ui.screens.HousekeepingScreen(
                                        kind = kind,
                                        progress = s.progress,
                                        statusLabel = s.label,
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {}
                            }
                        }
                        is BootState.Error -> BootErrorScreen(message = s.message)
                        is BootState.Ready -> ReadyNavGraph()
                    }
                    }
                }
            }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ReadyNavGraph() {
        val svm = settingsViewModel.get()
        val slvm = systemListViewModel.get()
        val glvm = gameListViewModel.get()
        val itvm = inputTesterViewModel.get()
        val cvm = controllersViewModel.get()
        val updateInfo = updateManager.updateAvailable.collectAsState().value
        val dlProgress = updateManager.downloadProgress.collectAsStateWhileActive().value
        val dlError = updateManager.downloadError.collectAsState().value
        val navScreen = nav.currentScreen
        val navDialogState = nav.dialogState
        val navResumableGames = nav.resumableGames
        val activeMapping by activeMappingHolder.active.collectAsState()
        val syncStatus by saveSyncStatusHolder.state.collectAsState()
        LaunchedEffect(updateInfo) { svm.updateInfo = updateInfo }
        LaunchedEffect(navScreen) {
        }
        AppNavGraph(
            currentScreen = navScreen,
            systemListViewModel = slvm,
            gameListViewModel = glvm,
            inputTesterViewModel = itvm,
            onExitInputTester = {
                inputTesterController.exit()
                if (nav.screenStack.size > 1) nav.screenStack.removeAt(nav.screenStack.lastIndex)
            },
            settingsViewModel = svm,
            controllersViewModel = cvm,
            dialogState = navDialogState,
            onListStateChanged = { listState -> nav.activeListState = listState },
            resumableGames = navResumableGames,
            updateAvailable = updateInfo != null,
            downloadProgress = dlProgress ?: 0f,
            downloadError = dlError,
            osdController = osdController,
            activeMapping = activeMapping,
            mappingRepository = mappingRepository.get(),
            editButtonsController = editButtonsController,
            nav = nav,
            inputRouter = router,
            rommBrowseViewModel = rommBrowseViewModel,
            rommImageLoader = rommImageLoader,
            rommHost = rommStore.host,
            rommArtType = rommStore.artTypeFlow.collectAsState().value,
            rommDownloader = rommDownloader,
            rommArtFetcher = rommArtFetcher,
            saveSyncStatus = syncStatus,
            onKeyboardConfirm = { inputDispatcher.onStart() },
            onKeyboardCancel = { inputDispatcher.onWest() },
            onKeyboardHelp = { inputDispatcher.onMenu() },
            onKeyboardTick = { menuSounds.play(dev.piannoli.scorza.util.MenuSound.TICK) },
        )
    }

    /**
     * Re-run the storage-backed parts of input setup once MANAGE_EXTERNAL_STORAGE is available:
     * point the input log at the SD card and re-settle so controllers pick up saved profiles.
     * The controller bridge itself is started in onCreate (before permission) so the onboarding
     * wizard is operable. BootSequencer invokes this once, on the edge into Initializing.
     */
    private fun startStorageDependent() {
        settings.reload()
        if (settings.sdCardRoot.isNotEmpty()) {
            dev.piannoli.scorza.util.InputLog.init(settings.sdCardRoot)
        }
        controllerBridge.settleNow()
        refreshRommServerVersion()
    }

    private fun refreshRommServerVersion() {
        if (!rommStore.isConfigured) return
        lifecycleScope.launch {
            // The server can be upgraded behind our back; the version cached at pairing time would
            // otherwise gate save sync forever. Keep the cached value when the server is unreachable.
            val version = withContext(Dispatchers.IO) { rommClient.serverVersion() }
            if (version != null) rommStore.serverVersion = version
            val media = withContext(Dispatchers.IO) { rommClient.scanMedia() }
            if (media.isNotEmpty()) rommStore.scanMedia = media.toSet()
        }
    }

    private fun registerControllerOsd() {
        controllerBridge.onDeviceAdded = { device ->
            if (settings.controllerNotifications && !device.isBuiltIn) {
                val port = portRouter.portFor(device.androidDeviceId)
                if (port != null) {
                    val name = portRouter.mappingForPort(port)?.displayName?.takeIf { it.isNotEmpty() }
                        ?: device.name.ifEmpty { getString(R.string.device_controller) }
                    osdController.show(getString(R.string.osd_controller_connected, port + 1, name))
                }
            }
        }
        controllerBridge.onDeviceRemoved = { departed ->
            if (settings.controllerNotifications) {
                val msg = departed.port?.let {
                    getString(R.string.osd_controller_disconnected_port, it + 1, departed.displayName)
                } ?: getString(R.string.osd_controller_disconnected, departed.displayName)
                osdController.show(msg)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        launcherResumed = true
        // Re-wire the dispatcher to launcher dispatch shape on each resume. LibretroActivity
        // overwrites these callbacks with IGM-specific wiring when it runs; we restore the
        // launcher's wiring when we come back.
        router.wire(inputDispatcher)
        registerControllerOsd()
        updateLauncherActivity()
        bootSequencer.advance()
        launchState.launching = false
        val justExited = launchState.lastLaunched
        if (justExited != null && !LibretroActivity.isRunning) {
            launchState.lastLaunched = null
        }
        if (!LibretroActivity.isRunning) {
            syncScheduler.start()
            // The just-played save is uploaded by the sweep itself; force one so it runs now.
            if (justExited != null) syncScheduler.syncNow()
        }
        if (!isReady) return
        if (!LibretroActivity.isRunning) browseSession.clear()
        if (!coldStart) overridePendingTransition(0, 0)
        coldStart = false
        hideSystemUI()
        if (LibretroActivity.isRunning) {
            val intent = Intent(this, LibretroActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            startActivity(intent, opts)
            return
        }
        settings.reload()
        settingsViewModel.get().load()
        val activeDialogState = nav.dialogState
        if (activeDialogState.value is DialogState.RAAccount && settings.raToken.isEmpty()) {
            activeDialogState.value = DialogState.None
        }
        if (activeDialogState.value is DialogState.RommConnected && rommStore.token.isNullOrEmpty()) {
            activeDialogState.value = DialogState.None
        }
        launcherActions.get().refreshLauncherLists(scanDisk = false)
    }

    override fun onPause() {
        launcherResumed = false
        updateLauncherActivity()
        super.onPause()
        syncScheduler.stop()
        menuNavigationPoller.stop()
        // Cancel any in-flight stick auto-repeat so it does not keep firing dispatcher callbacks
        // after LibretroActivity has rewired them.
        stickAutoRepeat.stop()
        controllerBridge.onDeviceAdded = null
        controllerBridge.onDeviceRemoved = null
        if (isReady && nav.pendingRecentlyPlayedReorder) {
            nav.pendingRecentlyPlayedReorder = false
            gameListViewModel.get().moveSelectedToTop()
        }
        if (isReady && (launchState.launching || launchState.lastLaunched != null)) {
            browseSession.save()
        }
    }

    override fun onDestroy() {
        menuSounds.release()
        controllerBridge.onDeviceAdded = null
        controllerBridge.onDeviceRemoved = null
        controllerBridge.stop(this)
        super.onDestroy()
        unregisterCoreQueryReceiver()
        loginPollHandler.removeCallbacks(loginPollRunnable)
        loginManager?.destroy()
        loginManager = null
        settings.shutdown()
        if (isReady) {
            systemListViewModel.get().close()
            gameListViewModel.get().close()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val dialog = nav.dialogState.value
        return if (dialog is KeyboardHost || dialog is DialogState.KeyboardHelp) super.dispatchTouchEvent(event) else true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            dev.piannoli.scorza.util.InputLog.write(
                "[launcher dispatch] keyCode=${event.keyCode} source=0x${event.source.toString(16)}"
            )
        }
        if (!isReady) {
            if (event.action == KeyEvent.ACTION_DOWN
                && bootSequencer.state.value is BootState.Error
                && AndroidGamepadKeyNames.isGamepadEvent(event)) {
                bootSequencer.retry()
            }
            // While in NeedsSetup or NeedsPermission the launcher screen stack drives the wizard
            // via the normal input pipeline, so fall through; everything else is swallowed.
            val bootVal = bootSequencer.state.value
            if (bootVal !is BootState.NeedsSetup && bootVal !is BootState.NeedsPermission) return true
        }
        val cs = nav.currentScreen
        if (nav.dialogState.value is KeyboardHost &&
            settings.keyboardType == dev.piannoli.scorza.settings.KeyboardType.SYSTEM &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            !AndroidGamepadKeyNames.isGamepadEvent(event)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) inputDispatcher.onWest()
            return true
        }
        if (cs is LauncherScreen.EditButtons && editButtonsController.isListening
            && event.action == KeyEvent.ACTION_DOWN) {
            editButtonsController.captureRawKeyEvent(event.keyCode)
            return true
        }
        // The dev keyboard binds BACK to its own back button and needs it to reach the normal
        // pipeline, so the GPIO menu-button shim below is skipped for that device only. Scoped to
        // the event's own device rather than the feature flag: a GPIO menu button is not an
        // enrolled keyboard, so it keeps the shim even if the AVD gate misjudges the host.
        if (!AndroidGamepadKeyNames.isGamepadEvent(event) &&
            !controllerBridge.isDevKeyboardDevice(event.deviceId)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    val currentScreenForKey = nav.currentScreen
                    if (currentScreenForKey is LauncherScreen.InputTester) {
                        inputTesterController.dispatchKey(event, down = event.action == KeyEvent.ACTION_DOWN)
                    } else if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                        // KEYCODE_BACK is a default BTN_MENU binding, but handhelds that wire the menu
                        // button to GPIO deliver it keyboard-sourced from a device ControllerBridge never
                        // routes, so it has no PortRouter entry and can never resolve through the mapping.
                        // Dispatch it as BTN_MENU so launcher remapping still applies. TV keeps back-nav.
                        dev.piannoli.scorza.util.InputLog.write(
                            "back key: isTv=$isTv -> ${if (isTv) "onBack" else "onMenu"}"
                        )
                        if (isTv) {
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) inputDispatcher.onBack()
                        } else {
                            inputDispatcher.handleMenuKey(
                                pressed = event.action == KeyEvent.ACTION_DOWN,
                                isRepeat = event.repeatCount > 0,
                            )
                        }
                    }
                    return true
                }
            }
        }
        if (usesPiannoliKeyboard() && (
                AndroidGamepadKeyNames.isGamepadEvent(event) ||
                    KeyEvent.isGamepadButton(event.keyCode) ||
                    event.keyCode in listOf(
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                    )
                )) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun usesPiannoliKeyboard(): Boolean =
        nav.dialogState.value is KeyboardHost &&
            settings.keyboardType == dev.piannoli.scorza.settings.KeyboardType.PIANNOLI

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bootValOnKeyDown = bootSequencer.state.value
        if (!isReady && bootValOnKeyDown !is BootState.NeedsSetup && bootValOnKeyDown !is BootState.NeedsPermission) {
            if (bootValOnKeyDown is BootState.Error && AndroidGamepadKeyNames.isGamepadEvent(event)) {
                bootSequencer.retry()
            }
            return true
        }
        val currentScreenForKey = nav.currentScreen
        if (currentScreenForKey is LauncherScreen.InputTester) {
            inputTesterController.dispatchKey(event, down = true)
            return true
        }
        if (bindingController.keyDown(keyCode)) {
            return true
        }
        if (event.repeatCount > 0 && currentScreenForKey is LauncherScreen.ShortcutBinding && !currentScreenForKey.listening) {
            return true
        }
        nav.lastKeyRepeatCount = event.repeatCount
        if (isTv && !AndroidGamepadKeyNames.isGamepadEvent(event)) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> { inputDispatcher.onWest(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { inputDispatcher.onNorth(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { inputDispatcher.onStart(); return true }
            }
        }
        if (inputDispatcher.handleKeyEvent(event, keyboardNavigation = usesPiannoliKeyboard())) {
            return true
        }
        // Onboarding wizard fallback: route raw D-pad / button keycodes when no device has been
        // routed by the v2 bridge yet (e.g. TV remotes that aren't classified as gamepads, or
        // the brief pre-settle window). Scoped to OnboardingPermissions so other screens keep
        // using v2 routing as-is.
        if (currentScreenForKey is LauncherScreen.OnboardingPermissions) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { inputDispatcher.onUp(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { inputDispatcher.onDown(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { inputDispatcher.onLeft(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { inputDispatcher.onRight(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                    { inputDispatcher.onConfirm(); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B ->
                    { inputDispatcher.onBack(); return true }
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU ->
                    { inputDispatcher.onStart(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val bootValOnKeyUp = bootSequencer.state.value
        if (!isReady && bootValOnKeyUp !is BootState.NeedsSetup && bootValOnKeyUp !is BootState.NeedsPermission) return true
        val currentScreenForKey = nav.currentScreen
        if (currentScreenForKey is LauncherScreen.InputTester) {
            inputTesterController.dispatchKey(event, down = false)
            return true
        }
        if (inputDispatcher.handleKeyEvent(event, keyboardNavigation = usesPiannoliKeyboard())) {
            return true
        }
        if (bindingController.keyUp(keyCode)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val bootValOnMotion = bootSequencer.state.value
        if (!isReady && bootValOnMotion !is BootState.NeedsSetup && bootValOnMotion !is BootState.NeedsPermission) return super.onGenericMotionEvent(event)
        val currentScreenForMotion = nav.currentScreen
        if (currentScreenForMotion is LauncherScreen.InputTester) {
            inputTesterController.dispatchMotion(event)
            return true
        }
        val handled = inputDispatcher.handleMotionEvent(event)
        stickAutoRepeat.handleMotion(event, dispatcherHandled = handled)
        return handled || super.onGenericMotionEvent(event)
    }

    private val triggerL2HeldDevices = mutableSetOf<Int>()
    private val triggerR2HeldDevices = mutableSetOf<Int>()
    private val bindingHatSync = dev.piannoli.scorza.input.HatKeySync()
    private val keyboardHatSync = dev.piannoli.scorza.input.HatKeySync()

    private fun syncBindingTrigger(deviceId: Int, keyCode: Int, value: Float, held: MutableSet<Int>) {
        val wasHeld = deviceId in held
        if (value > 0.5f && !wasHeld) {
            held.add(deviceId)
            bindingController.keyDown(keyCode)
        } else if (value < 0.3f && wasHeld) {
            held.remove(deviceId)
            bindingController.keyUp(keyCode)
        }
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val csForListen = nav.currentScreen
        if (csForListen is LauncherScreen.EditButtons && editButtonsController.isListening) {
            val axes = listOf(0, 1, 11, 14, 15, 16, 17, 18, 22, 23)
            val axisValues = axes.associateWith { event.getAxisValue(it) }
            editButtonsController.captureRawAxisEvent(axisValues)
            return true
        }
        val source = event.source
        val isJoystick =
            source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
            source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
            (usesPiannoliKeyboard() &&
                source and android.view.InputDevice.SOURCE_DPAD == android.view.InputDevice.SOURCE_DPAD)
        if (!isJoystick) return super.dispatchGenericMotionEvent(event)

        if (usesPiannoliKeyboard()) {
            onGenericMotionEvent(event)
            keyboardHatSync.sync(
                event.deviceId,
                event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X),
                event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y),
                { inputDispatcher.handleKeyboardHat(event.deviceId, it, pressed = true) },
                { inputDispatcher.handleKeyboardHat(event.deviceId, it, pressed = false) },
            )
            return true
        }
        keyboardHatSync.releaseAll(event.deviceId) {
            inputDispatcher.handleKeyboardHat(event.deviceId, it, pressed = false)
        }

        val currentScreenForMotion = nav.currentScreen
        if (currentScreenForMotion is LauncherScreen.ShortcutBinding) {
            val lt = maxOf(
                event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE),
            )
            val rt = maxOf(
                event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_GAS),
            )
            syncBindingTrigger(event.deviceId, KeyEvent.KEYCODE_BUTTON_L2, lt, triggerL2HeldDevices)
            syncBindingTrigger(event.deviceId, KeyEvent.KEYCODE_BUTTON_R2, rt, triggerR2HeldDevices)
            bindingHatSync.sync(
                event.deviceId,
                event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X),
                event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y),
                { bindingController.keyDown(it) },
                { bindingController.keyUp(it) },
            )
        }

        if (currentScreenForMotion is LauncherScreen.InputTester) {
            inputTesterController.dispatchMotion(event)
            return true
        }

        return super.dispatchGenericMotionEvent(event)
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            storagePermissionLauncher.launch(intent)
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun unregisterCoreQueryReceiver() {
        coreQueryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
            coreQueryReceiver = null
        }
    }

    override fun finishAffinity() = super.finishAffinity()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(dev.piannoli.scorza.i18n.LocaleOverride.wrap(newBase))
    }

    override fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        startActivity(intent, opts)
        Runtime.getRuntime().exit(0)
    }

    override fun startRaLogin(username: String, password: String) {
        val ra = RetroAchievementsManager(
            context = this,
            onLogin = { success, nameOrError, token ->
                if (success && token != null) {
                    settings.raUsername = nameOrError
                    settings.raToken = token
                    settings.raPassword = password
                    settingsViewModel.get().raPassword = ""
                    nav.dialogState.value = DialogState.RAAccount(username = nameOrError)
                } else {
                    nav.dialogState.value = DialogState.RALoggingIn(message = getString(R.string.ra_login_invalid))
                }
                loginPollHandler.removeCallbacks(loginPollRunnable)
                loginManager?.destroy()
                loginManager = null
            }
        )
        ra.init()
        ra.loginWithPassword(username, password)
        loginManager = ra
        loginPollHandler.postDelayed(loginPollRunnable, 100)
        nav.dialogState.value = DialogState.RALoggingIn()
    }

    override fun startRommPairing(host: String) {
        rommDevicePairing.start(host)
        pairingUiJob?.cancel()
        pairingUiJob = lifecycleScope.launch {
            rommDevicePairing.state.collect { state ->
                when (state) {
                    is dev.piannoli.scorza.romm.PairingState.Idle -> {}
                    is dev.piannoli.scorza.romm.PairingState.Connecting ->
                        nav.dialogState.value = DialogState.RommPairing(host = host)
                    is dev.piannoli.scorza.romm.PairingState.WaitingApproval -> {
                        val qr = withContext(Dispatchers.Default) {
                            dev.piannoli.scorza.util.QrCode.generate(state.verificationUrl, 256)
                        }
                        nav.dialogState.value = DialogState.RommPairing(
                            host = rommStore.host,
                            waitingApproval = true,
                            qrBitmap = qr,
                        )
                    }
                    is dev.piannoli.scorza.romm.PairingState.Success -> {
                        val connected = completeRommConnection()
                        if (rommDevicePairing.state.value is dev.piannoli.scorza.romm.PairingState.Success) {
                            nav.dialogState.value = connected
                        }
                    }
                    is dev.piannoli.scorza.romm.PairingState.Failed ->
                        nav.dialogState.value = DialogState.RommPairing(
                            host = rommStore.host,
                            message = pairingFailureMessage(state.reason),
                        )
                }
            }
        }
    }

    override fun startRommCodePairing(host: String, pairCode: String) {
        if (!dev.piannoli.scorza.romm.RommPairingCode.isValid(pairCode)) {
            nav.dialogState.value = DialogState.RommPairing(host = host, message = getString(R.string.romm_pair_invalid))
            return
        }
        nav.dialogState.value = DialogState.RommPairing(host = host)
        lifecycleScope.launch {
            val base = withContext(Dispatchers.IO) { rommClient.resolveBaseUrl(host) }
            if (base == null) {
                nav.dialogState.value = DialogState.RommPairing(host = host, message = getString(R.string.romm_unreachable))
                return@launch
            }
            rommStore.host = base
            val version = withContext(Dispatchers.IO) { rommClient.serverVersion() }
            if (dev.piannoli.scorza.romm.RommCapabilities.isKnownUnsupported(version)) {
                nav.dialogState.value = DialogState.RommPairing(host = base, message = getString(R.string.romm_server_too_old))
                return@launch
            }
            val result = withContext(Dispatchers.IO) { runCatching { rommClient.exchangeCode(pairCode) } }
            result.onSuccess { token ->
                rommStore.token = token
                val connected = completeRommConnection()
                if (nav.dialogState.value is DialogState.RommPairing) {
                    nav.dialogState.value = connected
                }
            }.onFailure { e ->
                val msg = when ((e as? dev.piannoli.scorza.romm.RommException)?.statusCode) {
                    404 -> getString(R.string.romm_pair_invalid)
                    429 -> getString(R.string.romm_pair_rate_limited)
                    else -> getString(R.string.romm_pair_failed)
                }
                if (nav.dialogState.value is DialogState.RommPairing) {
                    nav.dialogState.value = DialogState.RommPairing(host = host, message = msg)
                }
            }
        }
    }

    private suspend fun completeRommConnection(): DialogState.RommConnected {
        settingsViewModel.get().exitSubList()
        val user = withContext(Dispatchers.IO) { rommClient.currentUser() }
        val version = withContext(Dispatchers.IO) { rommClient.serverVersion() }
        val media = withContext(Dispatchers.IO) { rommClient.scanMedia() }
        rommStore.username = user
        rommStore.serverVersion = version
        if (media.isNotEmpty()) rommStore.scanMedia = media.toSet()
        return DialogState.RommConnected(host = rommStore.host, username = user, version = version)
    }

    private fun pairingFailureMessage(reason: dev.piannoli.scorza.romm.PairingFailure): String = when (reason) {
        dev.piannoli.scorza.romm.PairingFailure.SERVER_TOO_OLD -> getString(R.string.romm_server_too_old)
        dev.piannoli.scorza.romm.PairingFailure.UNREACHABLE -> getString(R.string.romm_unreachable)
        dev.piannoli.scorza.romm.PairingFailure.DENIED -> getString(R.string.romm_pair_denied)
        dev.piannoli.scorza.romm.PairingFailure.EXPIRED -> getString(R.string.romm_pair_expired)
        dev.piannoli.scorza.romm.PairingFailure.RATE_LIMITED -> getString(R.string.romm_pair_rate_limited)
        dev.piannoli.scorza.romm.PairingFailure.FAILED -> getString(R.string.romm_pair_failed)
    }

}
