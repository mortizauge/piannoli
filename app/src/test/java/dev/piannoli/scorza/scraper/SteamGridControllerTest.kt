package dev.piannoli.scorza.scraper

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.R
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.di.AppFonts
import dev.piannoli.scorza.input.screen.SettingsInputHandler
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.ui.components.KeyboardState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SteamGridControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val steam = mockk<SteamGridDbClient>()
    private val screen = mockk<ScraperProvider>()
    private val store = mockk<ScrapedArtworkStore>()
    private val games = mockk<GameListViewModel>(relaxed = true)
    private val nav = NavigationController()
    private lateinit var context: Context
    private lateinit var controller: ScraperController
    private lateinit var settings: SettingsRepository
    private val rom = Rom(1, File("Game.sfc"), "SNES", "Game")
    private val match = SteamGridGame(10, "Matched game")
    private val cover = SteamGridCover(20, "Artist", 600, 900,
        "https://cdn2.steamgriddb.com/grid/20.png", "https://cdn2.steamgriddb.com/thumb/20.png")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        every { steam.search("personal-key", "Game") } returns listOf(match)
        every { steam.covers("personal-key", 10, 0) } returns SteamGridCovers(listOf(cover), false)
        settings = SettingsRepository(context)
        controller = ScraperController(context, scope, nav, screen, store, games, steam, settings)
    }

    @After fun cleanup() {
        settings.shutdown()
        Dispatchers.resetMain()
    }

    private fun click(label: Int) {
        val menu = nav.dialogState.value as DialogState.ScraperMenu
        val index = menu.options.indexOfFirst { it.substringBefore('\t') == context.getString(label) }
        check(index >= 0)
        controller.confirm(menu.copy(selectedIndex = index))
    }

    @Test fun `individual port uses same search cover and overwrite flow as games`() = scope.runTest {
        val port = dev.piannoli.scorza.model.App(1, dev.piannoli.scorza.model.AppType.PORT,
            "Game", "dev.game", artFile = File("Existing.png"))
        settings.steamGridApiKey = "personal-key"
        val bytes = byteArrayOf(1, 2)
        every { steam.download(cover) } returns bytes
        every { store.installPort(port, bytes, true) } returns File("Game.png")
        controller.openPort(port)
        assertEquals(DialogState.ScraperOverwriteConfirm, nav.dialogState.value)
        controller.confirmOverwrite()
        runCurrent()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        verify(exactly = 1) { store.installPort(port, bytes, true) }
        verify { games.reload(any()) }
        assertEquals(context.getString(R.string.scraper_saved), (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `system scraping browses selected resource previews and pages before saving choice`() = scope.runTest {
        settings.steamGridApiKey = "personal-key"
        val type = dev.piannoli.scorza.settings.SystemArtworkType.ICON
        settings.systemArtworkType = type
        val bytes = byteArrayOf(1, 2)
        val second = cover.copy(id = 21, thumbnail = "https://cdn2.steamgriddb.com/thumb/second.png")
        every { steam.search("personal-key", "Game Boy") } returns listOf(match)
        every { steam.systemArtwork("personal-key", 10, type, 0) } returns SteamGridCovers(listOf(cover), true)
        every { steam.systemArtwork("personal-key", 10, type, 1) } returns SteamGridCovers(listOf(second), false)
        every { steam.download(second) } returns bytes
        every { store.installSystem("GB", type, bytes) } returns File("gb_icon.png")
        controller.openSystem("GB", "Game Boy")
        runCurrent()
        settings.systemArtworkType = dev.piannoli.scorza.settings.SystemArtworkType.GRID
        verify(exactly = 0) { steam.download(any()); store.installSystem(any(), any(), any()) }
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        assertEquals(cover.thumbnail, (nav.dialogState.value as DialogState.ScraperMenu).previews.first())
        click(R.string.steamgrid_next_page)
        runCurrent()
        assertEquals(second.thumbnail, (nav.dialogState.value as DialogState.ScraperMenu).previews.first())
        controller.back()
        assertEquals(cover.thumbnail, (nav.dialogState.value as DialogState.ScraperMenu).previews.first())
        click(R.string.steamgrid_next_page)
        runCurrent()
        controller.confirm((nav.dialogState.value as DialogState.ScraperMenu).copy(selectedIndex = 0))
        runCurrent()
        verify(exactly = 1) { store.installSystem("GB", type, bytes) }
        verify(exactly = 0) { steam.covers(any(), any(), any()); screen.systems(any()); games.reload(any()) }
        assertEquals(context.getString(R.string.scraper_saved), (nav.dialogState.value as DialogState.ScraperStatus).message)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `cancelled system download cannot install artwork or reopen a dialog`() = scope.runTest {
        settings.steamGridApiKey = "personal-key"
        every { steam.systemArtwork("personal-key", 10, any(), 0) } returns SteamGridCovers(listOf(cover), false)
        every { steam.download(cover) } answers {
            controller.back()
            byteArrayOf(1)
        }
        controller.openSystem("GB", "Game")
        runCurrent()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        val picker = nav.dialogState.value
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        assertEquals(picker, nav.dialogState.value)
        verify(exactly = 0) { store.installSystem(any(), any(), any()) }
    }

    @Test fun `system scraping requires a SteamGridDB key`() = scope.runTest {
        settings.steamGridApiKey = ""
        controller.openSystem("PORTS", "Ports")
        runCurrent()
        assertEquals(context.getString(R.string.steamgrid_key_required),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { steam.search(any(), any()); store.installSystem(any(), any(), any()) }
    }

    private fun search() {
        controller.openSteamGridSetup()
        controller.submit(DialogState.ScraperInput(ScraperField.API_KEY, KeyboardState(text = "personal-key")))
        controller.open(rom)
        scope.runCurrent()
    }

    @Test fun `back restores cover pages results and originating game menu with their selections`() = scope.runTest {
        settings.steamGridApiKey = "personal-key"
        val origin = DialogState.ContextMenu("Game", 1, listOf("Rename", "Scrape"))
        nav.dialogState.value = origin
        every { steam.covers("personal-key", 10, 0) } returns SteamGridCovers(listOf(cover), true)
        every { steam.covers("personal-key", 10, 1) } returns SteamGridCovers(listOf(cover.copy(id = 21)), false)
        controller.open(rom)
        runCurrent()
        val results = nav.dialogState.value
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        click(R.string.steamgrid_next_page)
        runCurrent()
        controller.back()
        val firstPage = nav.dialogState.value as DialogState.ScraperMenu
        assertEquals(cover.thumbnail, firstPage.previews.first())
        assertEquals(context.getString(R.string.steamgrid_next_page), firstPage.options[firstPage.selectedIndex])
        controller.back()
        assertEquals(results, nav.dialogState.value)
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        assertEquals(match.name, (nav.dialogState.value as DialogState.ScraperMenu).title)
        controller.back()
        controller.back()
        assertEquals(origin, nav.dialogState.value)
        verify(exactly = 1) { steam.search(any(), any()) }
        verify(exactly = 0) { steam.download(any()) }
    }

    @Test fun `back from download error restores cover picker without repeating request`() = scope.runTest {
        search()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        val picker = nav.dialogState.value
        every { steam.download(cover) } throws ScraperException(ScraperError.NETWORK)
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        controller.back()
        assertEquals(picker, nav.dialogState.value)
        controller.back()
        assertEquals(context.getString(R.string.steamgrid_results), (nav.dialogState.value as DialogState.ScraperMenu).title)
        verify(exactly = 1) { steam.download(cover) }
    }

    @Test fun `back from saved message returns to list and cancels its pending auto dismissal`() = scope.runTest {
        search()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        every { steam.download(cover) } returns byteArrayOf(1)
        every { store.install(rom, any()) } returns File("Game.png")
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        controller.back()
        assertEquals(DialogState.None, nav.dialogState.value)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `SteamGridDB is default and needs no ScreenScraper credentials`() = scope.runTest {
        search()
        verify(exactly = 1) { steam.search("personal-key", "Game") }
        verify(exactly = 0) { screen.configured }
        val options = (nav.dialogState.value as DialogState.ScraperMenu).options
        assertFalse(options.any { it.contains("personal-key") })
        assertFalse(options.any { it.contains(context.getString(R.string.steamgrid_api_key)) })
    }

    @Test fun `back from results skips accepted overwrite confirmation`() = scope.runTest {
        settings.steamGridApiKey = "personal-key"
        controller.open(rom.copy(artFile = File("Existing.jpg")))
        controller.confirmOverwrite()
        runCurrent()
        assertTrue(nav.dialogState.value is DialogState.ScraperMenu)
        controller.back()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `existing artwork requires consent and overwrite permission resets for each game`() = scope.runTest {
        settings.steamGridApiKey = "personal-key"
        val existing = rom.copy(artFile = File("Existing.jpg"))
        controller.open(existing)
        assertEquals(DialogState.ScraperOverwriteConfirm, nav.dialogState.value)
        verify(exactly = 0) { steam.search(any(), any()) }
        controller.back()
        assertEquals(DialogState.None, nav.dialogState.value)
        controller.open(existing)
        controller.confirmOverwrite()
        runCurrent()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        val bytes = byteArrayOf(1, 2, 3)
        every { steam.download(cover) } returns bytes
        every { store.install(existing, bytes, true) } returns File("Game.png")
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        verify(exactly = 1) { store.install(existing, bytes, true) }
        controller.open(existing)
        assertEquals(DialogState.ScraperOverwriteConfirm, nav.dialogState.value)
    }

    @Test fun `failed replacement download does not touch existing artwork`() = scope.runTest {
        settings.steamGridApiKey = "personal-key"
        controller.open(rom.copy(artFile = File("Existing.jpg")))
        controller.confirmOverwrite()
        runCurrent()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        every { steam.download(cover) } throws ScraperException(ScraperError.NETWORK)
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        verify(exactly = 0) { store.install(any(), any(), any()) }
        assertEquals(context.getString(R.string.steamgrid_network), (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `empty game search retries cleaned query but still requires manual selection`() = scope.runTest {
        settings.steamGridApiKey = "personal-key"
        every { steam.search("personal-key", "Game!") } returns emptyList()
        controller.open(rom.copy(displayName = "Game!"))
        runCurrent()
        verify(exactly = 1) { steam.search("personal-key", "Game!"); steam.search("personal-key", "Game") }
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).options.first().contains(match.name))
        verify(exactly = 0) { steam.covers(any(), any(), any()); store.install(any(), any()) }
    }

    @Test fun `selecting a cover installs immediately and saved message closes after three seconds`() = scope.runTest {
        search()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        val picker = nav.dialogState.value as DialogState.ScraperMenu
        assertEquals(cover.thumbnail, picker.previews.first())
        assertTrue(picker.options.first().contains("Artist"))
        verify(exactly = 0) { steam.download(any()) }
        val bytes = byteArrayOf(1, 2, 3)
        every { steam.download(cover) } returns bytes
        every { store.install(rom, bytes) } returns File("Game.png")
        controller.confirm(picker)
        assertTrue((nav.dialogState.value as DialogState.ScraperStatus).busy)
        runCurrent()
        verify(exactly = 1) { store.install(rom, bytes) }
        verify(exactly = 1) { games.reload(any()) }
        assertEquals(context.getString(R.string.scraper_saved), (nav.dialogState.value as DialogState.ScraperStatus).message)
        advanceTimeBy(2_999)
        runCurrent()
        assertTrue(nav.dialogState.value is DialogState.ScraperStatus)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `cover pagination preserves game identity`() = scope.runTest {
        every { steam.covers("personal-key", 10, 0) } returns SteamGridCovers(listOf(cover), true)
        every { steam.covers("personal-key", 10, 1) } returns SteamGridCovers(listOf(cover.copy(id = 21)), false)
        search()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        click(R.string.steamgrid_next_page)
        runCurrent()
        click(R.string.steamgrid_previous_page)
        runCurrent()
        verify(exactly = 2) { steam.covers("personal-key", 10, 0) }
        verify(exactly = 1) { steam.covers("personal-key", 10, 1) }
    }

    @Test fun `forget key clears it before the next search`() = scope.runTest {
        search()
        controller.openSteamGridSetup()
        click(R.string.steamgrid_forget_key)
        assertEquals("", settings.steamGridApiKey)
        controller.open(rom)
        runCurrent()
        assertEquals(context.getString(R.string.steamgrid_key_required), (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 1) { steam.search(any(), any()) }
    }

    @Test fun `browser shortcut opens exact key page and leaves key editor ready on return`() {
        controller.openSteamGridSetup()
        click(R.string.steamgrid_open_api_page)
        val intent = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.steamgriddb.com/profile/preferences/api", intent.dataString)
        assertEquals(ScraperField.API_KEY, (nav.dialogState.value as DialogState.ScraperInput).field)
    }

    @Test fun `clipboard key is reviewed before saving and used immediately from game action`() = scope.runTest {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("key", "  personal-key\n"))
        controller.openSteamGridSetup()
        click(R.string.steamgrid_paste_key)
        val input = nav.dialogState.value as DialogState.ScraperInput
        assertEquals(ScraperField.API_KEY, input.field)
        assertEquals("personal-key", input.currentName)
        assertEquals(input.currentName.length, input.cursorPos)
        verify(exactly = 0) { steam.search(any(), any()) }
        assertEquals("", settings.steamGridApiKey)
        controller.submit(input)
        assertEquals("personal-key", settings.steamGridApiKey)
        controller.open(rom)
        runCurrent()
        verify { steam.search("personal-key", "Game") }
    }

    @Test fun `empty clipboard reports an error without replacing existing key`() = scope.runTest {
        search()
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        controller.openSteamGridSetup()
        click(R.string.steamgrid_paste_key)
        assertEquals(context.getString(R.string.steamgrid_clipboard_empty),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        controller.back()
        assertEquals(context.getString(R.string.steamgrid_setup), (nav.dialogState.value as DialogState.ScraperMenu).title)
        controller.open(rom)
        runCurrent()
        verify(exactly = 2) { steam.search("personal-key", "Game") }
    }

    @Test fun `missing browser reports a usable error`() {
        val noBrowserContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException()
            }
        }
        controller = ScraperController(noBrowserContext, scope, nav, screen, store, games, steam, settings)
        controller.openSteamGridSetup()
        click(R.string.steamgrid_open_api_page)
        assertEquals(context.getString(R.string.steamgrid_browser_unavailable),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `cancelled download does not install artwork or reopen a dialog`() = scope.runTest {
        search()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        every { steam.download(cover) } answers {
            controller.back()
            byteArrayOf(1, 2, 3)
        }
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        verify(exactly = 0) { store.install(any(), any()) }
        assertTrue(nav.dialogState.value is DialogState.ScraperMenu)
    }

    @Test fun `saved timeout leaves a newer dialog open`() = scope.runTest {
        search()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        val bytes = byteArrayOf(1, 2, 3)
        every { steam.download(cover) } returns bytes
        every { store.install(rom, bytes) } returns File("Game.png")
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        val newer = DialogState.About()
        nav.dialogState.value = newer
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(newer, nav.dialogState.value)
    }

    @Test fun `download failure remains visible after three seconds and does not install artwork`() = scope.runTest {
        search()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        every { steam.download(cover) } throws ScraperException(ScraperError.NETWORK)
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(context.getString(R.string.steamgrid_network), (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { store.install(any(), any()) }
    }

    @Test fun `new controller searches immediately using saved key without entering setup`() = scope.runTest {
        settings.steamGridApiKey = "personal-key"
        controller = ScraperController(context, scope, nav, screen, store, games, steam, settings)
        controller.open(rom)
        assertTrue((nav.dialogState.value as DialogState.ScraperStatus).busy)
        runCurrent()
        verify(exactly = 1) { steam.search("personal-key", "Game") }
        assertEquals(context.getString(R.string.steamgrid_results), (nav.dialogState.value as DialogState.ScraperMenu).title)
    }

    @Test fun `missing key directs user to settings without making a network request`() {
        controller.open(rom)
        assertEquals(context.getString(R.string.steamgrid_key_required), (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { steam.search(any(), any()) }
    }

    @Test fun `closing setup returns to underlying settings and cancelling key edits preserves saved key`() {
        settings.steamGridApiKey = "personal-key"
        controller.openSteamGridSetup()
        click(R.string.steamgrid_api_key)
        controller.back()
        assertEquals("personal-key", settings.steamGridApiKey)
        click(R.string.scraper_close)
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `artwork scraper setup preserves all parent menus and their selection on return`() {
        val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
        viewModel.load()
        val handler = SettingsInputHandler(
            nav = nav, ioScope = scope, settings = settings, platformConfig = mockk(),
            installedCoreService = mockk(), globalOverrides = mockk(), appsRepository = mockk(),
            setupCoordinator = mockk(), inputTesterController = mockk(), updateManager = mockk(),
            intentAuditor = mockk(), settingsViewModel = viewModel, launcherActions = mockk(relaxed = true),
            activityActions = mockk(), emulatorMappingBuilder = mockk(), context = context,
            rommStore = mockk(), piannoliPaths = mockk(), scraper = controller, artworkStore = mockk(),
        )
        viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "display" })
        handler.onConfirm()
        viewModel.selectItem("artwork")
        handler.onConfirm()
        assertEquals("artwork", viewModel.state.value.activeCategory)
        assertTrue(viewModel.state.value.items.map { it.key }.containsAll(listOf("game_artwork", "system_artwork")))
        viewModel.selectItem("system_artwork")
        handler.onConfirm()
        assertEquals("system_artwork", viewModel.state.value.activeCategory)
        assertTrue(viewModel.state.value.items.map { it.key }.containsAll(
            listOf("system_art_scale", "system_art_width", "system_art_radius", "system_artwork_type")))
        handler.onBack()
        assertEquals("artwork", viewModel.state.value.activeCategory)
        viewModel.selectItem("game_artwork")
        handler.onConfirm()
        assertEquals("game_artwork", viewModel.state.value.activeCategory)
        assertTrue(viewModel.state.value.items.map { it.key }.containsAll(listOf("art_scale", "art_width", "art_radius")))
        handler.onBack()
        viewModel.selectItem("setup_scrapers")
        val artworkSelection = viewModel.state.value.selectedIndex
        handler.onConfirm()
        assertEquals("setup_scrapers", viewModel.state.value.activeCategory)
        viewModel.selectItem("steamgrid_setup")
        handler.onConfirm()
        assertEquals(context.getString(R.string.steamgrid_setup), (nav.dialogState.value as DialogState.ScraperMenu).title)
        click(R.string.scraper_close)
        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals("setup_scrapers", viewModel.state.value.activeCategory)
        handler.onBack()
        assertEquals("artwork", viewModel.state.value.activeCategory)
        assertEquals(artworkSelection, viewModel.state.value.selectedIndex)
        assertEquals(R.string.settings_artwork, viewModel.state.value.activeCategoryLabel)
        handler.onBack()
        assertEquals("display", viewModel.state.value.activeCategory)
        assertEquals("artwork", viewModel.getSelectedItem()?.key)
        handler.onBack()
        assertFalse(viewModel.state.value.inSubList)
    }
}
