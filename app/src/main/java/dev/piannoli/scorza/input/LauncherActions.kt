package dev.piannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.AppsRepository
import dev.piannoli.scorza.db.CollectionsRepository
import dev.piannoli.scorza.db.LibraryRef
import dev.piannoli.scorza.db.RecentlyPlayedRepository
import dev.piannoli.scorza.db.RomsRepository
import dev.piannoli.scorza.di.IoScope
import dev.piannoli.scorza.launcher.LaunchManager
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.model.CollectionType
import dev.piannoli.scorza.model.ListItem
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.romm.sync.PreLaunchOutcome
import dev.piannoli.scorza.romm.sync.RomKeys
import dev.piannoli.scorza.romm.sync.SaveSyncService
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.ContentMode
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.piannoli.scorza.util.ArcadeTitleLookup
import dev.piannoli.scorza.util.ArtworkLookup
import dev.piannoli.ui.components.COLOR_GRID_COLS
import dev.piannoli.ui.theme.COLOR_PRESETS
import dev.piannoli.ui.theme.colorToArgbLong
import dev.piannoli.ui.theme.hexToColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class LauncherActions @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
    private val romsRepository: RomsRepository,
    private val appsRepository: AppsRepository,
    private val launchManager: LaunchManager,
    private val platformConfig: PlatformConfig,
    private val gameOverrideStore: dev.piannoli.scorza.db.GameOverrideStore,
    private val artworkLookup: ArtworkLookup,
    private val arcadeTitleLookup: ArcadeTitleLookup,
    private val nav: NavigationController,
    private val systemListViewModel: SystemListViewModel,
    private val gameListViewModel: GameListViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val saveSyncService: SaveSyncService,
    private val pathsProvider: PiannoliPathsProvider,
) {

    private var pendingLaunch: (() -> DialogState?)? = null
    private var pendingRecentPath: String? = null
    private var pendingRecentReorder: Boolean = false

    fun proceedPendingLaunch() {
        val p = pendingLaunch
        val recentPath = pendingRecentPath
        val reorder = pendingRecentReorder
        pendingLaunch = null
        pendingRecentPath = null
        pendingRecentReorder = false
        p?.invoke()?.let { nav.dialogState.value = it }
        if (recentPath != null) {
            recordRecentlyPlayedByPath(recentPath)
            if (reorder) nav.pendingRecentlyPlayedReorder = true
        }
    }

    fun cancelPendingLaunch() {
        pendingLaunch = null
        pendingRecentPath = null
        pendingRecentReorder = false
    }

    fun recordPendingRecent(path: String, reorder: Boolean) {
        pendingRecentPath = path
        pendingRecentReorder = reorder
    }

    fun rescanSystemList(
        scanDisk: Boolean = true,
        reconcileOrphans: Boolean = false,
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        val fghId = validateFghCollection()
        gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
        systemListViewModel.scan(
            showRecentlyPlayed = settings.showRecentlyPlayed,
            showFavorites = settings.showFavorites,
            contentMode = settings.contentMode,
            fghCollectionId = fghId,
            toolsName = settings.toolsName,
            portsName = settings.portsName,
            scanDisk = scanDisk,
            reconcileOrphans = reconcileOrphans,
            onProgress = onProgress,
            onReady = {
                onComplete?.invoke()
                if (fghId != null) scanResumableGames()
            }
        )
    }

    /** Rescans behind the progress overlay. A disk scan can run for a long time on a slow card, so
     *  anything that triggers one from the UI shows this rather than leaving the launcher looking
     *  hung with no indication that work is happening. */
    fun rescanWithProgress(reconcileOrphans: Boolean = false, onComplete: (() -> Unit)? = null) {
        nav.dialogState.value = DialogState.RescanProgress(
            0f, context.getString(dev.piannoli.scorza.R.string.boot_preparing),
        )
        rescanSystemList(
            scanDisk = true,
            reconcileOrphans = reconcileOrphans,
            onProgress = { tag, current, total ->
                nav.dialogState.value = DialogState.RescanProgress(
                    current.toFloat() / total.coerceAtLeast(1), tag,
                )
            },
            onComplete = {
                nav.dialogState.value = DialogState.None
                onComplete?.invoke()
            },
        )
    }

    fun refreshLauncherLists(scanDisk: Boolean = true) {
        if (systemListViewModel.state.value.isLoading) return
        rescanSystemList(scanDisk = scanDisk)
        if (nav.currentScreen is LauncherScreen.GameList) {
            gameListViewModel.reload { scanResumableGames() }
        }
    }

    fun validateFghCollection(): Long? {
        if (settings.contentMode != ContentMode.FIVE_GAME_HANDHELD) return null
        val all = collectionsRepository.all().filter { it.type == CollectionType.STANDARD }
        val current = settings.fghCollectionId
        if (current != null && all.any { it.id == current }) return current
        val fallback = all.firstOrNull()?.id
        settings.fghCollectionId = fallback
        return fallback
    }

    fun scanResumableGames() {
        val gameListRoms = gameListViewModel.state.value.items
            .filterIsInstance<ListItem.RomItem>()
            .map { it.rom }
        val systemListRoms = systemListViewModel.state.value.items
            .filterIsInstance<SystemListViewModel.ListItem.GameItem>()
            .mapNotNull { (it.item as? ListItem.RomItem)?.rom }
        val roms = (gameListRoms + systemListRoms).distinctBy { it.path.absolutePath }
        ioScope.launch {
            val result = launchManager.findResumableRoms(roms)
            withContext(Dispatchers.Main) { nav.resumableGames = result }
        }
    }

    fun invalidateAllLibraryCaches() {
        artworkLookup.invalidateAll()
        arcadeTitleLookup.invalidateAll()
    }

    /** Asks before writing the setting, so declining leaves the library exactly as it was. Pass an
     *  empty [newRomDirectory] to clear the pick back to the Piannoli root. */
    fun confirmRomDirectoryChange(newRomDirectory: String) {
        nav.dialogState.value = DialogState.LibrarySwitchConfirm(newRomDirectory)
    }

    /** Everything that resolves the ROM directory reads it on demand, and the watcher rebinds when
     *  the scan restarts it, so the change only needs the caches dropped, the new place scaffolded
     *  and the library reconciled against it. Reconciling is what prunes the entries the new
     *  location cannot account for, and this is the only path that does it. */
    fun applyRomDirectoryChange(newRomDirectory: String) {
        settings.romDirectory = newRomDirectory
        invalidateAllLibraryCaches()
        settingsViewModel.refreshActiveCategory()
        // Straight from the confirmation onto the scan screen. Clearing the dialog and waiting for
        // the scaffold to finish first flashes the settings list back up in between.
        nav.dialogState.value = DialogState.RescanProgress(
            0f, context.getString(dev.piannoli.scorza.R.string.boot_preparing),
        )
        ioScope.launch {
            val romDir = pathsProvider.romDir
            if (dev.piannoli.scorza.util.DirectoryLayout.romDirNeedsScaffold(romDir)) {
                dev.piannoli.scorza.util.DirectoryLayout.scaffoldRomFolders(romDir, platformConfig.getAllTags())
            }
            withContext(Dispatchers.Main) {
                // The settings screens below were built against the old library, so the scan lands
                // the user back on the main list rather than where they started.
                rescanWithProgress(reconcileOrphans = true) {
                    nav.screenStack.clear()
                    nav.screenStack.add(LauncherScreen.SystemList)
                    settingsViewModel.resetToCategoryList()
                }
            }
        }
    }


    fun launchSelected(item: ListItem, resume: Boolean): DialogState? {
        return when (item) {
            is ListItem.RomItem -> launchSelectedRom(item.rom, resume)
            is ListItem.AppItem -> launchManager.launchApp(item.app)
            else -> null
        }
    }

    private fun launchSelectedRom(rom: Rom, resume: Boolean): DialogState? {
        val gameKey = RomKeys.relativeKey(rom.path, pathsProvider.romDir)
        val romId = saveSyncService.isSyncableGame(gameKey)
        if (romId == null) {
            return if (resume) launchManager.resumeRom(rom) else launchManager.launchRom(rom)
        }
        val tag = rom.platformTag
        val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)
        val emulator = RomKeys.coreDisplayNameFor(rom, platformConfig, gameOverrideStore.get(rom.id))
        pendingLaunch = { if (resume) launchManager.resumeRom(rom) else launchManager.launchRom(rom) }
        nav.dialogState.value = DialogState.SaveSyncChecking
        ioScope.launch {
            val outcome = saveSyncService.syncBeforeLaunch(tag, base, gameKey, emulator)
            withContext(Dispatchers.Main) {
                when (outcome) {
                    is PreLaunchOutcome.Proceed -> {
                        nav.dialogState.value = DialogState.None
                        proceedPendingLaunch()
                    }
                    is PreLaunchOutcome.Conflict -> nav.dialogState.value = DialogState.SaveSyncConflict(outcome)
                    is PreLaunchOutcome.KnownStaleBlock -> nav.dialogState.value = DialogState.SaveSyncStaleBlock(outcome, tag, base)
                }
            }
        }
        return null
    }

    fun launchRomFromSlot(rom: Rom, slot: Int): DialogState? =
        launchManager.resumeRom(rom, slot)

    fun buildSaveStatePicker(rom: Rom, awaitConfirmRelease: Boolean): LauncherScreen.SaveStatePicker =
        LauncherScreen.SaveStatePicker(
            rom = rom,
            stateBasePath = launchManager.saveStateBasePath(rom),
            slotOccupied = launchManager.slotOccupancy(rom),
            selectedSlotIndex = launchManager.findMostRecentSlot(rom) ?: 0,
            awaitConfirmRelease = awaitConfirmRelease,
        )

    fun recordRecentlyPlayedByPath(path: String) {
        ioScope.launch {
            resolvePathToRef(path)?.let { recentlyPlayedRepository.record(it) }
        }
    }

    fun openColorPicker(settingKey: String) {
        val hex = settingsViewModel.getColorHex(settingKey)
        val color = hexToColor(hex) ?: androidx.compose.ui.graphics.Color.White
        val argb = colorToArgbLong(color)
        val idx = COLOR_PRESETS.indexOfFirst { it.color == argb }
        val row = if (idx >= 0) idx / COLOR_GRID_COLS else 0
        val col = if (idx >= 0) idx % COLOR_GRID_COLS else 0
        nav.dialogState.value = DialogState.ColorPicker(
            settingKey = settingKey,
            title = colorSettingTitle(settingKey),
            currentColor = argb,
            selectedRow = row,
            selectedCol = col
        )
    }

    fun handleSystemListRename(state: DialogState.RenameInput) {
        val newName = state.currentName.trim()
        val item = systemListViewModel.getSelectedItem()
        val blankResetsToDefault = item is SystemListViewModel.ListItem.PlatformItem
        if (newName == state.gameName || (newName.isEmpty() && !blankResetsToDefault)) {
            nav.dialogState.value = DialogState.None
            return
        }
        when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> {
                ioScope.launch {
                    platformConfig.setDisplayName(item.platform.tag, newName)
                    rescanSystemList(scanDisk = false)
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                settings.toolsName = newName
                rescanSystemList()
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                settings.portsName = newName
                rescanSystemList()
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                ioScope.launch {
                    val id = collectionsRepository.all().firstOrNull { it.displayName == item.name }?.id
                    if (id != null) collectionsRepository.rename(id, newName)
                    rescanSystemList()
                }
            }
            else -> {}
        }
        nav.dialogState.value = DialogState.None
    }

    private fun resolvePathToRef(path: String): LibraryRef? {
        return if (path.startsWith("/apps/")) {
            val parts = path.removePrefix("/apps/").split("/", limit = 2)
            if (parts.size == 2) {
                val type = runCatching { AppType.valueOf(parts[0]) }.getOrNull()
                type?.let { appsRepository.byPackage(it, parts[1]) }?.let { LibraryRef.App(it.id) }
            } else null
        } else {
            romsRepository.gameByPath(path)?.let { LibraryRef.Rom(it.id) }
        }
    }

    private fun colorSettingTitle(settingKey: String): String {
        val labelRes = when (settingKey) {
            "color_accent" -> dev.piannoli.scorza.R.string.setting_color_accent
            "color_highlight" -> dev.piannoli.scorza.R.string.setting_color_highlight
            "color_highlight_text" -> dev.piannoli.scorza.R.string.setting_color_highlight_text
            "color_text" -> dev.piannoli.scorza.R.string.setting_color_text
            "color_title" -> dev.piannoli.scorza.R.string.setting_color_title
            else -> return ""
        }
        return context.getString(labelRes)
    }
}
