package dev.piannoli.scorza.input.screen

import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.ui.components.KeyboardState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameListSelectReleaseTest {

    private lateinit var nav: NavigationController
    private lateinit var glvm: GameListViewModel
    private lateinit var handler: GameListInputHandler

    @Before fun setup() {
        nav = NavigationController()
        glvm = mockk(relaxed = true)
        handler = GameListInputHandler(
            nav = nav,
            ioScope = mockk(relaxed = true),
            settings = mockk<SettingsRepository>(relaxed = true),
            gameListViewModel = glvm,
            launcherActions = mockk<LauncherActions>(relaxed = true),
        )
        every { glvm.isMultiSelectMode() } returns false
        every { glvm.isReorderMode() } returns false
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State(isCollection = true))
    }

    // A dialog consumes the Select press but not the release, so the handler can see a release
    // whose press it never got. Acting on it silently reorders the list behind the dialog.
    @Test fun back_from_search_results_reopens_saved_keyboard_instead_of_clearing_search() {
        val keyboard = KeyboardState(text = "  Mario ", cursorPos = 3, keyRow = 1, keyCol = 2)
        every { glvm.isSearching() } returns true
        every { glvm.searchKeyboard() } returns keyboard
        handler.onBack()
        val dialog = nav.dialogState.value as DialogState.RenameInput
        assertEquals("launcher_search", dialog.gameName)
        assertEquals(keyboard, dialog.keyboard)
        verify(exactly = 0) { glvm.clearSearch() }
    }

    @Test fun back_from_global_library_results_reopens_global_query_keyboard() {
        val keyboard = KeyboardState(text = "Mario", cursorPos = 2)
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State(
            isGlobalSearch = true, globalSearchQuery = "Mario"))
        every { glvm.searchKeyboard() } returns keyboard
        handler.onBack()
        val dialog = nav.dialogState.value as DialogState.RenameInput
        assertEquals("launcher_global_search", dialog.gameName)
        assertEquals(keyboard, dialog.keyboard)
    }

    @Test fun reopening_search_keyboard_does_not_replace_original_list_bookmark() {
        handler.onR1()
        every { glvm.isSearching() } returns true
        handler.onBack()
        handler.onBack()
        verify(exactly = 1) { glvm.beginSearch() }
    }

    @Test fun editing_restored_global_results_never_uses_results_as_cancel_destination() {
        nav.push(LauncherScreen.GameList)
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State(
            isGlobalSearch = true, globalSearchQuery = "Mario"))
        handler.onBack()
        nav.cancelLauncherSearch()
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
    }

    @Test fun select_release_without_a_press_does_not_enter_reorder() {
        handler.onSelectUp()
        verify(exactly = 0) { glvm.enterReorderMode() }
    }

    @Test fun select_press_then_release_still_enters_reorder() {
        handler.onSelect()
        handler.onSelectUp()
        verify { glvm.enterReorderMode() }
    }

    @Test fun release_still_clears_press_state_so_the_next_tap_works() {
        handler.onSelect()
        handler.onSelectUp()
        handler.onSelect()
        handler.onSelectUp()
        verify(exactly = 2) { glvm.enterReorderMode() }
    }
}
