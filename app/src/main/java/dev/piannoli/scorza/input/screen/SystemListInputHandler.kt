package dev.piannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.CollectionsRepository
import dev.piannoli.scorza.di.IoScope
import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.input.MENU_ADD_FAVORITE
import dev.piannoli.scorza.input.MENU_DELETE_GAME
import dev.piannoli.scorza.input.MENU_DOWNLOAD_ART
import dev.piannoli.scorza.input.MENU_EMULATOR_OVERRIDE
import dev.piannoli.scorza.input.MENU_MANAGE_COLLECTIONS
import dev.piannoli.scorza.input.MENU_REMOVE_FAVORITE
import dev.piannoli.scorza.input.MENU_RENAME
import dev.piannoli.scorza.input.PageJump
import dev.piannoli.scorza.input.ScreenInputHandler
import dev.piannoli.scorza.model.ListItem
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.ContentMode
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.piannoli.scorza.updater.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@ActivityScoped
class SystemListInputHandler @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val nav: NavigationController,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val platformConfig: PlatformConfig,
    private val updateManager: UpdateManager,
    private val systemListViewModel: SystemListViewModel,
    private val gameListViewModel: GameListViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val launcherActions: LauncherActions,
    private val rommConnectionStore: dev.piannoli.scorza.romm.RommConnectionStore,
) : ScreenInputHandler {

    private var selectDown = false

    override fun onUp() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveUp()
        else systemListViewModel.moveSelection(-1)
    }

    override fun onDown() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveDown()
        else systemListViewModel.moveSelection(1)
    }

    override fun onLeft() {
        if (!systemListViewModel.isReorderMode()) pageJump(-1)
    }

    override fun onRight() {
        if (!systemListViewModel.isReorderMode()) pageJump(1)
    }

    override fun onL1() {}

    override fun onR1() {
        if (systemListViewModel.isReorderMode()) return
        if (nav.beginLauncherSearch()) gameListViewModel.beginSearch(fromGameList = false)
        nav.dialogState.value = DialogState.RenameInput(
            gameName = "launcher_global_search",
        )
    }

    override fun onConfirm() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
        else onSystemListConfirm()
    }

    override fun onBack() {
        if (systemListViewModel.isReorderMode()) {
            systemListViewModel.cancelReorder(
                showRecentlyPlayed = settings.showRecentlyPlayed,
                showFavorites = settings.showFavorites,
                contentMode = settings.contentMode,
                fghCollectionId = launcherActions.validateFghCollection(),
                toolsName = settings.toolsName,
                portsName = settings.portsName
            )
        } else if (settings.mainMenuQuit) {
            nav.dialogState.value = DialogState.QuitConfirm
        }
    }

    override fun onStart() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
        else onSystemListContextMenu()
    }

    override fun onSelect() {
        if (selectDown) return
        selectDown = true
        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
        else systemListViewModel.enterReorderMode()
    }

    override fun onSelectUp() {
        selectDown = false
    }

    override fun onNorth() {
        val fgh = launcherActions.validateFghCollection() != null
        val item = systemListViewModel.getSelectedItem()
        if (fgh && item is SystemListViewModel.ListItem.GameItem) {
            val recentKey = item.recentKey
            val isResumable = nav.resumableGames.contains(recentKey)
            if (isResumable) {
                val errorDialog = launcherActions.launchSelected(item.item, !settings.swapPlayResume)
                if (errorDialog != null) {
                    nav.dialogState.value = errorDialog
                } else if (nav.dialogState.value is DialogState.SaveSyncChecking) {
                    launcherActions.recordPendingRecent(recentKey, false)
                } else {
                    launcherActions.recordRecentlyPlayedByPath(recentKey)
                }
            }
        } else if (!fgh) {
            systemListViewModel.savePosition()
            settingsViewModel.load()
            nav.screenStack.add(LauncherScreen.Settings)
            if (updateManager.isOnline()) {
                ioScope.launch { updateManager.checkForUpdate() }
            }
        }
    }

    override fun onWest() {
        if (settings.contentMode == ContentMode.FIVE_GAME_HANDHELD) {
            systemListViewModel.savePosition()
            settingsViewModel.load()
            nav.screenStack.add(LauncherScreen.Settings)
            if (updateManager.isOnline()) {
                ioScope.launch { updateManager.checkForUpdate() }
            }
        }
    }

    private fun pageJump(direction: Int) {
        val state = systemListViewModel.state.value
        val newIdx = PageJump.compute(direction, state.items.size, state.selectedIndex, nav.activeListState)
        if (newIdx != state.selectedIndex) systemListViewModel.setSelectedIndex(newIdx)
    }

    private fun onSystemListConfirm() {
        if (nav.navigating) return
        systemListViewModel.savePosition()
        when (val item = systemListViewModel.getSelectedItem()) {
            is SystemListViewModel.ListItem.RecentlyPlayedItem -> {
                nav.navigating = true
                gameListViewModel.loadRecentlyPlayed {
                    launcherActions.scanResumableGames()
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.FavoritesItem -> {
                nav.navigating = true
                gameListViewModel.loadFavorites {
                    launcherActions.scanResumableGames()
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionsFolder -> {
                nav.navigating = true
                gameListViewModel.loadCollectionsList {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.PlatformItem -> {
                nav.navigating = true
                gameListViewModel.loadPlatform(item.platform.tag, item.platform.allTags) {
                    launcherActions.scanResumableGames()
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                nav.navigating = true
                gameListViewModel.loadCollectionById(item.id) {
                    launcherActions.scanResumableGames()
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.GameItem -> {
                val recentKey = item.recentKey
                val isResumable = nav.resumableGames.contains(recentKey)
                val resume = isResumable && settings.swapPlayResume
                val errorDialog = launcherActions.launchSelected(item.item, resume)
                if (errorDialog != null) {
                    nav.dialogState.value = errorDialog
                } else if (nav.dialogState.value is DialogState.SaveSyncChecking) {
                    launcherActions.recordPendingRecent(recentKey, false)
                } else {
                    launcherActions.recordRecentlyPlayedByPath(recentKey)
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                nav.navigating = true
                gameListViewModel.loadApkList("tools", item.name) {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                nav.navigating = true
                gameListViewModel.loadApkList("ports", item.name) {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            else -> {}
        }
    }

    private fun onSystemListContextMenu() {
        val item = systemListViewModel.getSelectedItem() ?: return
        if (item is SystemListViewModel.ListItem.GameItem) {
            val ref = resolveItemRef(item)
            val isFav = ref?.let { r ->
                when (r) {
                    is dev.piannoli.scorza.db.LibraryRef.Rom -> collectionsRepository.isRomFavorited(r.id)
                    is dev.piannoli.scorza.db.LibraryRef.App -> collectionsRepository.isAppFavorited(r.id)
                }
            } == true
            nav.pendingFghItem = item.item
            val menuName = item.displayName
            val options = buildList {
                val app = (item.item as? ListItem.AppItem)?.app
                add(if (app?.type == dev.piannoli.scorza.model.AppType.TOOL) {
                    if (app.isPinned) dev.piannoli.scorza.input.MENU_UNPIN_TOOL else dev.piannoli.scorza.input.MENU_PIN_TOOL
                } else if (isFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
                if (item.item is dev.piannoli.scorza.model.ListItem.RomItem ||
                    (item.item as? dev.piannoli.scorza.model.ListItem.AppItem)?.app?.type == dev.piannoli.scorza.model.AppType.PORT
                ) add(context.getString(dev.piannoli.scorza.R.string.steamgrid_action))
                add(MENU_MANAGE_COLLECTIONS)
                add(MENU_EMULATOR_OVERRIDE)
                if (item.item is dev.piannoli.scorza.model.ListItem.RomItem ||
                    (item.item as? dev.piannoli.scorza.model.ListItem.AppItem)?.app?.type == dev.piannoli.scorza.model.AppType.PORT
                ) add(dev.piannoli.scorza.input.MENU_HIDE_GAME)
                add(MENU_DELETE_GAME)
            }
            nav.dialogState.value = DialogState.ContextMenu(gameName = menuName, options = options)
            return
        }
        nav.pendingFghItem = null
        val name = when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> item.platform.displayName
            is SystemListViewModel.ListItem.ToolsFolder -> item.name
            is SystemListViewModel.ListItem.PortsFolder -> item.name
            is SystemListViewModel.ListItem.RecentlyPlayedItem -> context.getString(dev.piannoli.scorza.R.string.label_recently_played)
            is SystemListViewModel.ListItem.FavoritesItem -> context.getString(dev.piannoli.scorza.R.string.label_favorites)
            is SystemListViewModel.ListItem.CollectionsFolder -> context.getString(dev.piannoli.scorza.R.string.label_collections)
            is SystemListViewModel.ListItem.CollectionItem -> item.name
            else -> return
        }
        val options = buildList {
            if (item is SystemListViewModel.ListItem.PlatformItem || item is SystemListViewModel.ListItem.PortsFolder) {
                add(context.getString(dev.piannoli.scorza.R.string.bulk_platform_games, name))
                add(context.getString(dev.piannoli.scorza.R.string.scrape_system))
            }
            add(context.getString(dev.piannoli.scorza.R.string.choose_artwork))
            add(dev.piannoli.scorza.input.MENU_DELETE_ART)
            if (item is SystemListViewModel.ListItem.PlatformItem) {
                add(context.getString(dev.piannoli.scorza.R.string.filter_extensions))
            }
            if (item is SystemListViewModel.ListItem.PlatformItem ||
                item is SystemListViewModel.ListItem.ToolsFolder ||
                item is SystemListViewModel.ListItem.PortsFolder
            ) add(MENU_RENAME)
            if (item is SystemListViewModel.ListItem.PlatformItem && rommConnectionStore.isConfigured) {
                add(MENU_DOWNLOAD_ART)
            }
        }
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = name,
            options = options
        )
    }

    private fun resolveItemRef(item: SystemListViewModel.ListItem.GameItem): dev.piannoli.scorza.db.LibraryRef? {
        return when (val inner = item.item) {
            is ListItem.RomItem -> dev.piannoli.scorza.db.LibraryRef.Rom(inner.rom.id)
            is ListItem.AppItem -> dev.piannoli.scorza.db.LibraryRef.App(inner.app.id)
            else -> null
        }
    }

}
