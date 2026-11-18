package dev.piannoli.scorza.scraper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.R
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.navigation.NavigationController
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScraperControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val provider = mockk<ScraperProvider>()
    private val store = mockk<ScrapedArtworkStore>()
    private val games = mockk<GameListViewModel>(relaxed = true)
    private val steam = mockk<SteamGridDbClient>()
    private val nav = NavigationController()
    private lateinit var context: Context
    private lateinit var controller: ScraperController
    private lateinit var settings: dev.piannoli.scorza.settings.SettingsRepository
    private val rom = Rom(1, File("Game.sfc"), "SNES", "Game")
    private val system = ScraperSystem(4, "Super Nintendo")
    private val match = ScraperMatch(10, "Matched game", system, ScraperArtwork("box-2D", "us"))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        every { provider.configured } returns true
        every { provider.systems(any()) } returns listOf(system)
        every { provider.search(any(), system, any(), any()) } returns listOf(match)
        every { provider.requestDelayMillis(any()) } returns 0L
        settings = dev.piannoli.scorza.settings.SettingsRepository(context)
        settings.scraperSource = dev.piannoli.scorza.settings.ScraperSource.SCREENSCRAPER
        controller = ScraperController(context, scope, nav, provider, store, games, steam, settings)
    }

    @After fun cleanup() { settings.shutdown(); Dispatchers.resetMain() }

    private fun click(label: Int) {
        val menu = nav.dialogState.value as DialogState.ScraperMenu
        val index = menu.options.indexOfFirst { it.substringBefore('\t') == context.getString(label) }
        check(index >= 0)
        controller.confirm(menu.copy(selectedIndex = index))
    }

    private fun enterAccount() {
        controller.submit(DialogState.ScraperInput(ScraperField.USERNAME, KeyboardState(text = "user")))
        controller.submit(DialogState.ScraperInput(ScraperField.PASSWORD, KeyboardState(text = "password")))
    }

    private fun search() {
        enterAccount()
        controller.open(rom)
        scope.runCurrent()
    }

    @Test fun `inferred platform results show previews and selection downloads without a confirmation screen`() = scope.runTest {
        search()
        verify(exactly = 0) { provider.download(any(), any()) }
        val results = nav.dialogState.value as DialogState.ScraperMenu
        assertTrue(results.options.first().contains("Matched game"))
        assertEquals(ScreenScraperPreviewFetcher.uri(match), results.previews.first())
        assertEquals(results.options.size, results.previews.size)
        verify(exactly = 1) { provider.search(any(), system, "Game", any()) }

        val bytes = byteArrayOf(1, 2, 3)
        every { provider.download(any(), match) } returns bytes
        every { store.install(rom, bytes) } returns File("Game.png")
        controller.confirm(results)
        runCurrent()
        verify(exactly = 1) { store.install(rom, bytes) }
        verify(exactly = 1) { games.reload(any()) }
        assertEquals(context.getString(R.string.scraper_saved), (nav.dialogState.value as DialogState.ScraperStatus).message)
        controller.back()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `back from failed download returns directly to matching games`() = scope.runTest {
        search()
        val results = nav.dialogState.value
        every { provider.download(any(), match) } throws ScraperException(ScraperError.NETWORK)
        controller.confirm(results as DialogState.ScraperMenu)
        runCurrent()
        assertTrue(nav.dialogState.value is DialogState.ScraperStatus)
        controller.back()
        assertEquals(results, nav.dialogState.value)
    }

    @Test fun `cancelled search cannot reopen results or save artwork`() = scope.runTest {
        every { provider.search(any(), system, any(), any()) } answers {
            controller.back()
            listOf(match)
        }
        search()
        val menu = nav.dialogState.value as DialogState.ScraperMenu
        assertEquals(context.getString(R.string.scraper_title, rom.displayName), menu.title)
        verify(exactly = 0) { provider.download(any(), any()) }
        verify(exactly = 0) { store.install(any(), any()) }
    }

    @Test fun `download cancelled before installation cannot save a cover`() = scope.runTest {
        search()
        every { provider.download(any(), match) } answers {
            controller.back()
            byteArrayOf(1, 2, 3)
        }
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        runCurrent()
        verify(exactly = 0) { store.install(any(), any()) }
        assertTrue(nav.dialogState.value is DialogState.ScraperMenu)
    }

    @Test fun `edit search retains inferred platform and permits a manual system override`() = scope.runTest {
        search()
        click(R.string.scraper_edit_search)
        val edit = nav.dialogState.value as DialogState.ScraperMenu
        assertTrue(edit.options.contains("${context.getString(R.string.scraper_system)}\t${system.name}"))
        val other = ScraperSystem(3, "NES")
        every { provider.systems(any()) } returns listOf(other, system)
        every { provider.search(any(), other, any(), any()) } returns listOf(match.copy(system = other))
        click(R.string.scraper_system)
        runCurrent()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        click(R.string.scraper_search)
        runCurrent()
        verify(exactly = 1) { provider.search(any(), other, "Game", any()) }
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).options.first().endsWith("\tNES"))
    }

    @Test fun `existing artwork prevents network activity and can be dismissed`() {
        controller.open(rom.copy(artFile = File("Existing.jpg")))
        assertEquals(DialogState.ScraperOverwriteConfirm, nav.dialogState.value)
        controller.back()
        assertEquals(DialogState.None, nav.dialogState.value)
        verify(exactly = 0) { provider.systems(any()) }
    }

    @Test fun `missing account produces a visible error instead of a provider call`() = scope.runTest {
        controller.open(rom)
        runCurrent()
        assertEquals(context.getString(R.string.scraper_account_required), (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { provider.systems(any()) }
    }

    @Test fun `ScreenScraper setup stores login and cycles only its media row in either direction`() = scope.runTest {
        controller.openScreenScraperSetup()
        click(R.string.scraper_username)
        controller.submit(DialogState.ScraperInput(ScraperField.USERNAME, KeyboardState(text = "user")))
        click(R.string.scraper_password)
        controller.submit(DialogState.ScraperInput(ScraperField.PASSWORD, KeyboardState(text = "password")))
        val setup = nav.dialogState.value as DialogState.ScraperMenu
        assertEquals(context.getString(R.string.screenscraper_media), setup.options.first().substringBefore('\t'))
        assertTrue(setup.options.none { it.contains("password") })
        val row = setup.copy(selectedIndex = setup.cycleIndices.single())
        controller.cycle(row, 1)
        assertEquals(dev.piannoli.scorza.settings.ScreenScraperMedia.SCREENSHOT, settings.screenScraperMedia)
        controller.cycle(row, -1)
        assertEquals(dev.piannoli.scorza.settings.ScreenScraperMedia.COVER, settings.screenScraperMedia)
        every { provider.authenticate(ScraperAccount("user", "password")) } returns Unit
        click(R.string.screenscraper_login)
        runCurrent()
        assertEquals(context.getString(R.string.screenscraper_logged_in),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 1) { provider.authenticate(ScraperAccount("user", "password")) }
    }

    @Test fun `incorrect login tells the user to correct their account credentials`() = scope.runTest {
        enterAccount()
        every { provider.authenticate(any()) } throws ScraperException(ScraperError.ACCOUNT_AUTHENTICATION)
        controller.openScreenScraperSetup()
        click(R.string.screenscraper_login)
        runCurrent()
        assertEquals(context.getString(R.string.screenscraper_incorrect_credentials),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `selected ScreenScraper rejects system artwork without using either service`() {
        controller.openSystem("SNES")
        assertEquals(context.getString(R.string.screenscraper_system_unsupported),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { provider.systems(any()) }
        verify(exactly = 0) { steam.search(any(), any()) }
    }

    @Test fun `single game uses selected ScreenScraper media and never SteamGridDB`() = scope.runTest {
        enterAccount()
        settings.screenScraperMedia = dev.piannoli.scorza.settings.ScreenScraperMedia.LOGO
        controller.open(rom)
        runCurrent()
        verify(exactly = 1) { provider.search(any(), system, "Game", dev.piannoli.scorza.settings.ScreenScraperMedia.LOGO) }
        verify(exactly = 0) { steam.search(any(), any()) }
    }

    @Test fun `unconfigured ScreenScraper login stays a visible error without claiming success`() = scope.runTest {
        enterAccount()
        every { provider.configured } returns false
        controller.openScreenScraperSetup()
        click(R.string.screenscraper_login)
        runCurrent()
        assertEquals(context.getString(R.string.scraper_not_configured),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { provider.authenticate(any()) }
    }

    @Test fun `cancelling while waiting for ScreenScraper quota prevents search and artwork writes`() = scope.runTest {
        enterAccount()
        every { provider.requestDelayMillis(any()) } returns 60_000L
        controller.open(rom)
        runCurrent()
        controller.back()
        advanceUntilIdle()
        verify(exactly = 0) { provider.search(any(), any(), any(), any()) }
        verify(exactly = 0) { store.install(any(), any()) }
    }
}
