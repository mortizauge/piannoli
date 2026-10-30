package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.input.screen.ScrollListInputHandler
import dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder
import dev.cannoli.scorza.ui.screens.ConflictRow
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.quickmenu.QuickMenuRow
import dev.cannoli.scorza.scraper.BulkScraperController
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConflictsApplyingStateTest {

    private lateinit var nav: NavigationController
    private lateinit var handler: DialogInputHandler
    private val bulkScraper = mockk<BulkScraperController>(relaxed = true)
    private val scraper = mockk<dev.cannoli.scorza.scraper.ScraperController>(relaxed = true)
    private val launcherActions = mockk<LauncherActions>(relaxed = true)
    private val settings = mockk<dev.cannoli.scorza.settings.SettingsRepository>(relaxed = true)
    private val systems = mockk<dev.cannoli.scorza.ui.viewmodel.SystemListViewModel>(relaxed = true)
    private val games = mockk<dev.cannoli.scorza.ui.viewmodel.GameListViewModel>(relaxed = true)
    private val activityActions = mockk<ActivityActions>(relaxed = true)
    private val apps = mockk<dev.cannoli.scorza.db.AppsRepository>(relaxed = true)

    // Never advanced: the apply coroutine stays queued so the assertions see only what Start did
    // synchronously, which is the part the user perceives.
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        nav = NavigationController()
        handler = DialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(dispatcher),
            context = ApplicationProvider.getApplicationContext(),
            settings = settings,
            collectionManager = mockk(relaxed = true),
            recentlyPlayedManager = mockk(relaxed = true),
            platformResolver = mockk(relaxed = true),
            installedCoreService = mockk(relaxed = true),
            launchManager = mockk(relaxed = true),
            updateManager = mockk(relaxed = true),
            atomicRename = mockk(relaxed = true),
            scanner = mockk(relaxed = true),
            romDirectoryWalker = mockk(relaxed = true),
            settingsViewModel = mockk(relaxed = true),
            gameListViewModel = games,
            systemListViewModel = systems,
            romsRepository = mockk(relaxed = true),
            gameOverrideStore = mockk(relaxed = true),
            appsRepository = apps,
            artworkLookup = mockk(relaxed = true),
            launcherActions = launcherActions,
            activityActions = activityActions,
            controllersViewModel = mockk(relaxed = true),
            emulatorMappingBuilder = mockk(relaxed = true),
            rommStore = mockk(relaxed = true),
            rommDownloader = mockk(relaxed = true),
            rommBrowseViewModel = mockk(relaxed = true),
            rommArtFetcher = mockk(relaxed = true),
            raPreloadController = mockk(relaxed = true),
            deviceRegistrar = mockk(relaxed = true),
            saveSyncService = mockk(relaxed = true),
            slotManager = mockk(relaxed = true),
            saveSlotsHandler = mockk(relaxed = true),
            syncHistoryStore = mockk(relaxed = true),
            pendingConflictStore = mockk(relaxed = true),
            saveSyncStatusHolder = SaveSyncStatusHolder(),
            osdController = mockk(relaxed = true),
            rommDevicePairing = mockk(relaxed = true),
            scraper = scraper,
            bulkScraper = bulkScraper,
        )
    }

    @Test fun `ports offer individual scraping while tools do not and selected port is dispatched`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val port = dev.cannoli.scorza.model.App(1, dev.cannoli.scorza.model.AppType.PORT, "Game", "dev.game")
        val item = dev.cannoli.scorza.model.ListItem.AppItem(port)
        val state = dev.cannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "ports")
        val action = context.getString(dev.cannoli.scorza.R.string.steamgrid_action)
        assertTrue(action in handler.buildGameContextOptions(item, state))
        assertFalse(action in handler.buildGameContextOptions(
            dev.cannoli.scorza.model.ListItem.AppItem(port.copy(type = dev.cannoli.scorza.model.AppType.TOOL)), state))
        every { games.getSelectedItem() } returns item
        every { games.state } returns kotlinx.coroutines.flow.MutableStateFlow(state)
        nav.push(LauncherScreen.GameList)
        nav.dialogState.value = DialogState.ContextMenu("Game", options = listOf(action))
        handler.onConfirm()
        verify(exactly = 1) { scraper.openPort(port) }
    }



    @Test fun `ports platform dispatches both game and system scraping`() {
        every { systems.getSelectedPlatformTag() } returns "PORTS"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        nav.dialogState.value = DialogState.ContextMenu("Ports", options = listOf(
            context.getString(dev.cannoli.scorza.R.string.bulk_platform_games, "Ports")))
        handler.onConfirm()
        verify(exactly = 1) { bulkScraper.open("PORTS", any()) }
        nav.dialogState.value = DialogState.ContextMenu("Ports", options = listOf(
            context.getString(dev.cannoli.scorza.R.string.scrape_system)))
        handler.onConfirm()
        verify(exactly = 1) { scraper.openSystem("PORTS", "Ports") }
    }

    @Test fun `scrape systems quick menu action passes available platforms to bulk scraping`() {
        val available = listOf(dev.cannoli.scorza.model.Platform("GB", "Game Boy", null))
        every { systems.state } returns kotlinx.coroutines.flow.MutableStateFlow(
            dev.cannoli.scorza.ui.viewmodel.SystemListViewModel.State(platforms = available))
        nav.dialogState.value = DialogState.QuickMenu(rows = listOf(QuickMenuRow.SCRAPE_SYSTEMS), kitchenRunning = false)
        handler.onConfirm()
        verify(exactly = 1) { bulkScraper.openSystems(available, any()) }
        verify(exactly = 0) { bulkScraper.open(any(), any()) }
    }

    @Test fun `platform delete art uses selected type closes menu and reports storage errors`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val type = dev.cannoli.scorza.settings.SystemArtworkType.ICON
            every { settings.systemArtworkType } returns type
            every { systems.getSelectedPlatformTag() } returns "PORTS"
            val origin = DialogState.ContextMenu("Ports", options = listOf(MENU_DELETE_ART))
            nav.dialogState.value = origin
            handler.onConfirm()
            runCurrent()
            coVerify(exactly = 1) { systems.deleteSystemArtwork("PORTS", type) }
            assertEquals(DialogState.None, nav.dialogState.value)
            coEvery { systems.deleteSystemArtwork("PORTS", type) } throws java.io.IOException("denied")
            nav.dialogState.value = origin
            handler.onConfirm()
            runCurrent()
            assertTrue(nav.dialogState.value is DialogState.LaunchError)
        } finally {
            Dispatchers.resetMain()
        }
    }


    @Test fun `extension filter confirm toggles exclusions and directions navigate checkbox list`() {
        val origin = DialogState.ContextMenu("Game Boy", options = listOf("Filter extensions"))
        nav.dialogState.value = DialogState.ExtensionFilters("GB", "Filter extensions",
            listOf("gb", "zip"), emptySet(), origin)
        handler.onDown()
        handler.onConfirm()
        assertEquals(setOf("zip"), (nav.dialogState.value as DialogState.ExtensionFilters).excluded)
        handler.onConfirm()
        assertEquals(emptySet<String>(), (nav.dialogState.value as DialogState.ExtensionFilters).excluded)
        handler.onUp()
        assertEquals(0, (nav.dialogState.value as DialogState.ExtensionFilters).selectedIndex)
        every { settings.filteredExtensions("GB") } returns emptySet()
        handler.onConfirm()
        handler.onBack()
        assertEquals(origin, nav.dialogState.value)
        verify(exactly = 1) { settings.setFilteredExtensions("GB", setOf("gb")) }
        verify(exactly = 1) { systems.refreshFilteredPlatform("GB") }
    }

    @Test fun `scrape system menu action uses the selected platform without starting a game scrape`() {
        every { systems.getSelectedPlatformTag() } returns "GB"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = "Game Boy",
            options = listOf(context.getString(dev.cannoli.scorza.R.string.scrape_system)),
        )
        handler.onConfirm()
        verify(exactly = 1) { scraper.openSystem("GB", "Game Boy") }
        verify(exactly = 0) { bulkScraper.open(any(), any()) }
    }

    @Test fun `keyboard shoulders move caret one position and clamp at text boundaries`() {
        every { settings.keyboardType } returns dev.cannoli.scorza.settings.KeyboardType.PIANNOLI
        nav.dialogState.value = DialogState.RenameInput(
            gameName = "Game",
            keyboard = dev.cannoli.ui.components.KeyboardState(text = "Game", cursorPos = 2),
        )
        fun cursor() = (nav.dialogState.value as DialogState.RenameInput).keyboard.cursorPos
        handler.onL1()
        assertEquals(1, cursor())
        handler.onR1()
        assertEquals(2, cursor())
        repeat(5) { handler.onL1() }
        assertEquals(0, cursor())
        repeat(6) { handler.onR1() }
        assertEquals(4, cursor())
        assertEquals("Game", (nav.dialogState.value as DialogState.RenameInput).keyboard.text)
    }

    // Resolving a row is a network round trip. Without the swap the list sits there unchanged for
    // seconds and Start reads as dead.
    @Test fun start_leaves_the_conflicts_list_immediately() {
        nav.dialogState.value = conflictsMenu()
        handler.onStart()
        assertTrue(nav.dialogState.value is DialogState.ConflictsApplying)
    }

    @Test fun a_second_start_cannot_re_apply_while_the_first_is_in_flight() {
        nav.dialogState.value = conflictsMenu()
        handler.onStart()
        handler.onStart()
        assertTrue(nav.dialogState.value is DialogState.ConflictsApplying)
    }

    @Test fun back_is_swallowed_while_applying() {
        nav.dialogState.value = DialogState.ConflictsApplying
        assertTrue(handler.onBack())
        assertTrue(nav.dialogState.value is DialogState.ConflictsApplying)
    }

    @Test fun quick_menu_scraping_opens_bulk_controller() {
        nav.dialogState.value = DialogState.QuickMenu(rows = listOf(QuickMenuRow.SCRAPING), kitchenRunning = false)
        handler.onConfirm()
        verify(exactly = 1) { bulkScraper.open(null, any()) }
    }

    @Test fun bulk_menu_selection_and_busy_back_use_bulk_controller() {
        val menu = DialogState.ScraperMenu("Scraping", listOf("Scrape all"), bulk = true)
        nav.dialogState.value = menu
        handler.onLeft()
        handler.onRight()
        verify(exactly = 2) { bulkScraper.cycle(menu) }
        handler.onConfirm()
        verify(exactly = 1) { bulkScraper.confirm(menu) }
        nav.dialogState.value = DialogState.ScraperStatus("Working", busy = true, bulk = true)
        handler.onConfirm()
        verify(exactly = 0) { bulkScraper.back() }
        handler.onBack()
        verify(exactly = 1) { bulkScraper.back() }
    }

    @Test fun system_keyboard_back_cancels_and_returns_to_the_same_settings_screen() {
        every { settings.keyboardType } returns dev.cannoli.scorza.settings.KeyboardType.SYSTEM
        nav.push(LauncherScreen.Settings)
        val input = DialogState.RenameInput(
            "title", keyboard = dev.cannoli.ui.components.KeyboardState(text = "Unsaved title"),
        )
        nav.dialogState.value = input
        handler.onBack()
        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
        nav.dialogState.value = DialogState.ScraperInput(dev.cannoli.scorza.scraper.ScraperField.API_KEY)
        handler.onBack()
        verify(exactly = 1) { scraper.back() }
    }

    @Test fun piannoli_keyboard_back_deletes_and_west_cancels_including_scraper_inputs() {
        every { settings.keyboardType } returns dev.cannoli.scorza.settings.KeyboardType.PIANNOLI
        nav.push(LauncherScreen.Settings)
        val keyboard = dev.cannoli.ui.components.KeyboardState(text = "abc", cursorPos = 3)
        for (input in listOf(
            DialogState.RenameInput("title", keyboard = keyboard),
            DialogState.ScraperInput(dev.cannoli.scorza.scraper.ScraperField.API_KEY, keyboard),
        )) {
            nav.dialogState.value = input
            handler.onBack()
            val edited = nav.dialogState.value as dev.cannoli.scorza.ui.screens.KeyboardHost
            assertEquals("ab", edited.keyboard.text)
            assertEquals(2, edited.keyboard.cursorPos)
            verify(exactly = 0) { scraper.back() }
            handler.onWest()
        }
        verify(exactly = 1) { scraper.back() }
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
    }

    @Test fun back_from_hex_editor_restores_color_picker_instead_of_deleting_text() {
        nav.dialogState.value = DialogState.HexColorInput("color_background", "Background", "123456")
        handler.onNorth()
        assertEquals("12345", (nav.dialogState.value as DialogState.HexColorInput).currentHex)
        handler.onBack()
        verify(exactly = 1) { launcherActions.openColorPicker("color_background") }
    }

    @Test fun back_from_platform_rename_restores_the_selected_platform_submenu() {
        every { settings.keyboardType } returns dev.cannoli.scorza.settings.KeyboardType.SYSTEM
        val origin = DialogState.ContextMenu("Game Boy", options = listOf(MENU_RENAME))
        nav.dialogState.value = origin
        handler.onConfirm()
        assertTrue(nav.dialogState.value is DialogState.RenameInput)
        handler.onBack()
        assertEquals(origin, nav.dialogState.value)
        handler.onBack()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun back_from_romm_downloads_returns_to_romm_actions_menu() {
        nav.dialogState.value = DialogState.RommActionsMenu(hasDownloads = true)
        handler.onConfirm()
        assertTrue(nav.dialogState.value is DialogState.RommDownloads)
        handler.onBack()
        assertEquals(DialogState.RommActionsMenu(hasDownloads = true), nav.dialogState.value)
    }

    @Test fun cancelling_disconnect_keeps_server_info_parent_settings_menu() {
        nav.dialogState.value = DialogState.RommConnected("server", fromSettingsMenu = true)
        handler.onWest()
        assertTrue(nav.dialogState.value is DialogState.RommConfirm)
        handler.onBack()
        assertTrue((nav.dialogState.value as DialogState.RommConnected).fromSettingsMenu)
        handler.onBack()
        assertTrue(nav.dialogState.value is DialogState.RommSettingsMenu)
    }

    private fun router(): InputRouter {
        val factory = mockk<ScrollListInputHandler.Factory>()
        every { factory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            ScrollListInputHandler(nav, arg(0), arg(1), arg(2), arg(3), arg(4), arg(5),
                arg(6), arg(7), arg(8), arg(9), arg(10), arg(11))
        }
        return InputRouter(
            nav = nav, dialogHandler = handler, systemListHandler = mockk(), gameListHandler = mockk(),
            settingsHandler = mockk(), onboardingHandler = mockk(), inputTesterHandler = mockk(),
            saveStatePickerHandler = mockk(), saveSlotsHandler = mockk(), guideHandler = mockk(),
            controllerDetailHandler = mockk(), controllersHandler = mockk(), editButtonsHandler = mockk(),
            loggingSettingsHandler = mockk(), scrollListFactory = factory, platformConfig = mockk(),
            gameOverrideStore = mockk(), installedCoreService = mockk(), emulatorMappingBuilder = mockk(),
            globalOverrides = mockk(), launcherActions = launcherActions, bindingController = mockk(),
            screenInputRegistry = mockk(), context = ApplicationProvider.getApplicationContext(),
            settings = mockk(), coreInstaller = mockk(), rommBrowseViewModel = mockk(),
            rommDownloader = mockk(relaxed = true), osdController = mockk(), raPreloadController = mockk(),
            ioScope = CoroutineScope(dispatcher), menuSounds = mockk(),
        )
    }

    @Test fun back_from_romm_library_returns_to_quick_menu_without_leaving_underlying_screen() {
        nav.push(LauncherScreen.Settings)
        val origin = DialogState.QuickMenu(rows = listOf(QuickMenuRow.ROMM), kitchenRunning = false)
        nav.dialogState.value = origin
        handler.onConfirm()
        assertTrue(nav.currentScreen is LauncherScreen.RommPlatformList)
        router().currentHandler().onBack()
        assertEquals(origin, nav.dialogState.value)
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
    }

    @Test fun back_from_credits_returns_to_about_then_settings() {
        nav.push(LauncherScreen.Settings)
        val about = DialogState.About()
        nav.dialogState.value = about
        handler.onNorth()
        assertTrue(nav.currentScreen is LauncherScreen.Credits)
        router().currentHandler().onBack()
        assertEquals(about, nav.dialogState.value)
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
        handler.onBack()
        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
    }
}
