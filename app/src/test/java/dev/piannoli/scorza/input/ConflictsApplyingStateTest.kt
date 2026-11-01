package dev.piannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.input.screen.ScrollListInputHandler
import dev.piannoli.scorza.romm.sync.SaveSyncStatusHolder
import dev.piannoli.scorza.ui.screens.ConflictRow
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.screens.KeyboardHost
import dev.piannoli.scorza.ui.quickmenu.QuickMenuRow
import dev.piannoli.scorza.scraper.BulkScraperController
import dev.piannoli.scorza.util.NetworkInfo
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
    private val scraper = mockk<dev.piannoli.scorza.scraper.ScraperController>(relaxed = true)
    private val launcherActions = mockk<LauncherActions>(relaxed = true)
    private val settings = mockk<dev.piannoli.scorza.settings.SettingsRepository>(relaxed = true)
    private val systems = mockk<dev.piannoli.scorza.ui.viewmodel.SystemListViewModel>(relaxed = true)
    private val games = mockk<dev.piannoli.scorza.ui.viewmodel.GameListViewModel>(relaxed = true)
    private val activityActions = mockk<ActivityActions>(relaxed = true)
    private val apps = mockk<dev.piannoli.scorza.db.AppsRepository>(relaxed = true)
    private val roms = mockk<dev.piannoli.scorza.db.RomsRepository>(relaxed = true)
    private val artwork = mockk<dev.piannoli.scorza.util.ArtworkLookup>(relaxed = true)

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
            updateManager = mockk(relaxed = true),
            atomicRename = mockk(relaxed = true),
            scanner = mockk(relaxed = true),
            romDirectoryWalker = mockk(relaxed = true),
            settingsViewModel = mockk(relaxed = true),
            gameListViewModel = games,
            systemListViewModel = systems,
            romsRepository = roms,
            gameOverrideStore = mockk(relaxed = true),
            appsRepository = apps,
            artworkLookup = artwork,
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

    @Test fun `dialog pickers and RomM menus clamp held directions and fresh presses wrap`() {
        val origin = DialogState.ContextMenu(gameName = "g", options = listOf("a", "b"))
        val dialogs = listOf(
            origin,
            DialogState.RommSettingsMenu(),
            DialogState.ExtensionFilters("GB", "Extensions", listOf("gb", "zip"), emptySet(), origin),
            DialogState.QuickMenu(rows = listOf(QuickMenuRow.INFO, QuickMenuRow.ROMM)),
            DialogState.RommPlatformToggle(items = listOf(
                dev.piannoli.scorza.ui.screens.RommPlatformToggleItem("GB", "GB", true),
                dev.piannoli.scorza.ui.screens.RommPlatformToggleItem("SNES", "SNES", true),
            )),
        )
        for (dialog in dialogs) {
            nav.dialogState.value = dialog
            ListNavigation.withRepeat(true) { repeat(4) { handler.onUp() } }
            assertEquals(dialog, nav.dialogState.value)
            handler.onUp()
            val end = nav.dialogState.value
            assertTrue(end != dialog)
            ListNavigation.withRepeat(true) { repeat(4) { handler.onDown() } }
            assertEquals(end, nav.dialogState.value)
            handler.onDown()
            assertEquals(dialog, nav.dialogState.value)
        }
    }

    @Test fun `keyboard grid retains wrapping during held navigation`() {
        nav.dialogState.value = DialogState.RenameInput(gameName = "g")
        ListNavigation.withRepeat(true) { handler.onUp() }
        assertTrue((nav.dialogState.value as KeyboardHost).keyboard.keyRow > 0)
    }

    @Test fun `dialog and picker holds stop before handlers and changing dialog releases the latch`() {
        val spy = io.mockk.spyk(handler)
        val input = dev.piannoli.scorza.input.runtime.InputDispatcher(
            dev.piannoli.scorza.input.runtime.PortRouter(),
            dev.piannoli.scorza.input.runtime.ActiveMappingHolder(),
        )
        var feedback = 0
        input.wireToRegistry(dialogHandler = spy, onMenuSound = { feedback++ })
        val origin = DialogState.ContextMenu(gameName = "g", options = listOf("a", "b", "c"))
        for (dialog in listOf(
            origin, DialogState.RommSettingsMenu(),
            DialogState.ExtensionFilters("GB", "Extensions", listOf("gb", "zip"), emptySet(), origin),
            DialogState.QuickMenu(rows = listOf(QuickMenuRow.INFO, QuickMenuRow.ROMM)),
        )) {
            nav.dialogState.value = dialog
            val before = feedback
            ListNavigation.withRepeat(true) { repeat(20) { input.onDown() } }
            assertTrue(feedback > before)
            val atEnd = feedback
            io.mockk.clearMocks(spy, answers = false, recordedCalls = true)
            ListNavigation.withRepeat(true) { repeat(20) { input.onDown() } }
            verify(exactly = 0) { spy.onDown() }
            assertEquals(atEnd, feedback)
            // The opposite direction is usable without releasing the original direction.
            ListNavigation.withRepeat(true) { input.onUp() }
            assertEquals(atEnd + 1, feedback)
            ListNavigation.withRepeat(true) { repeat(20) { input.onDown() } }
        }
        nav.dialogState.value = DialogState.RenameInput(gameName = "g")
        val before = feedback
        ListNavigation.withRepeat(true) { repeat(20) { input.onDown() } }
        assertEquals(before + 20, feedback)
        nav.dialogState.value = DialogState.RenameInput(
            gameName = "g", keyboard = dev.piannoli.ui.components.KeyboardState(text = "Game", cursorPos = 0),
        )
        val beforeCaret = feedback
        ListNavigation.withRepeat(true) { repeat(8) { input.onR1() } }
        assertEquals(4, (nav.dialogState.value as KeyboardHost).keyboard.cursorPos)
        assertEquals(beforeCaret + 8, feedback)
    }

    @Test fun `RomM library and global results share bounded repeat navigation`() {
        val screens = listOf(
            LauncherScreen.RommPlatformList(itemCount = 4),
            LauncherScreen.RommGlobalSearch(term = "m", itemCount = 4),
            LauncherScreen.RommCollectionGroups(itemCount = 4),
        )
        val router = router()
        for (screen in screens) {
            nav.push(screen)
            val input = router.currentHandler()
            fun index() = (nav.currentScreen as LauncherScreen.ScrollableScreen).selectedIndex
            ListNavigation.withRepeat(true) { repeat(8) { input.onDown() } }
            assertEquals(3, index())
            input.onRight()
            assertEquals(3, index())
            input.onDown()
            assertEquals(0, index())
            ListNavigation.withRepeat(true) { repeat(8) { input.onUp() } }
            assertEquals(0, index())
            input.onUp()
            assertEquals(3, index())
            nav.pop()
        }
    }

    @Test fun `RomM boundary skips handlers and updated results release the latch`() {
        val registry = dev.piannoli.scorza.input.runtime.ScreenInputRegistry()
        val input = dev.piannoli.scorza.input.runtime.InputDispatcher(
            dev.piannoli.scorza.input.runtime.PortRouter(),
            dev.piannoli.scorza.input.runtime.ActiveMappingHolder(), registry,
        )
        var feedback = 0
        input.wireToRegistry(navigationContext = { nav.currentScreen }, onMenuSound = { feedback++ })
        val router = router()
        for (screen in listOf(
            LauncherScreen.RommPlatformList(itemCount = 4),
            LauncherScreen.RommGlobalSearch(term = "m", itemCount = 4),
            LauncherScreen.RommCollectionGroups(itemCount = 4),
        )) {
            nav.push(screen)
            val spy = io.mockk.spyk(router.currentHandler())
            registry.push(spy)
            val before = feedback
            input.onDown()
            ListNavigation.withRepeat(true) { repeat(12) { input.onDown() } }
            verify(exactly = 3) { spy.onDown() }
            assertEquals(before + 3, feedback)
            val end = nav.currentScreen as LauncherScreen.ScrollableScreen
            nav.replaceTop(end.withScroll(1, 1))
            ListNavigation.withRepeat(true) { repeat(12) { input.onDown() } }
            verify(exactly = 5) { spy.onDown() }
            assertEquals(before + 5, feedback)
            input.onDown()
            assertEquals(0, (nav.currentScreen as LauncherScreen.ScrollableScreen).selectedIndex)
            registry.pop(spy)
            nav.pop()
        }
    }

    @Test fun `network info opens with local addresses`() {
        mockkObject(NetworkInfo)
        try {
            val addresses = listOf("192.168.1.10", "10.0.0.2")
            every { NetworkInfo.localIpv4Addresses(any()) } returns addresses
            nav.dialogState.value = DialogState.QuickMenu(rows = listOf(QuickMenuRow.INFO))

            handler.onConfirm()

            assertEquals(DialogState.QuickInfo(addresses), nav.dialogState.value)
        } finally {
            unmockkObject(NetworkInfo)
        }
    }

    private fun keyboardDispatcher(
        confirm: CanonicalButton = CanonicalButton.BTN_SOUTH,
        remap: (CanonicalButton, DeviceMapping) -> CanonicalButton = { button, _ -> button },
        feedback: () -> Unit = {},
    ): dev.piannoli.scorza.input.runtime.InputDispatcher {
        every { settings.keyboardType } returns dev.piannoli.scorza.settings.KeyboardType.PIANNOLI
        val router = dev.piannoli.scorza.input.runtime.PortRouter()
        val mapping = DeviceMapping(
            id = "keyboard-test", displayName = "Pad", match = DeviceMatchRule(),
            bindings = mapOf(
                CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
                CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
                CanonicalButton.BTN_SELECT to listOf(InputBinding.Button(109)),
                CanonicalButton.BTN_MENU to listOf(
                    InputBinding.Button(android.view.KeyEvent.KEYCODE_BACK),
                    InputBinding.Button(android.view.KeyEvent.KEYCODE_BUTTON_MODE),
                ),
            ),
            menuConfirm = confirm,
            menuBack = if (confirm == CanonicalButton.BTN_SOUTH) CanonicalButton.BTN_EAST else CanonicalButton.BTN_SOUTH,
            source = MappingSource.ANDROID_DEFAULT,
        )
        router.onConnect(
            ConnectedDevice(7, "pad", "Pad", 1, 1, "test", 0, 0), mapping,
        )
        return dev.piannoli.scorza.input.runtime.InputDispatcher(
            router, dev.piannoli.scorza.input.runtime.ActiveMappingHolder(),
        ).also {
            it.wireToRegistry(dialogHandler = handler, remap = remap, onMenuSound = { feedback() })
        }
    }

    private fun advanceDeleteTime(millis: Long) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(millis))
    }

    private fun dev.piannoli.scorza.input.runtime.InputDispatcher.key(code: Int, down: Boolean, repeats: Int = 0) {
        handleKeyEventForTest(7, code, if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP, repeats)
    }

    @Test fun `Android button repeat filtering leaves raw capture visible without action feedback`() {
        val input = keyboardDispatcher()
        val raw = mutableListOf<Int>()
        var feedback = 0
        input.wireToRegistry(
            screenResolver = { object : ScreenInputHandler {
                override fun onRawKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
                    raw += keyCode
                    return false
                }
            } },
            onMenuSound = { feedback++ },
        )
        for (code in listOf(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_BUTTON_MODE)) {
            assertTrue(input.handleKeyEvent(android.view.KeyEvent(
                0, 0, android.view.KeyEvent.ACTION_DOWN, code, 1,
            )))
        }
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_BUTTON_MODE), raw)
        assertEquals(0, feedback)
    }

    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun `Android back and mode delete once while controller B keeps held deletion`() {
        var feedback = 0
        val input = keyboardDispatcher(
            remap = { button, mapping -> if (button == CanonicalButton.BTN_MENU) mapping.menuBack else button },
            feedback = { feedback++ },
        )
        fun open() {
            nav.dialogState.value = DialogState.RenameInput(
                "game", keyboard = dev.piannoli.ui.components.KeyboardState(text = "abcdef", cursorPos = 6),
            )
        }
        fun text() = (nav.dialogState.value as KeyboardHost).keyboard.text
        for (code in listOf(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_BUTTON_MODE)) {
            open()
            val before = feedback
            input.key(code, true)
            input.key(code, true, repeats = 1)
            input.handleKeyEventForTest(7, code, android.view.KeyEvent.ACTION_MULTIPLE, 2)
            advanceDeleteTime(1000)
            assertEquals("abcde", text())
            assertEquals(before + 1, feedback)
            input.key(code, false)
        }
        open()
        val beforeShim = feedback
        input.handleMenuKey(true)
        input.handleMenuKey(true, isRepeat = true)
        advanceDeleteTime(1000)
        input.handleMenuKey(false)
        assertEquals("abcde", text())
        assertEquals(beforeShim + 1, feedback)

        open()
        val beforeB = feedback
        input.key(android.view.KeyEvent.KEYCODE_BUTTON_B, true)
        input.key(android.view.KeyEvent.KEYCODE_BUTTON_B, true, repeats = 1)
        advanceDeleteTime(460)
        assertEquals("abc", text())
        assertEquals(beforeB + 3, feedback)
        input.key(android.view.KeyEvent.KEYCODE_BUTTON_B, false)
        advanceDeleteTime(1000)
        assertEquals("abc", text())
    }

    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun `remapped delete holds use device mapping repeat without OS events and stop at release and cursor boundary`() {
        val bindings = dev.piannoli.ui.MenuAction.swap(
            emptyMap(), dev.piannoli.ui.MenuAction.BACK, dev.piannoli.ui.MenuAction.SELECT,
        )
        var feedback = 0
        val input = keyboardDispatcher(
            confirm = CanonicalButton.BTN_EAST,
            remap = { button, mapping -> PiannoliRemapping.translate(button, mapping, bindings) },
            feedback = { feedback++ },
        )
        nav.dialogState.value = DialogState.RenameInput(
            "game", keyboard = dev.piannoli.ui.components.KeyboardState(text = "abcXYZ", cursorPos = 3),
        )
        fun text() = (nav.dialogState.value as KeyboardHost).keyboard.text
        input.key(109, true)
        assertEquals("abXYZ", text())
        advanceDeleteTime(399)
        assertEquals("abXYZ", text())
        input.key(109, true, repeats = 1)
        assertEquals("abXYZ", text())
        advanceDeleteTime(1)
        assertEquals("aXYZ", text())
        advanceDeleteTime(60)
        assertEquals("XYZ", text())
        advanceDeleteTime(600)
        assertEquals("XYZ", text())
        assertEquals(3, feedback)
        input.key(109, false)
        nav.dialogState.value = DialogState.RenameInput(
            "next", keyboard = dev.piannoli.ui.components.KeyboardState(text = "new", cursorPos = 3),
        )
        advanceDeleteTime(1000)
        assertEquals("new", text())
        input.key(109, true)
        input.key(109, false)
        advanceDeleteTime(1000)
        assertEquals("ne", text())
        assertEquals(4, feedback)
    }

    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun `delete stops on focus cancellation dialog replacement remapping and rewiring without leaking into menus`() {
        var bindings = emptyMap<dev.piannoli.ui.MenuAction, dev.piannoli.ui.MenuAction>()
        val input = keyboardDispatcher(
            remap = { button, mapping -> PiannoliRemapping.translate(button, mapping, bindings) },
        )
        fun open() {
            nav.dialogState.value = DialogState.RenameInput(
                "game", keyboard = dev.piannoli.ui.components.KeyboardState(text = "abcdef", cursorPos = 6),
            )
        }
        open()
        input.key(97, true)
        input.cancelKeyboardDeleteHold()
        advanceDeleteTime(1000)
        assertEquals("abcde", (nav.dialogState.value as KeyboardHost).keyboard.text)
        input.key(97, false)
        input.key(97, true)
        nav.dialogState.value = DialogState.None
        advanceDeleteTime(400)
        open()
        advanceDeleteTime(600)
        assertEquals("abcdef", (nav.dialogState.value as KeyboardHost).keyboard.text)
        input.key(97, false)
        input.key(97, true)
        open()
        advanceDeleteTime(1000)
        assertEquals("abcdef", (nav.dialogState.value as KeyboardHost).keyboard.text)
        input.key(97, false)
        input.key(97, true)
        val beforeHelp = nav.dialogState.value
        input.onMenu()
        assertTrue(nav.dialogState.value is DialogState.KeyboardHelp)
        input.onBack()
        advanceDeleteTime(1000)
        assertEquals(beforeHelp, nav.dialogState.value)
        input.key(97, false)
        open()
        input.key(97, true)
        bindings = dev.piannoli.ui.MenuAction.swap(bindings, dev.piannoli.ui.MenuAction.BACK, dev.piannoli.ui.MenuAction.SELECT)
        advanceDeleteTime(1000)
        assertEquals("abcde", (nav.dialogState.value as KeyboardHost).keyboard.text)
        input.key(97, false)
        input.key(109, true)
        var backs = 0
        input.wireToRegistry(screenResolver = {
            object : ScreenInputHandler { override fun onBack() { backs++ } }
        })
        advanceDeleteTime(1000)
        assertEquals(0, backs)
        input.key(109, false)
        input.key(97, true)
        advanceDeleteTime(1000)
        input.key(97, true, repeats = 1)
        input.key(97, false)
        assertEquals(1, backs)
    }

    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun `confirm hold only repeats selected backspace including number and hex keyboards`() {
        val input = keyboardDispatcher()
        nav.dialogState.value = DialogState.RenameInput(
            "number", keyboard = dev.piannoli.ui.components.KeyboardState(
                text = "12345", cursorPos = 3, layout = dev.piannoli.ui.components.KeyboardLayout.Number,
                keyRow = 3, keyCol = 0,
            ),
        )
        input.key(96, true)
        advanceDeleteTime(460)
        input.key(96, false)
        assertEquals("45", (nav.dialogState.value as KeyboardHost).keyboard.text)
        nav.dialogState.value = DialogState.RenameInput(
            "letters", keyboard = dev.piannoli.ui.components.KeyboardState(keyRow = 1, keyCol = 0),
        )
        input.key(96, true)
        input.key(96, true, repeats = 1)
        advanceDeleteTime(1000)
        input.key(96, false)
        assertEquals("q", (nav.dialogState.value as KeyboardHost).keyboard.text)
        nav.dialogState.value = DialogState.HexColorInput(
            settingKey = "test", title = "Hex", currentHex = "ABCDEF",
            selectedIndex = dev.piannoli.ui.components.HEX_KEYS.indexOf(dev.piannoli.ui.KEY_BACKSPACE),
        )
        input.key(96, true)
        advanceDeleteTime(460)
        input.key(96, false)
        assertEquals("ABC", (nav.dialogState.value as DialogState.HexColorInput).currentHex)
    }

    @Test fun `launcher keyboard cancel returns to captured navigation for initial and edited searches`() {
        every { games.cancelSearch(any()) } answers { firstArg<() -> Unit>().invoke() }
        for (global in listOf(false, true)) {
            for (submitted in listOf(false, true)) {
                for (systemKeyboard in listOf(false, true)) {
                    nav.screenStack.clear()
                    nav.push(LauncherScreen.SystemList)
                    if (!global) nav.push(LauncherScreen.GameList)
                    val origin = nav.screenStack.toList()
                    nav.beginLauncherSearch()
                    if (global && submitted) nav.push(LauncherScreen.GameList)
                    // Reopening the keyboard must not replace the original navigation snapshot.
                    if (submitted) assertFalse(nav.beginLauncherSearch())
                    every { settings.keyboardType } returns if (systemKeyboard)
                        dev.piannoli.scorza.settings.KeyboardType.SYSTEM
                    else dev.piannoli.scorza.settings.KeyboardType.PIANNOLI
                    nav.dialogState.value = DialogState.RenameInput(
                        if (global) "launcher_global_search" else "launcher_search",
                        keyboard = dev.piannoli.ui.components.KeyboardState(text = "Mario"),
                    )
                    if (systemKeyboard) handler.onBack() else handler.onWest()
                    assertEquals(origin, nav.screenStack.toList())
                    assertEquals(DialogState.None, nav.dialogState.value)
                    assertFalse(nav.navigating)
                }
            }
        }
        verify(exactly = 8) { games.cancelSearch(any()) }
    }

    @Test fun `pending global keyboard can be cancelled while navigation is busy`() {
        every { games.cancelSearch(any()) } answers { firstArg<() -> Unit>().invoke() }
        nav.beginLauncherSearch()
        nav.navigating = true
        nav.dialogState.value = DialogState.RenameInput(
            "launcher_global_search", keyboard = dev.piannoli.ui.components.KeyboardState(text = "Mario"),
        )
        handler.onWest()
        verify { games.cancelSearch(any()) }
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
        assertFalse(nav.navigating)
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `network info cycles interfaces and wraps in both directions`() {
        val addresses = listOf("192.168.1.10", "10.0.0.2")
        nav.dialogState.value = DialogState.QuickInfo(addresses)

        handler.onLeft()
        assertEquals(DialogState.QuickInfo(addresses, selectedIndex = 1), nav.dialogState.value)
        handler.onRight()
        assertEquals(DialogState.QuickInfo(addresses), nav.dialogState.value)
        handler.onRight()
        assertEquals(DialogState.QuickInfo(addresses, selectedIndex = 1), nav.dialogState.value)
        handler.onRight()
        assertEquals(DialogState.QuickInfo(addresses), nav.dialogState.value)
    }

    @Test fun `offline network info ignores interface navigation`() {
        val info = DialogState.QuickInfo(emptyList())
        nav.dialogState.value = info

        handler.onLeft()
        handler.onRight()

        assertEquals(info, nav.dialogState.value)
    }

    @Test fun `ports offer individual scraping while tools do not and selected port is dispatched`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val port = dev.piannoli.scorza.model.App(1, dev.piannoli.scorza.model.AppType.PORT, "Game", "dev.game")
        val item = dev.piannoli.scorza.model.ListItem.AppItem(port)
        val state = dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "ports")
        val action = context.getString(dev.piannoli.scorza.R.string.steamgrid_action)
        assertTrue(action in handler.buildGameContextOptions(item, state))
        assertFalse(action in handler.buildGameContextOptions(
            dev.piannoli.scorza.model.ListItem.AppItem(port.copy(type = dev.piannoli.scorza.model.AppType.TOOL)), state))
        every { games.getSelectedItem() } returns item
        every { games.state } returns kotlinx.coroutines.flow.MutableStateFlow(state)
        nav.push(LauncherScreen.GameList)
        nav.dialogState.value = DialogState.ContextMenu("Game", options = listOf(action))
        handler.onConfirm()
        verify(exactly = 1) { scraper.openPort(port) }
    }

    @Test fun `tool menus pin instead of favorite while ports still use Favorites`() {
        val state = dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "tools")
        val tool = dev.piannoli.scorza.model.App(7, dev.piannoli.scorza.model.AppType.TOOL, "Files", "dev.files")
        val options = handler.buildGameContextOptions(dev.piannoli.scorza.model.ListItem.AppItem(tool), state)
        assertTrue(MENU_PIN_TOOL in options)
        assertFalse(MENU_ADD_FAVORITE in options || MENU_REMOVE_FAVORITE in options)
        assertFalse(MENU_MANAGE_COLLECTIONS in options || MENU_RENAME in options)
        val pinned = handler.buildGameContextOptions(
            dev.piannoli.scorza.model.ListItem.AppItem(tool.copy(isPinned = true)), state)
        assertTrue(MENU_UNPIN_TOOL in pinned)
        assertFalse(MENU_PIN_TOOL in pinned)
        val port = handler.buildGameContextOptions(
            dev.piannoli.scorza.model.ListItem.AppItem(tool.copy(type = dev.piannoli.scorza.model.AppType.PORT)),
            state.copy(platformTag = "ports"))
        assertTrue(MENU_ADD_FAVORITE in port)
        assertFalse(MENU_PIN_TOOL in port)
        assertTrue(MENU_MANAGE_COLLECTIONS in port && MENU_RENAME in port)
    }

    @Test fun `local artwork follows scrape action and is available for folders`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val rom = dev.piannoli.scorza.model.Rom(
            3, java.io.File("Roms/SNES/Adventure.sfc"), "SNES", "Adventure",
        )
        val options = handler.buildGameContextOptions(
            dev.piannoli.scorza.model.ListItem.RomItem(rom),
            dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "SNES"),
        )
        val scrape = context.getString(dev.piannoli.scorza.R.string.steamgrid_action)
        val choose = context.getString(dev.piannoli.scorza.R.string.choose_artwork)
        assertEquals("Scrape Game", scrape)
        assertEquals(choose, options[options.indexOf(scrape) + 1])
        assertTrue(MENU_HIDE_GAME in options)
        assertEquals(
            listOf(MENU_UNHIDE_GAME),
            handler.buildGameContextOptions(
                dev.piannoli.scorza.model.ListItem.RomItem(rom),
                dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(
                    platformTag = "hidden_games", isHiddenGames = true,
                ),
            ),
        )

        val folderOptions = handler.buildGameContextOptions(
            dev.piannoli.scorza.model.ListItem.SubfolderItem("Adventure", "RPGs/Adventure"),
            dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "SNES"),
        )
        assertEquals(context.getString(dev.piannoli.scorza.R.string.choose_folder_artwork), folderOptions.first())
        assertTrue(MENU_RENAME in folderOptions)
        val port = dev.piannoli.scorza.model.App(4, dev.piannoli.scorza.model.AppType.PORT, "Game", "dev.game")
        assertTrue(MENU_HIDE_GAME in handler.buildGameContextOptions(
            dev.piannoli.scorza.model.ListItem.AppItem(port),
            dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "ports"),
        ))
        val tool = port.copy(type = dev.piannoli.scorza.model.AppType.TOOL)
        assertFalse(MENU_HIDE_GAME in handler.buildGameContextOptions(
            dev.piannoli.scorza.model.ListItem.AppItem(tool),
            dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "tools"),
        ))
    }

    @Test fun `hide and restore context actions update persistent local game state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val rom = dev.piannoli.scorza.model.Rom(
                21, java.io.File("Roms/SNES/Hidden.sfc"), "SNES", "Hidden",
            )
            val item = dev.piannoli.scorza.model.ListItem.RomItem(rom)
            every { games.getSelectedItem() } returns item
            every { games.state } returns kotlinx.coroutines.flow.MutableStateFlow(
                dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "SNES"),
            )
            nav.push(LauncherScreen.GameList)
            nav.dialogState.value = DialogState.ContextMenu("Hidden", options = listOf(MENU_HIDE_GAME))

            handler.onConfirm()
            runCurrent()

            verify(exactly = 1) { roms.setHidden(21, true) }
            verify(exactly = 1) { games.reload(any()) }
            verify(exactly = 1) { launcherActions.rescanSystemList() }

            every { games.state } returns kotlinx.coroutines.flow.MutableStateFlow(
                dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(
                    platformTag = "hidden_games", isHiddenGames = true,
                ),
            )
            nav.dialogState.value = DialogState.ContextMenu("Hidden", options = listOf(MENU_UNHIDE_GAME))
            handler.onConfirm()
            runCurrent()

            verify(exactly = 1) { roms.setHidden(21, false) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `tool pin action reloads list without favoriting and reports pin limit`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val tool = dev.piannoli.scorza.model.App(7, dev.piannoli.scorza.model.AppType.TOOL, "Files", "dev.files")
            every { games.getSelectedItem() } returns dev.piannoli.scorza.model.ListItem.AppItem(tool)
            every { games.state } returns kotlinx.coroutines.flow.MutableStateFlow(
                dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "tools"))
            every { apps.setToolsPinned(listOf(7), true) } returns true
            nav.push(LauncherScreen.GameList)
            nav.dialogState.value = DialogState.ContextMenu("Files", options = listOf(MENU_PIN_TOOL))
            handler.onConfirm()
            runCurrent()
            verify(exactly = 1) { apps.setToolsPinned(listOf(7), true) }
            verify(exactly = 1) { games.reload(any()) }
            verify(exactly = 0) { games.toggleFavorite(any()) }
            every { apps.setToolsPinned(listOf(7), true) } returns false
            nav.dialogState.value = DialogState.ContextMenu("Files", options = listOf(MENU_PIN_TOOL))
            handler.onConfirm()
            runCurrent()
            assertTrue(nav.dialogState.value is DialogState.LaunchError)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `ports platform dispatches both game and system scraping`() {
        every { systems.getSelectedPlatformTag() } returns "PORTS"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        nav.dialogState.value = DialogState.ContextMenu("Ports", options = listOf(
            context.getString(dev.piannoli.scorza.R.string.bulk_platform_games, "Ports")))
        handler.onConfirm()
        verify(exactly = 1) { bulkScraper.open("PORTS", any()) }
        nav.dialogState.value = DialogState.ContextMenu("Ports", options = listOf(
            context.getString(dev.piannoli.scorza.R.string.scrape_system)))
        handler.onConfirm()
        verify(exactly = 1) { scraper.openSystem("PORTS", "Ports") }
    }

    @Test fun `scrape systems quick menu action passes available platforms to bulk scraping`() {
        val available = listOf(dev.piannoli.scorza.model.Platform("GB", "Game Boy", null))
        every { systems.state } returns kotlinx.coroutines.flow.MutableStateFlow(
            dev.piannoli.scorza.ui.viewmodel.SystemListViewModel.State(platforms = available))
        nav.dialogState.value = DialogState.QuickMenu(rows = listOf(QuickMenuRow.SCRAPE_SYSTEMS))
        handler.onConfirm()
        verify(exactly = 1) { bulkScraper.openSystems(available, any()) }
        verify(exactly = 0) { bulkScraper.open(any(), any()) }
    }

    @Test fun `platform delete art uses selected type closes menu and reports storage errors`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val type = dev.piannoli.scorza.settings.SystemArtworkType.ICON
            every { settings.systemArtworkType } returns type
            every { systems.getSelectedItem() } returns
                dev.piannoli.scorza.ui.viewmodel.SystemListViewModel.ListItem.PortsFolder("Ports", 1)
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

    @Test fun `choose artwork captures category identity and current artwork type`() {
        val type = dev.piannoli.scorza.settings.SystemArtworkType.GRID
        every { settings.systemArtworkType } returns type
        every { systems.getSelectedItem() } returns
            dev.piannoli.scorza.ui.viewmodel.SystemListViewModel.ListItem.FavoritesItem
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        nav.dialogState.value = DialogState.ContextMenu("Favorites",
            options = listOf(context.getString(dev.piannoli.scorza.R.string.choose_artwork)))
        handler.onConfirm()
        verify(exactly = 1) { activityActions.chooseSystemArtwork("@favorites", type) }
        verify(exactly = 0) { scraper.openSystem(any(), any()) }
    }

    @Test fun `folder menu explains sharing and dispatches shared deletion using original platform`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder = dev.piannoli.scorza.model.ListItem.SubfolderItem(
            "Adventure", "RPGs/Adventure", artworkPlatformTag = "SNES",
        )
        val state = dev.piannoli.scorza.ui.viewmodel.GameListViewModel.State(platformTag = "GB")
        every { games.getSelectedItem() } returns folder
        every { games.state } returns kotlinx.coroutines.flow.MutableStateFlow(state)
        every { artwork.hasSharedFolderArtwork("SNES", folder.path) } returns true
        val options = handler.buildGameContextOptions(folder, state)
        val choose = context.getString(dev.piannoli.scorza.R.string.choose_folder_artwork)
        val deleteShared = context.getString(dev.piannoli.scorza.R.string.delete_shared_folder_artwork)
        assertTrue(choose in options && deleteShared in options)
        assertFalse(MENU_DELETE_ART in options)
        nav.push(LauncherScreen.GameList)
        nav.dialogState.value = DialogState.ContextMenu("Adventure", options = listOf(choose))
        handler.onConfirm()
        verify(exactly = 1) { activityActions.chooseFolderArtwork("SNES", folder.path) }
        nav.dialogState.value = DialogState.ContextMenu("Adventure", options = listOf(deleteShared))
        handler.onConfirm()
        verify(exactly = 1) { artwork.deleteFolder("SNES", folder.path) }
        verify(exactly = 1) { games.reload(any()) }

        every { artwork.hasSharedFolderArtwork("SNES", folder.path) } returns false
        every { artwork.findFolder("SNES", folder.path) } returns java.io.File("legacy.png")
        val individual = handler.buildGameContextOptions(folder, state)
        assertTrue(MENU_DELETE_ART in individual)
        assertFalse(deleteShared in individual)
    }

    private fun conflictsMenu() = DialogState.ConflictsMenu(
        rows = listOf(ConflictRow(gameKey = "snes/Zelda.sfc", name = "Zelda"))
    )

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
            options = listOf(context.getString(dev.piannoli.scorza.R.string.scrape_system)),
        )
        handler.onConfirm()
        verify(exactly = 1) { scraper.openSystem("GB", "Game Boy") }
        verify(exactly = 0) { bulkScraper.open(any(), any()) }
    }

    @Test fun `keyboard shoulders move caret one position and clamp at text boundaries`() {
        every { settings.keyboardType } returns dev.piannoli.scorza.settings.KeyboardType.PIANNOLI
        nav.dialogState.value = DialogState.RenameInput(
            gameName = "Game",
            keyboard = dev.piannoli.ui.components.KeyboardState(text = "Game", cursorPos = 2),
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
        nav.dialogState.value = DialogState.QuickMenu(rows = listOf(QuickMenuRow.SCRAPING))
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
        every { settings.keyboardType } returns dev.piannoli.scorza.settings.KeyboardType.SYSTEM
        nav.push(LauncherScreen.Settings)
        val input = DialogState.RenameInput(
            "title", keyboard = dev.piannoli.ui.components.KeyboardState(text = "Unsaved title"),
        )
        nav.dialogState.value = input
        handler.onBack()
        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
        nav.dialogState.value = DialogState.ScraperInput(dev.piannoli.scorza.scraper.ScraperField.API_KEY)
        handler.onBack()
        verify(exactly = 1) { scraper.back() }
    }

    @Test fun piannoli_keyboard_back_deletes_and_west_cancels_including_scraper_inputs() {
        every { settings.keyboardType } returns dev.piannoli.scorza.settings.KeyboardType.PIANNOLI
        nav.push(LauncherScreen.Settings)
        val keyboard = dev.piannoli.ui.components.KeyboardState(text = "abc", cursorPos = 3)
        for (input in listOf(
            DialogState.RenameInput("title", keyboard = keyboard),
            DialogState.ScraperInput(dev.piannoli.scorza.scraper.ScraperField.API_KEY, keyboard),
        )) {
            nav.dialogState.value = input
            handler.onBack()
            val edited = nav.dialogState.value as dev.piannoli.scorza.ui.screens.KeyboardHost
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
        every { settings.keyboardType } returns dev.piannoli.scorza.settings.KeyboardType.SYSTEM
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

    @Test fun launcher_feedback_reaches_sounds_and_haptics_but_not_the_input_tester() {
        val sounds = mockk<dev.piannoli.scorza.util.MenuSounds>(relaxed = true)
        val haptics = mockk<dev.piannoli.scorza.util.MenuHaptics>(relaxed = true)
        val input = mockk<dev.piannoli.scorza.input.runtime.InputDispatcher>()
        val feedback = io.mockk.slot<(dev.piannoli.scorza.util.MenuSound) -> Unit>()
        every { input.wireToRegistry(any(), any(), any(), capture(feedback), any(), any()) } returns Unit
        router(sounds, haptics).wire(input)
        nav.push(LauncherScreen.Settings)
        for (action in dev.piannoli.scorza.util.MenuSound.entries) feedback.captured(action)
        nav.push(LauncherScreen.InputTester)
        for (action in dev.piannoli.scorza.util.MenuSound.entries) feedback.captured(action)
        for (action in dev.piannoli.scorza.util.MenuSound.entries) {
            verify(exactly = 1) { sounds.play(action) }
            verify(exactly = 1) { haptics.play(action) }
        }
    }

    private fun router(
        menuSounds: dev.piannoli.scorza.util.MenuSounds = mockk(),
        menuHaptics: dev.piannoli.scorza.util.MenuHaptics = mockk(),
    ): InputRouter {
        val factory = mockk<ScrollListInputHandler.Factory>()
        every { factory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            ScrollListInputHandler(nav, arg(0), arg(1), arg(2), arg(3), arg(4), arg(5),
                arg(6), arg(7), arg(8), arg(9), arg(10), arg(11))
        }
        return InputRouter(
            nav = nav, dialogHandler = handler, systemListHandler = mockk(), gameListHandler = mockk(relaxed = true),
            settingsHandler = mockk(), onboardingHandler = mockk(), inputTesterHandler = mockk(),
            saveStatePickerHandler = mockk(), saveSlotsHandler = mockk(relaxed = true), guideHandler = mockk(),
            controllerDetailHandler = mockk(), controllersHandler = mockk(), editButtonsHandler = mockk(),
            loggingSettingsHandler = mockk(), scrollListFactory = factory, platformConfig = mockk(),
            gameOverrideStore = mockk(), installedCoreService = mockk(), emulatorMappingBuilder = mockk(),
            launcherActions = launcherActions,
            screenInputRegistry = mockk(), context = ApplicationProvider.getApplicationContext(),
            settings = mockk(), coreInstaller = mockk(), rommBrowseViewModel = mockk(),
            rommDownloader = mockk(relaxed = true), osdController = mockk(), raPreloadController = mockk(),
            ioScope = CoroutineScope(dispatcher), menuSounds = menuSounds, menuHaptics = menuHaptics,
        )
    }

    @Test fun back_from_romm_library_returns_to_quick_menu_without_leaving_underlying_screen() {
        nav.push(LauncherScreen.Settings)
        val origin = DialogState.QuickMenu(rows = listOf(QuickMenuRow.ROMM))
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
