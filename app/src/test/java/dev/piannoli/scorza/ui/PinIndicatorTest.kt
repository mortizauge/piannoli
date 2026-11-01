package dev.piannoli.scorza.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.piannoli.scorza.model.App
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.model.ListItem
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.ui.screens.GameListScreen
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.ui.STAR
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
        compose.onNodeWithText("Game").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Favorite").assertCountEquals(1)
        compose.runOnIdle {
            state.value = state.value.copy(items = listOf(
                ListItem.AppItem(pinned.copy(isPinned = false)), ListItem.AppItem(other), ListItem.RomItem(game)))
        }
        compose.onAllNodesWithContentDescription("Pinned").assertCountEquals(0)
        compose.onNodeWithText("Game").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Favorite").assertCountEquals(1)
    }

    @Test fun `favorite icons track ROM and app membership without changing titles`() {
        val game = Rom(1, File("Game.gb"), "GB", "Game")
        val port = App(2, AppType.PORT, "Port", "dev.port")
        val state = MutableStateFlow(GameListViewModel.State(
            platformTag = "recently_played",
            items = listOf(ListItem.RomItem(game), ListItem.AppItem(port)),
            favoriteRomIds = setOf(game.id),
            favoriteAppIds = setOf(port.id),
            isLoading = false,
        ))
        val model = mockk<GameListViewModel>(relaxed = true)
        every { model.state } returns state
        every { model.showFavoriteStars } returns true
        compose.setContent { GameListScreen(viewModel = model, artWidth = 0) }
        compose.onNodeWithText("Game").assertIsDisplayed()
        compose.onNodeWithText("Port").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Favorite").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Pinned").assertCountEquals(0)
        compose.runOnIdle {
            state.value = state.value.copy(favoriteRomIds = emptySet(), favoriteAppIds = emptySet())
        }
        compose.onAllNodesWithContentDescription("Favorite").assertCountEquals(0)
        compose.onNodeWithText("Game").assertIsDisplayed()
        compose.onNodeWithText("Port").assertIsDisplayed()
    }

    @Test fun `favorites collection and disabled setting hide favorite icons`() {
        val game = Rom(1, File("Game.gb"), "GB", "Game")
        val state = MutableStateFlow(GameListViewModel.State(
            isCollection = true,
            isFavorites = true,
            items = listOf(ListItem.RomItem(game)),
            favoriteRomIds = setOf(game.id),
            isLoading = false,
        ))
        val model = mockk<GameListViewModel>(relaxed = true)
        every { model.state } returns state
        every { model.showFavoriteStars } returns true
        compose.setContent { GameListScreen(viewModel = model, artWidth = 0) }
        compose.onNodeWithText("Game").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Favorite").assertCountEquals(0)
        compose.runOnIdle {
            every { model.showFavoriteStars } returns false
            state.value = state.value.copy(isCollection = false, isFavorites = false)
        }
        compose.onNodeWithText("Game").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Favorite").assertCountEquals(0)
    }
}
