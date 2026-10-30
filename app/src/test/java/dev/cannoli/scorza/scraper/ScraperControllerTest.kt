package dev.cannoli.scorza.scraper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.R
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.ui.components.KeyboardState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    private val nav = NavigationController()
    private lateinit var context: Context
    private lateinit var controller: ScraperController
    private val rom = Rom(1, File("Game.sfc"), "SNES", "Game")
    private val system = ScraperSystem(4, "Super Nintendo")
    private val match = ScraperMatch(10, "Matched game", system, ScraperArtwork("box-2D", "us"))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        every { provider.configured } returns true
        every { provider.systems(any()) } returns listOf(system)
        every { provider.search(any(), system, any()) } returns listOf(match)
        controller = ScraperController(context, scope, nav, provider, store, games, mockk(), mockk(relaxed = true))
    }

    @After fun cleanup() { Dispatchers.resetMain() }

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
        controller.open(rom, useSteamGrid = false)
        enterAccount()
        click(R.string.scraper_system)
        scope.runCurrent()
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        click(R.string.scraper_search)
        scope.runCurrent()
    }

    @Test fun `search and match selection require explicit confirmation before downloading`() = scope.runTest {
        search()
        verify(exactly = 0) { provider.download(any(), any()) }
        val results = nav.dialogState.value as DialogState.ScraperMenu
        assertTrue(results.options.first().contains("Matched game"))
        controller.confirm(results)
        verify(exactly = 0) { provider.download(any(), any()) }

        val bytes = byteArrayOf(1, 2, 3)
        every { provider.download(any(), match) } returns bytes
        every { store.install(rom, bytes) } returns File("Game.png")
        click(R.string.scraper_download)
        runCurrent()
        verify(exactly = 1) { store.install(rom, bytes) }
        verify(exactly = 1) { games.reload(any()) }
        assertEquals(context.getString(R.string.scraper_saved), (nav.dialogState.value as DialogState.ScraperStatus).message)
        controller.back()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `back from failed download skips download confirmation`() = scope.runTest {
        search()
        val results = nav.dialogState.value
        controller.confirm(results as DialogState.ScraperMenu)
        every { provider.download(any(), match) } throws ScraperException(ScraperError.NETWORK)
        click(R.string.scraper_download)
        runCurrent()
        assertTrue(nav.dialogState.value is DialogState.ScraperStatus)
        controller.back()
        assertEquals(results, nav.dialogState.value)
    }

    @Test fun `cancelled search cannot reopen results or save artwork`() = scope.runTest {
        every { provider.search(any(), system, any()) } answers {
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
        controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)
        every { provider.download(any(), match) } answers {
            controller.back()
            byteArrayOf(1, 2, 3)
        }
        click(R.string.scraper_download)
        runCurrent()
        verify(exactly = 0) { store.install(any(), any()) }
        assertTrue(nav.dialogState.value is DialogState.ScraperMenu)
    }

    @Test fun `existing artwork prevents network activity and can be dismissed`() {
        controller.open(rom.copy(artFile = File("Existing.jpg")), useSteamGrid = false)
        assertEquals(DialogState.ScraperOverwriteConfirm, nav.dialogState.value)
        controller.back()
        assertEquals(DialogState.None, nav.dialogState.value)
        verify(exactly = 0) { provider.systems(any()) }
    }

    @Test fun `missing account produces a visible error instead of a provider call`() = scope.runTest {
        controller.open(rom, useSteamGrid = false)
        click(R.string.scraper_system)
        runCurrent()
        assertEquals(context.getString(R.string.scraper_account_required), (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { provider.systems(any()) }
    }
}
