package dev.cannoli.scorza.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.ui.screens.GameListScreen
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.ui.STAR
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp")
class PinIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `pinned tools show a pin which disappears on unpin while favorites retain their star`() {
        val pinned = App(1, AppType.TOOL, "Files", "dev.files", isPinned = true)
        val other = App(2, AppType.TOOL, "Settings", "dev.settings")
        val game = Rom(3, File("Game.gb"), "GB", "Game")
        val state = MutableStateFlow(GameListViewModel.State(
            platformTag = "recently_played",
            items = listOf(ListItem.AppItem(pinned), ListItem.AppItem(other), ListItem.RomItem(game)),
            favoriteRomIds = setOf(game.id),
            isLoading = false,
        ))
        val model = mockk<GameListViewModel>(relaxed = true)
        every { model.state } returns state
        every { model.showFavoriteStars } returns true
        compose.setContent { GameListScreen(viewModel = model, artWidth = 0) }
        compose.onAllNodesWithContentDescription("Pinned").assertCountEquals(1)
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("$STAR Game").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(items = listOf(
                ListItem.AppItem(pinned.copy(isPinned = false)), ListItem.AppItem(other), ListItem.RomItem(game)))
        }
        compose.onAllNodesWithContentDescription("Pinned").assertCountEquals(0)
        compose.onNodeWithText("$STAR Game").assertIsDisplayed()
    }
}
