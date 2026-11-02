package dev.piannoli.scorza.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.db.LibraryRef
import dev.piannoli.scorza.db.RecentlyPlayedRepository
import dev.piannoli.scorza.db.RomsRepository
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.launcher.LaunchManager
import dev.piannoli.scorza.model.ListItem
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.romm.sync.PreLaunchOutcome
import dev.piannoli.scorza.romm.sync.SaveSyncService
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.screens.withMenuDelta
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.util.RomDirectoryWalker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherActionsDiscTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val nav = NavigationController()
    private val manager = mockk<LaunchManager>(relaxed = true)
    private val walker = mockk<RomDirectoryWalker>()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val sync = mockk<SaveSyncService>(relaxed = true)
    private val recent = mockk<RecentlyPlayedRepository>(relaxed = true)
    private val roms = mockk<RomsRepository>(relaxed = true)
    private val remembered = mutableMapOf<String, String>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { settings.lastDisc(any()) } answers { remembered[firstArg()] }
        every { settings.rememberDisc(any(), any()) } answers {
            remembered[firstArg()] = secondArg()
        }
        every { manager.launchRom(any(), any(), any()) } returns null
        every { manager.resumeRom(any(), selectedDisc = any(), discSet = any()) } returns null
        every { sync.isSyncableGame(any()) } returns null
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    private fun TestScope.actions(): LauncherActions {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val paths = mockk<PiannoliPathsProvider>()
        every { paths.romDir } returns temp.root
        val games = mockk<GameListViewModel>(relaxed = true)
        every { games.state } returns MutableStateFlow(GameListViewModel.State(platformTag = "recently_played"))
        return LauncherActions(
            context, this, settings, mockk(relaxed = true), recent, roms, mockk(relaxed = true),
            manager, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), nav, mockk(relaxed = true), games, mockk(relaxed = true),
            sync, paths, walker,
        )
    }

    private fun game(playlist: Boolean = false): Pair<Rom, List<File>> {
        val discs = listOf(1, 2).map { File(temp.root, "Game (Disc $it).chd").apply { writeText("disc") } }
        val path = if (playlist) File(temp.root, "Game.m3u").apply {
            writeText(discs.joinToString("\n") { it.name })
        } else discs.first()
        val rom = Rom(17L, path, "PS", "Game")
        every { walker.discFiles(path) } returns discs
        every { roms.gameByPath(path.absolutePath) } returns rom
        return rom to discs
    }

    @Test fun `selection launches original disc with shared identity and remembers only a successful launch`() = runTest(dispatcher) {
        val actions = actions()
        val (rom, discs) = game()
        actions.launchSelected(ListItem.RomItem(rom), false)
        advanceUntilIdle()
        val picker = nav.dialogState.value as DialogState.DiscPicker
        assertEquals(discs, picker.files)
        assertEquals(1, (picker.withMenuDelta(1) as DialogState.DiscPicker).selectedIndex)
        verify(exactly = 0) { manager.launchRom(any(), any(), any()) }
        actions.confirmDiscSelection(picker.copy(selectedIndex = 1))
        advanceUntilIdle()
        verify(exactly = 1) { manager.launchRom(rom, discs[1], discs) }
        verify(exactly = 1) { recent.record(LibraryRef.Rom(rom.id), any()) }
        assertEquals(discs[1].absolutePath, remembered[rom.path.absolutePath])
        assertTrue(nav.pendingRecentlyPlayedReorder)

        actions.launchSelected(ListItem.RomItem(rom), false)
        advanceUntilIdle()
        assertEquals(1, (nav.dialogState.value as DialogState.DiscPicker).selectedIndex)
        actions.launchSelected(ListItem.RomItem(rom), true)
        advanceUntilIdle()
        verify(exactly = 1) { manager.resumeRom(rom, discs[1], discs) }
    }

    @Test fun `existing playlists offer original playlist as well as ordered individual discs`() = runTest(dispatcher) {
        val actions = actions()
        val (rom, discs) = game(playlist = true)
        actions.launchSelected(ListItem.RomItem(rom), false)
        advanceUntilIdle()
        val picker = nav.dialogState.value as DialogState.DiscPicker
        assertEquals(listOf(rom.path) + discs, picker.files)
        actions.confirmDiscSelection(picker)
        advanceUntilIdle()
        verify { manager.launchRom(rom, rom.path, discs) }
    }

    @Test fun `cancelled loading cannot open a stale picker or launch or record history`() = runTest(dispatcher) {
        val actions = actions()
        val (rom, _) = game()
        actions.launchSelected(ListItem.RomItem(rom), false)
        nav.dialogState.value = DialogState.None
        advanceUntilIdle()
        assertEquals(DialogState.None, nav.dialogState.value)
        verify(exactly = 0) { manager.launchRom(any(), any(), any()) }
        verify(exactly = 0) { recent.record(any(), any()) }
        assertTrue(remembered.isEmpty())
    }

    @Test fun `read failures and changed resume sets are explicit and never launch a different disc`() = runTest(dispatcher) {
        val actions = actions()
        val (rom, discs) = game(playlist = true)
        every { walker.discFiles(rom.path) } throws IOException("circular playlist")
        actions.launchSelected(ListItem.RomItem(rom), false)
        advanceUntilIdle()
        assertTrue((nav.dialogState.value as DialogState.LaunchError).message.contains("circular playlist"))
        every { walker.discFiles(rom.path) } returns discs.take(1)
        remembered[rom.path.absolutePath] = discs[1].absolutePath
        actions.launchSelected(ListItem.RomItem(rom), true)
        advanceUntilIdle()
        assertTrue(nav.dialogState.value is DialogState.LaunchError)
        verify(exactly = 0) { manager.resumeRom(any(), selectedDisc = any(), discSet = any()) }
    }

    @Test fun `failed deferred launch does not remember disc or record history`() = runTest(dispatcher) {
        val actions = actions()
        val (rom, discs) = game()
        every { sync.isSyncableGame(any()) } returns 42
        coEvery { sync.syncBeforeLaunch(any(), any(), any(), any()) } returns PreLaunchOutcome.Proceed
        every { manager.launchRom(rom, discs[1], discs) } returns DialogState.LaunchError("missing emulator")
        actions.launchSelected(ListItem.RomItem(rom), false)
        advanceUntilIdle()
        actions.confirmDiscSelection((nav.dialogState.value as DialogState.DiscPicker).copy(selectedIndex = 1))
        advanceUntilIdle()
        assertTrue(nav.dialogState.value is DialogState.LaunchError)
        verify(exactly = 0) { recent.record(any(), any()) }
        assertTrue(remembered.isEmpty())
    }
}
