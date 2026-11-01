package dev.piannoli.scorza.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateOf
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
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel.AppSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp")
class PinIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `hidden game artwork skips decoding for games ports apps and folders without hiding row indicators`() {
        val files = (0..3).map { File("game-artwork-visibility-${System.nanoTime()}-$it.png").absoluteFile }
        val game = Rom(1, File("Game.gb"), "GB", "Game", artFile = files[0])
        val items = listOf(
            ListItem.RomItem(game),
            ListItem.AppItem(App(2, AppType.PORT, "Port", "dev.port", artFile = files[1])),
            ListItem.AppItem(App(3, AppType.TOOL, "App", "dev.app", artFile = files[2], isPinned = true)),
            ListItem.SubfolderItem("Folder", "Folder", artFile = files[3]),
        )
        val state = MutableStateFlow(GameListViewModel.State(
            platformTag = "recently_played", items = items, favoriteRomIds = setOf(game.id), isLoading = false,
        ))
        val model = mockk<GameListViewModel>(relaxed = true)
        every { model.state } returns state
        every { model.showFavoriteStars } returns true
        val settings = mutableStateOf(AppSettings(showGameArtwork = false))
        val decoded = AtomicInteger()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        mockkStatic(BitmapFactory::class)
        for (file in files) {
            every { BitmapFactory.decodeFile(file.absolutePath, any()) } answers {
                val options = secondArg<BitmapFactory.Options>()
                if (options.inJustDecodeBounds) {
                    options.outWidth = 16
                    options.outHeight = 16
                    null
                } else {
                    decoded.incrementAndGet()
                    bitmap
                }
            }
        }
        try {
            compose.setContent { GameListScreen(model, artWidth = settings.value.effectiveArtWidth) }
            for (index in items.indices) {
                compose.runOnIdle {
                    settings.value = settings.value.copy(showGameArtwork = false)
                    state.value = state.value.copy(selectedIndex = index)
                }
                assertEquals(index, decoded.get())
                compose.onAllNodesWithContentDescription("Favorite").assertCountEquals(1)
                compose.onAllNodesWithContentDescription("Pinned").assertCountEquals(1)
                compose.onAllNodesWithContentDescription("Folder").assertCountEquals(1)
                compose.runOnIdle { settings.value = settings.value.copy(showGameArtwork = true) }
                compose.waitUntil(5_000) { decoded.get() == index + 1 }
                compose.runOnIdle { settings.value = settings.value.copy(showGameArtwork = false) }
                compose.runOnIdle { settings.value = settings.value.copy(showGameArtwork = true) }
                assertEquals(index + 1, decoded.get())
            }
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    @Test fun `subfolder rows use a folder glyph instead of a slash prefix`() {
        val model = mockk<GameListViewModel>(relaxed = true)
        every { model.state } returns MutableStateFlow(GameListViewModel.State(
            platformTag = "GB",
            items = listOf(ListItem.SubfolderItem(name = "Adventure", path = "Adventure")),
            isLoading = false,
        ))
        compose.setContent { GameListScreen(viewModel = model, artWidth = 0) }
        compose.onNodeWithText("Adventure").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Folder").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Pinned").assertCountEquals(0)
    }

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
