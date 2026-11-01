package dev.piannoli.scorza.input.screen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.AppsRepository
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.di.IoScope
import dev.piannoli.scorza.input.ActivityActions
import dev.piannoli.scorza.input.InputTesterController
import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.input.PageJump
import dev.piannoli.scorza.input.ScreenInputHandler
import dev.piannoli.scorza.launcher.ApkLauncher
import dev.piannoli.scorza.launcher.InstalledCoreService
import dev.piannoli.scorza.launcher.IntentAuditor
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.navigation.BrowsePurpose
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.GlobalOverridesManager
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.setup.SetupCoordinator
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.piannoli.ui.components.KeyboardState
import dev.piannoli.scorza.updater.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class SettingsInputHandler @Inject constructor(
    private val nav: NavigationController,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val installedCoreService: InstalledCoreService,
    private val globalOverrides: GlobalOverridesManager,
    private val appsRepository: AppsRepository,
    private val setupCoordinator: SetupCoordinator,
    private val inputTesterController: InputTesterController,
    private val updateManager: UpdateManager,
    private val intentAuditor: IntentAuditor,
    private val settingsViewModel: SettingsViewModel,
    private val gameListViewModel: GameListViewModel,
    private val launcherActions: LauncherActions,
    private val activityActions: ActivityActions,
    private val emulatorMappingBuilder: dev.piannoli.scorza.input.EmulatorMappingBuilder,
    @ApplicationContext private val context: Context,
    private val rommStore: dev.piannoli.scorza.romm.RommConnectionStore,
    private val piannoliPaths: PiannoliPathsProvider,
    private val scraper: dev.piannoli.scorza.scraper.ScraperController,
    private val artworkStore: dev.piannoli.scorza.scraper.ScrapedArtworkStore,
) : ScreenInputHandler {

    override fun onUp() {
        settingsViewModel.moveSelection(-1)
    }

    override fun onDown() {
        settingsViewModel.moveSelection(1)
    }

    override fun onLeft() {
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.cycleSelected(-1, repeatCount = nav.lastKeyRepeatCount)
            if (settingsViewModel.getSelectedItem()?.key == "release_channel") {
                ioScope.launch { updateManager.checkForUpdate() }
            }
        } else {
            pageJump(-1)
        }
    }

    override fun onRight() {
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.cycleSelected(1, repeatCount = nav.lastKeyRepeatCount)
            if (settingsViewModel.getSelectedItem()?.key == "release_channel") {
                ioScope.launch { updateManager.checkForUpdate() }
            }
        } else {
            pageJump(1)
        }
    }

    override fun onConfirm() {
        if (nav.navigating) return
        if (!settingsViewModel.state.value.inSubList) {
            val cat = settingsViewModel.state.value.categories.getOrNull(settingsViewModel.state.value.categoryIndex)
            when {
                cat?.key == "about" -> nav.dialogState.value = DialogState.About()
                else -> settingsViewModel.enterCategory()
            }
            return
        }

        when (val key = settingsViewModel.enterSelected()) {
            "remap:CONFIRM", "remap:BACK", "remap:NORTH", "remap:WEST", "remap:START", "remap:SELECT" ->
                settingsViewModel.startRemapping()
            "remap_piannoli" -> settingsViewModel.enterSubCategory("remap_piannoli", dev.piannoli.scorza.R.string.remap_piannoli)
            "remap_reset" -> settingsViewModel.cycleSelected(1)
            "artwork" -> settingsViewModel.enterSubCategory("artwork", dev.piannoli.scorza.R.string.settings_artwork)
            "game_artwork" -> settingsViewModel.enterSubCategory("game_artwork", dev.piannoli.scorza.R.string.settings_game_artwork)
            "system_artwork" -> settingsViewModel.enterSubCategory("system_artwork", dev.piannoli.scorza.R.string.setting_system_artwork)
            "setup_scrapers" -> settingsViewModel.enterSubCategory("setup_scrapers", dev.piannoli.scorza.R.string.settings_setup_scrapers)
            "steamgrid_setup" -> scraper.openSteamGridSetup()
            "integrations_ra" -> {
                if (settings.raToken.isNotEmpty()) nav.dialogState.value = DialogState.RAAccount(username = settings.raUsername)
                else settingsViewModel.enterSubCategory("retroachievements", dev.piannoli.scorza.R.string.settings_retroachievements)
            }
            "integrations_romm" -> {
                if (rommStore.token.isNullOrEmpty()) settingsViewModel.enterSubCategory("romm", dev.piannoli.scorza.R.string.settings_romm)
                else nav.dialogState.value = DialogState.RommConnected(
                    host = rommStore.host, username = rommStore.username, version = rommStore.serverVersion)
            }
            "status_bar" -> settingsViewModel.enterSubCategory("status_bar", dev.piannoli.scorza.R.string.settings_status_bar)
            "fgh_collection" -> settingsViewModel.enterSubCategory(
                "fgh_collection_picker",
                dev.piannoli.scorza.R.string.setting_fgh_collection,
                settingsViewModel.fghPickerInitialIndex()
            )
            "sd_root" -> pushDirectoryBrowser(BrowsePurpose.SD_ROOT, settings.sdCardRoot)
            "rom_directory" -> {
                val startPath = settings.romDirectory.ifEmpty { settings.sdCardRoot }
                pushDirectoryBrowser(BrowsePurpose.ROM_DIRECTORY, startPath)
            }
            "colors" -> nav.push(LauncherScreen.ColorList(colors = settingsViewModel.getColorEntries()))
            "controllers" -> nav.push(LauncherScreen.Controllers())
            "screen_geometry" -> settingsViewModel.enterSubCategory("screen_geometry", dev.piannoli.scorza.R.string.setting_screen_geometry)
            "logging" -> nav.push(LauncherScreen.LoggingSettings())
            "audit_emulator_intents" -> runIntentAudit()
            "icon_gallery" -> nav.push(LauncherScreen.IconGallery())
            "shortcuts" -> nav.push(LauncherScreen.ShortcutBinding(shortcuts = globalOverrides.readShortcuts()))
            "input_tester" -> {
                inputTesterController.enter()
                nav.push(LauncherScreen.InputTester)
            }
            "core_mapping" -> openEmulatorMapping()
            "set_default_launcher" -> context.startActivity(
                Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "installed_cores" -> queryInstalledCores()
            "manage_tools" -> openAppPicker("tools")
            "manage_ports" -> openAppPicker("ports")
            "manage_hidden_games" -> {
                nav.navigating = true
                gameListViewModel.loadHiddenGames {
                    nav.push(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            "regenerate_system_folders" -> {
                val romDir = piannoliPaths.romDir
                val tags = platformConfig.getAllTags()
                ioScope.launch {
                    val created = dev.piannoli.scorza.util.DirectoryLayout.scaffoldRomFolders(romDir, tags)
                    val msg = if (created == 0) {
                        context.getString(dev.piannoli.scorza.R.string.regenerate_system_folders_none)
                    } else {
                        context.resources.getQuantityString(
                            dev.piannoli.scorza.R.plurals.regenerate_system_folders_result, created, created
                        )
                    }
                    withContext(Dispatchers.Main) {
                        nav.dialogState.value = DialogState.SystemFoldersRegenerated(msg)
                    }
                }
            }
            "ra_username" -> {
                val current = settings.raUsername
                nav.dialogState.value = DialogState.RenameInput(
                    gameName = "ra_username",
                    keyboard = KeyboardState(text = current, cursorPos = current.length)
                )
            }
            "ra_password" -> {
                nav.dialogState.value = DialogState.RenameInput(
                    gameName = "ra_password",
                    keyboard = KeyboardState(text = settingsViewModel.raPassword, cursorPos = settingsViewModel.raPassword.length)
                )
            }
            "ra_login" -> activityActions.startRaLogin(settings.raUsername, settingsViewModel.raPassword)
            "romm_host" -> {
                val current = rommStore.host
                nav.dialogState.value = DialogState.RenameInput(gameName = "romm_host", keyboard = KeyboardState(text = current, cursorPos = current.length))
            }
            "romm_pair" -> activityActions.startRommPairing(rommStore.host)
            "romm_pair_code" -> {
                nav.dialogState.value = DialogState.RenameInput(gameName = "romm_pair_code", keyboard = KeyboardState())
            }
            null -> {}
            else -> {
                when {
                    key.startsWith("fgh_pick:") -> {
                        val id = key.removePrefix("fgh_pick:").toLongOrNull()
                        settingsViewModel.selectFghCollectionId(id)
                        settingsViewModel.save()
                        settingsViewModel.exitSubList()
                        launcherActions.rescanSystemList()
                    }
                    key.startsWith("color_") -> {
                        val entries = settingsViewModel.getColorEntries()
                        val idx = entries.indexOfFirst { it.key == key }.coerceAtLeast(0)
                        nav.push(LauncherScreen.ColorList(colors = entries, selectedIndex = idx))
                        launcherActions.openColorPicker(key)
                    }
                    else -> {
                        val displayValue = settingsViewModel.getSelectedItemDisplayValue()
                        nav.dialogState.value = DialogState.RenameInput(
                            gameName = key,
                            keyboard = KeyboardState(text = displayValue, cursorPos = displayValue.length)
                        )
                    }
                }
            }
        }
    }

    override fun onBack() {
        if (nav.navigating) return
        if (settingsViewModel.state.value.remappingAction != null) {
            settingsViewModel.cancelRemapping()
            return
        }
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.save()
            settingsViewModel.exitSubList()
            launcherActions.rescanSystemList()
        } else {
            settingsViewModel.cancel()
            nav.pop()
        }
    }

    override fun onNorth() {
        val item = settingsViewModel.getSelectedItem()
        if (item?.key == "rom_directory" && settings.romDirectory.isNotEmpty()) {
            launcherActions.confirmRomDirectoryChange("")
            return
        }

        if (settingsViewModel.state.value.activeCategory == "screen_geometry") {
            settingsViewModel.resetScreenGeometry()
        }
    }

    override fun onCaptureButton(
        button: dev.piannoli.scorza.input.CanonicalButton,
        mapping: dev.piannoli.scorza.input.DeviceMapping,
    ): Boolean {
        if (settingsViewModel.state.value.remappingAction == null) return false
        val physical = dev.piannoli.scorza.input.PiannoliRemapping.remappableButton(button, mapping)
        if (physical != null) settingsViewModel.assignRemapping(physical)
        else if (button in listOf(
                dev.piannoli.scorza.input.CanonicalButton.BTN_UP,
                dev.piannoli.scorza.input.CanonicalButton.BTN_DOWN,
                dev.piannoli.scorza.input.CanonicalButton.BTN_LEFT,
                dev.piannoli.scorza.input.CanonicalButton.BTN_RIGHT,
                dev.piannoli.scorza.input.CanonicalButton.BTN_MENU,
            )) settingsViewModel.cancelRemapping()
        return true
    }

    private fun pageJump(direction: Int) {
        val state = settingsViewModel.state.value
        val newIdx = PageJump.compute(direction, state.categories.size, state.categoryIndex, nav.activeListState)
        if (newIdx != state.categoryIndex) settingsViewModel.setCategoryIndex(newIdx)
    }

    private fun pushDirectoryBrowser(purpose: BrowsePurpose, startPath: String) {
        val entries = setupCoordinator.listDirectories(startPath)
        nav.push(LauncherScreen.DirectoryBrowser(
            purpose = purpose,
            currentPath = startPath,
            entries = entries
        ))
    }

    private fun openEmulatorMapping() {
        val initial = emulatorMappingBuilder.detailedMappings()
        nav.push(LauncherScreen.EmulatorMapping(mappings = initial, allMappings = initial))
        ioScope.launch {
            installedCoreService.queryAllPackages()
            withContext(Dispatchers.Main) {
                val cm = nav.screenStack.lastOrNull() as? LauncherScreen.EmulatorMapping ?: return@withContext
                val all = emulatorMappingBuilder.detailedMappings()
                nav.screenStack[nav.screenStack.lastIndex] = cm.copy(
                    mappings = emulatorMappingBuilder.filter(all, cm.filter),
                    allMappings = all
                )
            }
        }
    }

    private fun runIntentAudit() {
        ioScope.launch {
            val message = try {
                val result = intentAuditor.runAudit()
                "Audited ${result.totalInstalled} installed emulators; ${result.totalFailed} intents failed to resolve.\n\nReport: ${result.reportFile.absolutePath}"
            } catch (e: Exception) {
                "Audit failed: ${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) {
                nav.dialogState.value = DialogState.IntentAuditResult(message)
            }
        }
    }

    private fun queryInstalledCores() {
        val selectedPkg = settings.retroArchPackage
        val pkgLabel = InstalledCoreService.getPackageLabel(selectedPkg)
        nav.push(LauncherScreen.InstalledCores(title = context.getString(dev.piannoli.scorza.R.string.title_installed_cores, pkgLabel)))
        ioScope.launch {
            installedCoreService.queryAllPackages()
            val cores = (installedCoreService.installedCores[selectedPkg] ?: emptySet())
                .map { coreId -> platformConfig.getCoreDisplayName(coreId) }
                .sorted()
            withContext(Dispatchers.Main) {
                val screen = nav.screenStack.lastOrNull() as? LauncherScreen.InstalledCores ?: return@withContext
                nav.screenStack[nav.screenStack.lastIndex] = screen.copy(cores = cores, loading = false)
            }
        }
    }

    private fun openAppPicker(type: String) {
        val installed = getInstalledLauncherApps()
        val allApps = buildList {
            if (type == "tools" && context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                add("Android TV Settings" to ApkLauncher.VIRTUAL_TV_SETTINGS_PACKAGE)
            }
            addAll(installed)
        }
        val appType = if (type == "tools") AppType.TOOL else AppType.PORT
        val existing = appsRepository.all(appType).map { it.packageName }.toSet()
        val initialChecked = allApps.indices.filter { allApps[it].second in existing }.toSet()
        val title = if (type == "tools") "Manage Tools" else "Manage Ports"
        nav.push(LauncherScreen.AppPicker(
            type = type,
            title = title,
            apps = allApps.map { it.first },
            packages = allApps.map { it.second },
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    private fun getInstalledLauncherApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = context.packageManager.queryIntentActivities(intent, 0)
        return resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                val label = ri.loadLabel(context.packageManager).toString()
                label to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(java.util.Locale.ROOT) }
    }

    fun confirmAppPicker(state: LauncherScreen.AppPicker) {
        val selected = state.checkedIndices.mapNotNull { idx ->
            val name = state.apps.getOrNull(idx) ?: return@mapNotNull null
            val pkg = state.packages.getOrNull(idx) ?: return@mapNotNull null
            name to pkg
        }
        val appType = if (state.type == "tools") AppType.TOOL else AppType.PORT
        ioScope.launch {
            val keep = selected.map { it.second }.toSet()
            val existing = appsRepository.all(appType)
            val existingPackages = existing.map { it.packageName }.toSet()
            existing.forEach { app ->
                if (app.packageName !in keep) appsRepository.delete(app.id)
            }
            val iconFailures = mutableListOf<String>()
            selected.forEach { (name, pkg) ->
                val id = appsRepository.upsert(appType, name, pkg)
                if (pkg !in existingPackages) {
                    try {
                        val icon = if (pkg == ApkLauncher.VIRTUAL_TV_SETTINGS_PACKAGE) {
                            val info = context.packageManager.resolveActivity(
                                Intent(android.provider.Settings.ACTION_SETTINGS), 0)
                                ?: throw PackageManager.NameNotFoundException(pkg)
                            info.loadIcon(context.packageManager)
                        } else context.packageManager.getApplicationIcon(pkg)
                        artworkStore.installAppIcon(dev.piannoli.scorza.model.App(id, appType, name, pkg), icon)
                    } catch (e: PackageManager.NameNotFoundException) {
                        android.util.Log.w("Artwork", "Cannot load app icon for $pkg", e)
                        iconFailures.add(name)
                    } catch (e: SecurityException) {
                        android.util.Log.w("Artwork", "Cannot access app icon for $pkg", e)
                        iconFailures.add(name)
                    } catch (e: dev.piannoli.scorza.scraper.ScraperException) {
                        if (e.error != dev.piannoli.scorza.scraper.ScraperError.EXISTING_ARTWORK) {
                            android.util.Log.w("Artwork", "Cannot save app icon for $pkg", e)
                            iconFailures.add(name)
                        }
                    }
                }
            }
            launcherActions.rescanSystemList()
            if (iconFailures.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    nav.dialogState.value = DialogState.LaunchError(context.getString(
                        dev.piannoli.scorza.R.string.app_icon_artwork_failed, iconFailures.joinToString(", ")))
                }
            }
        }
        nav.pop()
    }
}
