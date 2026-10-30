package dev.piannoli.scorza.scraper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.R
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.RomsRepository
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.model.Platform
import dev.piannoli.scorza.settings.SystemArtworkType
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.settings.ScrapeFailBehavior
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class BulkScraperControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val nav = NavigationController()
    private val settings = mockk<SettingsRepository>()
    private val roms = mockk<RomsRepository>()
    private val platforms = mockk<PlatformConfig>()
    private val steam = mockk<SteamGridDbClient>()
    private val artwork = mockk<ScrapedArtworkStore>()
    private val games = mockk<GameListViewModel>(relaxed = true)
    private val apps = mockk<dev.piannoli.scorza.db.AppsRepository>()
    private lateinit var context: Context
    private lateinit var controller: BulkScraperController
    private val game = Rom(1, File("SNES/Game.sfc"), "SNES", "Game")
    private val match = SteamGridGame(10, "Game")
    private val cover = SteamGridCover(20, "Artist", 600, 900,
        "https://cdn2.steamgriddb.com/grid/20.png", "https://cdn2.steamgriddb.com/thumb/20.png")
    private val bytes = byteArrayOf(1, 2, 3)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        every { settings.steamGridApiKey } returns "key"
        every { settings.scrapeFailBehavior } returns ScrapeFailBehavior.DEFAULT
        every { roms.platformCounts() } returns linkedMapOf("SNES" to 1, "NES" to 1)
        every { platforms.getDisplayName(any()) } answers { firstArg() }
        every { roms.romsForScraping("SNES", any()) } returns listOf(game)
        every { roms.romsForScraping("NES", any()) } returns emptyList()
        every { apps.all(dev.piannoli.scorza.model.AppType.PORT) } returns emptyList()
        every { steam.search("key", "Game") } returns listOf(match)
        every { steam.covers("key", 10, 0) } returns SteamGridCovers(listOf(cover), false)
        every { steam.download(cover) } returns bytes
        every { artwork.install(any(), bytes) } returns File("Game.png")
        controller = BulkScraperController(context, scope, nav, settings, roms, platforms, steam, artwork, games, apps)
        controller.open {}
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    private fun click(label: Int) {
        val menu = nav.dialogState.value as DialogState.ScraperMenu
        val index = menu.options.indexOfFirst { it.substringBefore('\t') == context.getString(label) }
        check(index >= 0) { menu.options.toString() }
        val selected = menu.copy(selectedIndex = index)
        if (label in listOf(R.string.bulk_mode, R.string.bulk_subfolders, R.string.bulk_existing_artwork)) {
            controller.cycle(selected)
        } else {
            controller.confirm(selected)
        }
    }

    private fun selectFirst() = controller.confirm(nav.dialogState.value as DialogState.ScraperMenu)

    @Test fun `system bulk scrapes available platform display names and ports using selected resource type`() = scope.runTest {
        val type = SystemArtworkType.ICON
        every { settings.systemArtworkType } returns type
        every { settings.portsName } returns "My Ports"
        every { apps.all(dev.piannoli.scorza.model.AppType.PORT) } returns listOf(
            dev.piannoli.scorza.model.App(1, dev.piannoli.scorza.model.AppType.PORT, "Game", "dev.game"))
        every { artwork.systemArtworkFile(any(), type) } returns null
        every { steam.search("key", "My Console") } returns listOf(match.copy(name = "My Console"))
        every { steam.search("key", "My Ports") } returns listOf(match.copy(name = "My Ports"))
        every { steam.systemArtwork("key", 10, type, 0) } returns SteamGridCovers(listOf(cover), false)
        every { artwork.installSystem(any(), type, bytes) } returns File("system.png")
        val available = Platform("SNES", "My Console", null, tags = listOf("SNES", "SFC"))
        controller.openSystems(listOf(available, available)) {}
        assertEquals(context.getString(R.string.bulk_systems), (nav.dialogState.value as DialogState.ScraperMenu).title)
        click(R.string.bulk_start)
        advanceTimeBy(1_000)
        runCurrent()
        verify(exactly = 1) {
            artwork.installSystem("SNES", type, bytes)
            artwork.installSystem("PORTS", type, bytes)
        }
        verify(exactly = 0) {
            roms.platformCounts()
            roms.romsForScraping(any(), any())
            steam.covers(any(), any(), any())
            games.reload(any())
        }
        assertEquals(summary(context.getString(R.string.bulk_complete), 2, 0),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `system bulk excludes existing type artwork from totals and overwrite replaces it`() = scope.runTest {
        val type = SystemArtworkType.LOGO
        every { settings.systemArtworkType } returns type
        every { artwork.systemArtworkFile("SNES", type) } returns File("snes_logo.png")
        every { steam.search("key", "SNES") } returns listOf(match.copy(name = "SNES"))
        every { steam.systemArtwork("key", 10, type, 0) } returns SteamGridCovers(listOf(cover), false)
        every { artwork.installSystem("SNES", type, bytes) } returns File("snes_logo.png")
        val available = listOf(Platform("SNES", "SNES", null))
        controller.openSystems(available) {}
        click(R.string.bulk_start)
        runCurrent()
        assertEquals(summary(context.getString(R.string.bulk_complete), 0, 0),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { steam.search(any(), any()) }
        controller.openSystems(available) {}
        click(R.string.bulk_existing_artwork)
        click(R.string.bulk_start)
        advanceTimeBy(500)
        runCurrent()
        verify(exactly = 1) { artwork.installSystem("SNES", type, bytes) }
        assertEquals(summary(context.getString(R.string.bulk_complete), 1, 0),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `interactive systems browse pages preserve back navigation and skip systems`() = scope.runTest {
        val type = SystemArtworkType.GRID
        every { settings.systemArtworkType } returns type
        every { artwork.systemArtworkFile(any(), type) } returns null
        every { steam.search("key", "SNES") } returns listOf(match)
        every { steam.systemArtwork("key", 10, type, 0) } returns SteamGridCovers(listOf(cover), true)
        every { steam.systemArtwork("key", 10, type, 1) } returns SteamGridCovers(listOf(cover.copy(id = 21)), false)
        var closed = false
        controller.openSystems(listOf(Platform("SNES", "SNES", null))) { closed = true }
        click(R.string.bulk_mode)
        click(R.string.bulk_start)
        runCurrent()
        val matches = nav.dialogState.value as DialogState.ScraperMenu
        assertEquals(context.getString(R.string.bulk_system, 1, 1, "SNES"), matches.title)
        selectFirst()
        runCurrent()
        assertEquals(cover.thumbnail, (nav.dialogState.value as DialogState.ScraperMenu).previews.first())
        click(R.string.steamgrid_next_page)
        runCurrent()
        verify(exactly = 1) { steam.systemArtwork("key", 10, type, 1) }
        controller.back()
        controller.back()
        assertEquals(matches, nav.dialogState.value)
        click(R.string.bulk_skip_system)
        runCurrent()
        assertEquals(summary(context.getString(R.string.bulk_complete), 0, 1),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { steam.download(any()); artwork.installSystem(any(), any(), any()) }
        controller.openSystems(emptyList()) { closed = true }
        controller.back()
        assertTrue(closed)
    }

    @Test fun `cancelling interactive system download prevents late installation`() = scope.runTest {
        val type = SystemArtworkType.ICON
        every { settings.systemArtworkType } returns type
        every { artwork.systemArtworkFile(any(), type) } returns null
        every { steam.search("key", "SNES") } returns listOf(match)
        every { steam.systemArtwork("key", 10, type, 0) } returns SteamGridCovers(listOf(cover), false)
        controller.openSystems(listOf(Platform("SNES", "SNES", null))) {}
        click(R.string.bulk_mode)
        click(R.string.bulk_start)
        runCurrent()
        selectFirst()
        runCurrent()
        val picker = nav.dialogState.value
        every { steam.download(cover) } answers { controller.back(); bytes }
        selectFirst()
        runCurrent()
        assertEquals(picker, nav.dialogState.value)
        verify(exactly = 0) { artwork.installSystem(any(), any(), any()) }
    }

    @Test fun `ports platform bulk scraping uses port covers skips existing and excludes tools`() = scope.runTest {
        val port = dev.piannoli.scorza.model.App(1, dev.piannoli.scorza.model.AppType.PORT, "Game", "dev.game")
        val existing = port.copy(id = 2, artFile = File("Existing.png"))
        every { apps.all(dev.piannoli.scorza.model.AppType.PORT) } returns listOf(port, existing)
        every { artwork.installPort(port, bytes, false) } returns File("Game.png")
        controller.open("PORTS") {}
        click(R.string.bulk_start)
        advanceTimeBy(500)
        runCurrent()
        verify(exactly = 1) { artwork.installPort(port, bytes, false) }
        verify(exactly = 0) { roms.romsForScraping(any(), any()); apps.all(dev.piannoli.scorza.model.AppType.TOOL) }
        assertEquals(summary(context.getString(R.string.bulk_complete), 1, 0),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `scrape all includes ports even with same id as a rom`() = scope.runTest {
        val port = dev.piannoli.scorza.model.App(game.id, dev.piannoli.scorza.model.AppType.PORT, "Game", "dev.game")
        every { apps.all(dev.piannoli.scorza.model.AppType.PORT) } returns listOf(port)
        every { artwork.installPort(port, bytes, false) } returns File("Port.png")
        click(R.string.bulk_start)
        advanceTimeBy(1_000)
        runCurrent()
        verify(exactly = 1) { artwork.installPort(port, bytes, false); artwork.install(game, bytes, false) }
        assertEquals(summary(context.getString(R.string.bulk_complete), 2, 0),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `interactive back restores matches then configuration then originating menu`() = scope.runTest {
        val origin = DialogState.ContextMenu("SNES", options = listOf("Rename", "Scrape SNES Games"))
        controller.open("SNES") { nav.dialogState.value = origin }
        click(R.string.bulk_mode)
        click(R.string.bulk_start)
        runCurrent()
        val matches = nav.dialogState.value
        selectFirst()
        runCurrent()
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).previews.isNotEmpty())
        controller.back()
        assertEquals(matches, nav.dialogState.value)
        selectFirst()
        runCurrent()
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).previews.isNotEmpty())
        controller.back()
        controller.back()
        assertEquals("Scrape SNES Games", (nav.dialogState.value as DialogState.ScraperMenu).title)
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).options.any { it.endsWith("\tInteractive") })
        controller.back()
        assertEquals(origin, nav.dialogState.value)
        verify(exactly = 0) { steam.download(any()) }
    }

    @Test fun `back during interactive download restores picker and prevents late installation`() = scope.runTest {
        click(R.string.bulk_mode)
        click(R.string.bulk_start)
        runCurrent()
        selectFirst()
        runCurrent()
        val picker = nav.dialogState.value
        every { steam.download(cover) } answers { controller.back(); bytes }
        selectFirst()
        runCurrent()
        assertEquals(picker, nav.dialogState.value)
        verify(exactly = 0) { artwork.install(any(), any()) }
    }

    @Test fun `configuration cycles horizontally preserving selection and confirm only starts scraping`() {
        controller.open("SNES") {}
        val initial = nav.dialogState.value as DialogState.ScraperMenu
        for (index in 0..2) {
            val selected = (nav.dialogState.value as DialogState.ScraperMenu).copy(selectedIndex = index)
            nav.dialogState.value = selected
            controller.confirm(selected)
            assertEquals(selected, nav.dialogState.value)
            controller.cycle(selected)
            val changed = nav.dialogState.value as DialogState.ScraperMenu
            assertEquals(index, changed.selectedIndex)
            assertFalse(selected.options[index] == changed.options[index])
            controller.cycle(changed)
            assertEquals(initial.options, (nav.dialogState.value as DialogState.ScraperMenu).options)
        }
        val start = (nav.dialogState.value as DialogState.ScraperMenu).copy(selectedIndex = 3)
        nav.dialogState.value = start
        controller.cycle(start)
        assertEquals(start, nav.dialogState.value)
        verify(exactly = 0) { steam.search(any(), any()) }
    }

    private fun summary(reason: String, saved: Int, skipped: Int, failed: Int = 0, remaining: Int = 0) =
        context.getString(R.string.bulk_summary, reason, saved, skipped, failed, remaining)

    @Test fun `automatic is default and scrape all includes subfolders across every local platform`() = scope.runTest {
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).options.any { it.endsWith("\tAutomatic") })
        click(R.string.bulk_start)
        advanceTimeBy(500)
        runCurrent()
        verify { roms.romsForScraping("SNES", true); roms.romsForScraping("NES", true) }
        verify(exactly = 1) { artwork.install(game, bytes) }
        assertEquals(summary(context.getString(R.string.bulk_complete), 1, 0),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `completed summary closes after three seconds without dismissing newer dialogs`() = scope.runTest {
        var closed = false
        controller.open { closed = true; nav.dialogState.value = DialogState.None }
        click(R.string.bulk_start)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(nav.dialogState.value is DialogState.ScraperStatus)
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(closed)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(closed)
        assertEquals(DialogState.None, nav.dialogState.value)

        closed = false
        controller.open { closed = true }
        click(R.string.bulk_start)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        val newer = DialogState.About()
        nav.dialogState.value = newer
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(closed)
        assertEquals(newer, nav.dialogState.value)
    }

    @Test fun `back from completed summary skips scraping menus and originating submenu`() = scope.runTest {
        var returnedToSubmenu = false
        controller.open { returnedToSubmenu = true }
        click(R.string.bulk_start)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(nav.dialogState.value is DialogState.ScraperStatus)
        controller.back()
        assertFalse(returnedToSubmenu)
        assertEquals(DialogState.None, nav.dialogState.value)
        advanceUntilIdle()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `platform entry and subfolder toggle scope the selected run`() = scope.runTest {
        controller.open("SNES") {}
        assertEquals("Scrape SNES Games", (nav.dialogState.value as DialogState.ScraperMenu).title)
        click(R.string.bulk_subfolders)
        click(R.string.bulk_start)
        advanceUntilIdle()
        verify(exactly = 1) { roms.romsForScraping("SNES", false) }
        verify(exactly = 0) { roms.romsForScraping("NES", any()) }
    }

    @Test fun `overwrite option replaces existing artwork but protects embedded art and resets on reopen`() = scope.runTest {
        val existing = game.copy(artFile = File("Existing.jpg"))
        every { roms.romsForScraping("SNES", true) } returns listOf(existing, game.copy(id = 2, path = File("cart.p8.png")))
        every { artwork.install(existing, bytes, true) } returns File("Game.png")
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).options.contains("Existing artwork\tSkip"))
        click(R.string.bulk_existing_artwork)
        click(R.string.bulk_start)
        advanceTimeBy(1_000)
        runCurrent()
        verify(exactly = 1) { artwork.install(existing, bytes, true) }
        assertEquals(summary(context.getString(R.string.bulk_complete), 1, 1),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        controller.open {}
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).options.contains("Existing artwork\tSkip"))
    }

    @Test fun `existing artwork is excluded while embedded ambiguous and unmatched games are skipped`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(
            game.copy(id = 1, artFile = File("existing.png")),
            game.copy(id = 2, path = File("cart.p8.png")),
            game.copy(id = 3, displayName = "Ambiguous"),
            game.copy(id = 4, displayName = "Missing"),
        )
        every { steam.search("key", "Ambiguous") } returns listOf(
            SteamGridGame(11, "Ambiguous"), SteamGridGame(12, "Ambiguous"),
        )
        every { steam.search("key", "Missing") } returns listOf(SteamGridGame(13, "Missing sequel"))
        click(R.string.bulk_start)
        advanceTimeBy(2_000)
        runCurrent()
        verify(exactly = 0) { steam.download(any()); steam.search("key", "Game") }
        assertEquals(summary(context.getString(R.string.bulk_complete), 0, 3),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `skip existing artwork removes games from progress totals and remaining count`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(
            game.copy(id = 2, artFile = File("existing.png")),
            game,
            game.copy(id = 3, artFile = File("other.png")),
        )
        click(R.string.bulk_start)
        runCurrent()
        assertEquals(context.getString(R.string.bulk_progress, 1, 1, game.displayName, 0, 0),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(summary(context.getString(R.string.bulk_complete), 1, 0),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 1) { steam.search("key", "Game"); artwork.install(game, bytes) }
    }

    @Test fun `interactive skip existing artwork excludes games from picker totals`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(
            game.copy(id = 2, artFile = File("existing.png")), game,
        )
        click(R.string.bulk_mode)
        click(R.string.bulk_start)
        runCurrent()
        assertEquals(context.getString(R.string.bulk_game, 1, 1, game.displayName),
            (nav.dialogState.value as DialogState.ScraperMenu).title)
    }

    @Test fun `all existing artwork finishes with no skipped or remaining games`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(game.copy(artFile = File("existing.png")))
        click(R.string.bulk_start)
        runCurrent()
        assertEquals(summary(context.getString(R.string.bulk_complete), 0, 0),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { steam.search(any(), any()); steam.download(any()) }
    }

    @Test fun `interactive waits for game and cover choices and installs selected cover immediately`() = scope.runTest {
        click(R.string.bulk_mode)
        click(R.string.bulk_start)
        advanceUntilIdle()
        assertEquals(match.name, (nav.dialogState.value as DialogState.ScraperMenu).options.first().substringBefore('\t'))
        verify(exactly = 0) { steam.covers(any(), any(), any()); steam.download(any()) }
        selectFirst()
        runCurrent()
        assertEquals(cover.thumbnail, (nav.dialogState.value as DialogState.ScraperMenu).previews.first())
        verify(exactly = 0) { steam.download(any()) }
        selectFirst()
        advanceUntilIdle()
        verify(exactly = 1) { artwork.install(game, bytes) }
    }

    @Test fun `interactive skip advances to next game and pagination retains chosen match`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(game, game.copy(id = 2))
        every { steam.covers("key", 10, 0) } returns SteamGridCovers(listOf(cover), true)
        every { steam.covers("key", 10, 1) } returns SteamGridCovers(listOf(cover.copy(id = 21)), false)
        click(R.string.bulk_mode)
        click(R.string.bulk_start)
        runCurrent()
        click(R.string.bulk_skip_game)
        runCurrent()
        selectFirst()
        runCurrent()
        click(R.string.steamgrid_next_page)
        runCurrent()
        click(R.string.steamgrid_previous_page)
        runCurrent()
        verify(exactly = 2) { steam.covers("key", 10, 0) }
        click(R.string.bulk_skip_game)
        runCurrent()
        assertEquals(summary(context.getString(R.string.bulk_complete), 0, 2),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `rate limit stops batch with explicit failure and remaining count`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(game, game.copy(id = 2))
        every { steam.search("key", "Game") } throws ScraperException(ScraperError.RATE_LIMIT)
        click(R.string.bulk_start)
        advanceUntilIdle()
        val reason = context.getString(R.string.bulk_stopped, context.getString(R.string.steamgrid_rate_limit))
        assertEquals(summary(reason, 0, 0, 1, 1), (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 1) { steam.search(any(), any()) }
    }

    @Test fun `cancellation during download prevents late artwork install`() = scope.runTest {
        every { steam.download(cover) } answers { controller.back(); bytes }
        click(R.string.bulk_start)
        advanceUntilIdle()
        verify(exactly = 0) { artwork.install(any(), any()) }
        assertEquals(context.getString(R.string.bulk_all), (nav.dialogState.value as DialogState.ScraperMenu).title)
    }

    @Test fun `cancelling after a saved cover keeps it and leaves remaining games untouched`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(game, game.copy(id = 2))
        click(R.string.bulk_start)
        runCurrent()
        controller.back()
        advanceUntilIdle()
        verify(exactly = 1) { artwork.install(any(), bytes) }
        verify(exactly = 1) { steam.search(any(), any()) }
        assertEquals(context.getString(R.string.bulk_all), (nav.dialogState.value as DialogState.ScraperMenu).title)
    }

    @Test fun `automatic title matching normalizes accents and punctuation without guessing editions`() {
        val matching = SteamGridGame(10, "Pokémon: Game!")
        assertEquals(matching, controller.exactMatch("pokemon game", listOf(matching, matching)))
        assertNull(controller.exactMatch("Pokemon Game", listOf(SteamGridGame(11, "Pokemon Game 2"))))
        assertNull(controller.exactMatch("Pokemon Game", listOf(matching, SteamGridGame(12, "Pokemon Game"))))
    }

    @Test fun `skip leaves an inexact title untouched without retrying`() = scope.runTest {
        every { settings.scrapeFailBehavior } returns ScrapeFailBehavior.SKIP
        every { roms.romsForScraping("SNES", true) } returns listOf(game.copy(displayName = "Game!"))
        every { steam.search("key", "Game!") } returns listOf(SteamGridGame(11, "Game Deluxe"))
        click(R.string.bulk_start)
        advanceUntilIdle()
        verify(exactly = 1) { steam.search(any(), any()) }
        verify(exactly = 0) { steam.covers(any(), any(), any()); artwork.install(any(), any()) }
    }

    @Test fun `default retries cleaned title once and installs only an exact match`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(game.copy(displayName = "Game!"))
        every { steam.search("key", "Game!") } returns emptyList()
        click(R.string.bulk_start)
        advanceUntilIdle()
        verify(exactly = 1) { steam.search("key", "Game!"); steam.search("key", "Game") }
        verify(exactly = 1) { artwork.install(any(), bytes) }
    }

    @Test fun `retry skip leaves inexact retry results untouched`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(game.copy(displayName = "Game!"))
        every { steam.search("key", "Game!") } returns emptyList()
        every { steam.search("key", "Game") } returns listOf(SteamGridGame(11, "Game Deluxe"))
        click(R.string.bulk_start)
        advanceUntilIdle()
        verify(exactly = 2) { steam.search(any(), any()) }
        verify(exactly = 0) { steam.covers(any(), any(), any()); artwork.install(any(), any()) }
    }

    @Test fun `best match ranks title similarity rather than provider order without retrying`() = scope.runTest {
        every { settings.scrapeFailBehavior } returns ScrapeFailBehavior.BEST_MATCH
        every { steam.search("key", "Game") } returns listOf(
            SteamGridGame(11, "Game 2"), SteamGridGame(10, "Game Deluxe"),
        )
        click(R.string.bulk_start)
        advanceUntilIdle()
        verify(exactly = 1) { steam.search(any(), any()); artwork.install(game, bytes) }
        verify(exactly = 0) { steam.covers("key", 11, any()) }
    }

    @Test fun `retry best match considers original results when cleaned search is empty`() = scope.runTest {
        every { settings.scrapeFailBehavior } returns ScrapeFailBehavior.RETRY_BEST_MATCH
        every { roms.romsForScraping("SNES", true) } returns listOf(game.copy(displayName = "Game!"))
        every { steam.search("key", "Game!") } returns listOf(SteamGridGame(10, "Game Deluxe"))
        every { steam.search("key", "Game") } returns emptyList()
        click(R.string.bulk_start)
        advanceUntilIdle()
        verify(exactly = 2) { steam.search(any(), any()) }
        verify(exactly = 1) { artwork.install(any(), bytes) }
    }

    @Test fun `retry best match skips when neither query finds candidates`() = scope.runTest {
        every { settings.scrapeFailBehavior } returns ScrapeFailBehavior.RETRY_BEST_MATCH
        every { roms.romsForScraping("SNES", true) } returns listOf(game.copy(displayName = "Game!"))
        every { steam.search(any(), any()) } returns emptyList()
        click(R.string.bulk_start)
        advanceTimeBy(500)
        runCurrent()
        verify(exactly = 2) { steam.search(any(), any()) }
        verify(exactly = 0) { artwork.install(any(), any()) }
        assertEquals(summary(context.getString(R.string.bulk_complete), 0, 1),
            (nav.dialogState.value as DialogState.ScraperStatus).message)
    }

    @Test fun `retry skips duplicate and empty cleaned queries and missing artwork without looping`() = scope.runTest {
        every { roms.romsForScraping("SNES", true) } returns listOf(game, game.copy(id = 2, displayName = "!!!"))
        every { steam.search("key", "!!!") } returns emptyList()
        every { steam.covers("key", 10, 0) } returns SteamGridCovers(emptyList(), false)
        click(R.string.bulk_start)
        advanceUntilIdle()
        verify(exactly = 1) { steam.search("key", "Game"); steam.search("key", "!!!") }
        verify(exactly = 0) { artwork.install(any(), any()) }
    }

    @Test fun `interactive retry keeps selection manual even with best match enabled`() = scope.runTest {
        every { settings.scrapeFailBehavior } returns ScrapeFailBehavior.RETRY_BEST_MATCH
        every { roms.romsForScraping("SNES", true) } returns listOf(game.copy(displayName = "Game!"))
        every { steam.search("key", "Game!") } returns emptyList()
        click(R.string.bulk_mode)
        click(R.string.bulk_start)
        advanceUntilIdle()
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).options.first().startsWith("Game\t"))
        verify(exactly = 1) { steam.search("key", "Game") }
        verify(exactly = 0) { steam.covers(any(), any(), any()); artwork.install(any(), any()) }
    }

    @Test fun `missing key blocks network access and back returns to scraping menu`() {
        every { settings.steamGridApiKey } returns ""
        click(R.string.bulk_start)
        assertEquals(context.getString(R.string.steamgrid_key_required), (nav.dialogState.value as DialogState.ScraperStatus).message)
        verify(exactly = 0) { steam.search(any(), any()) }
        controller.back()
        assertEquals(context.getString(R.string.bulk_all), (nav.dialogState.value as DialogState.ScraperMenu).title)
    }

    @Test fun `back from platform configuration returns to its originating submenu`() = scope.runTest {
        var returned = false
        controller.open("SNES") { returned = true }
        click(R.string.bulk_mode)
        assertTrue((nav.dialogState.value as DialogState.ScraperMenu).options.any { it.endsWith("\tInteractive") })
        assertFalse(returned)
        controller.back()
        assertTrue(returned)
    }
}
