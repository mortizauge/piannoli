package dev.piannoli.scorza.input.screen

import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.model.ListItem
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameListResumeHoldTest {

    private lateinit var nav: NavigationController
    private lateinit var settings: SettingsRepository
    private lateinit var glvm: GameListViewModel
    private lateinit var actions: LauncherActions
    private lateinit var handler: GameListInputHandler

    private val rom = Rom(id = 1L, path = File("/roms/snes/Zelda.sfc"), platformTag = "snes", displayName = "Zelda")
    private val romItem = ListItem.RomItem(rom)

    @Before fun setup() {
        nav = NavigationController()
        settings = mockk(relaxed = true)
        glvm = mockk(relaxed = true)
        actions = mockk(relaxed = true)
        handler = GameListInputHandler(
            nav = nav,
            ioScope = mockk(relaxed = true),
            settings = settings,
            gameListViewModel = glvm,
            launcherActions = actions,
        )
        every { glvm.isMultiSelectMode() } returns false
        every { glvm.isReorderMode() } returns false
        every { glvm.getSelectedItem() } returns romItem
        nav.resumableGames = setOf(rom.path.absolutePath)
    }

    @Test fun confirm_on_resumable_always_plays_immediately() {
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State())
        handler.onConfirm()
        handler.onConfirmUp()
        handler.resumeHoldRunnable.run()
        verify(exactly = 1) { actions.launchSelected(romItem, false) }
        verify(exactly = 0) { actions.buildSaveStatePicker(any(), any()) }
    }

    @Test fun north_hold_opens_picker_without_launching_on_release() {
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State())
        every { actions.buildSaveStatePicker(rom, any()) } returns
            LauncherScreen.SaveStatePicker(rom, "/x/base", List(11) { false }, 0)
        handler.onNorth()
        handler.resumeHoldRunnable.run()
        handler.onNorthUp()
        assertTrue(nav.currentScreen is LauncherScreen.SaveStatePicker)
        verify(exactly = 0) { actions.launchSelected(any(), any()) }
        verify { actions.buildSaveStatePicker(rom, false) }
    }

    @Test fun held_north_pushes_picker_only_once() {
        every { actions.buildSaveStatePicker(rom, any()) } returns
            LauncherScreen.SaveStatePicker(rom, "/x/base", List(11) { false }, 0)
        handler.onNorth()
        handler.onNorth()
        val depthBefore = nav.screenStack.size
        handler.resumeHoldRunnable.run()
        handler.resumeHoldRunnable.run()
        handler.onNorthUp()
        assertEquals(depthBefore + 1, nav.screenStack.size)
    }

    @Test fun north_tap_resumes_latest_once() {
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State())
        handler.onNorth()
        verify(exactly = 0) { actions.launchSelected(any(), any()) }
        handler.onNorthUp()
        handler.onNorthUp()
        verify(exactly = 1) { actions.launchSelected(romItem, true) }
    }

    @Test fun changing_selection_cancels_pending_resume_hold() {
        handler.onNorth()
        handler.onDown()
        handler.resumeHoldRunnable.run()
        handler.onNorthUp()
        verify(exactly = 0) { actions.buildSaveStatePicker(any(), any()) }
        verify(exactly = 0) { actions.launchSelected(any(), any()) }
    }
}
