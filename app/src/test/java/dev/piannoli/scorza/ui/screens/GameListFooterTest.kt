package dev.piannoli.scorza.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.scorza.model.ListItem
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.ui.components.DialogOverlay
import dev.piannoli.scorza.ui.quickmenu.QuickMenuRow
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
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
class GameListFooterTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `quick menu change hint appears only on order by`() {
        val dialog = mutableStateOf(DialogState.QuickMenu(
            rows = listOf(QuickMenuRow.ORDER_BY, QuickMenuRow.INFO),
        ))
        compose.setContent {
            DialogOverlay(dialog.value, null, 0, 22.sp, 32.sp, 8.dp)
        }
        compose.onNodeWithText("L/R").assertIsDisplayed()
        compose.onNodeWithText("CHANGE").assertIsDisplayed()
        compose.runOnIdle { dialog.value = dialog.value.copy(selectedIndex = 1) }
        compose.onAllNodesWithText("L/R").assertCountEquals(0)
        compose.onAllNodesWithText("CHANGE").assertCountEquals(0)
    }

    @Test fun `quick menu scrape label reflects list title or whole library`() {
        val dialog = mutableStateOf(DialogState.QuickMenu(
            rows = listOf(QuickMenuRow.SCRAPING, QuickMenuRow.SCRAPE_SYSTEMS),
        ))
        compose.setContent { DialogOverlay(dialog.value, null, 0, 22.sp, 32.sp, 8.dp) }
        compose.onNodeWithText("Scrape All Games").assertIsDisplayed()
        compose.onNodeWithText("Scrape All Systems").assertIsDisplayed()
        compose.runOnIdle {
            dialog.value = dialog.value.copy(rows = listOf(QuickMenuRow.SCRAPING), scrapeListTitle = "Game Boy")
        }
        compose.onNodeWithText("Scrape All Game Boy Games").assertIsDisplayed()
        compose.onAllNodesWithText("Scrape All Systems").assertCountEquals(0)
    }

    @Test fun `quick menu displays list subtitle and omits it on the main menu`() {
        val dialog = mutableStateOf(DialogState.QuickMenu(rows = listOf(QuickMenuRow.INFO)))
        compose.setContent { DialogOverlay(dialog.value, null, 0, 22.sp, 32.sp, 8.dp) }
        compose.onAllNodesWithText("Game Boy").assertCountEquals(0)
        compose.runOnIdle { dialog.value = dialog.value.copy(listTitle = "Game Boy") }
        compose.onNodeWithText("Game Boy").assertIsDisplayed()
        compose.runOnIdle { dialog.value = dialog.value.copy(listTitle = "Collections") }
        compose.onNodeWithText("Collections").assertIsDisplayed()
        compose.onAllNodesWithText("Game Boy").assertCountEquals(0)
        compose.runOnIdle { dialog.value = dialog.value.copy(listTitle = null) }
        compose.onAllNodesWithText("Collections").assertCountEquals(0)
    }

    @Test fun `mixed platform collections label unique names and retain platform tags when filtered`() {
        val mario = ListItem.RomItem(Rom(1, File("Mario.nes"), "NES", "Mario"))
        val sonic = ListItem.RomItem(Rom(2, File("Sonic.md"), "MD", "Sonic"))
        val duplicate = mario.copy(rom = mario.rom.copy(id = 3, tags = "(USA)"))
        val state = MutableStateFlow(GameListViewModel.State(
            isCollection = true, items = listOf(mario, sonic), allItems = listOf(mario, sonic),
        ))
        val model = mockk<GameListViewModel>(relaxed = true)
        every { model.state } returns state
        compose.setContent { GameListScreen(model, artWidth = 0) }
        compose.onNodeWithText("(NES)").assertIsDisplayed()
        compose.onNodeWithText("(MD)").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(items = listOf(mario), searchTerm = "Mario") }
        compose.onNodeWithText("(NES)").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(items = listOf(mario), allItems = listOf(mario), searchTerm = null)
        }
        compose.onAllNodesWithText("(NES)").assertCountEquals(0)
        compose.runOnIdle {
            state.value = state.value.copy(items = listOf(mario, duplicate), allItems = listOf(mario, duplicate))
        }
        compose.onNodeWithText("(NES) (USA)").assertIsDisplayed()
    }

    @Test fun `handheld collection home labels games from different platforms`() {
        val items = listOf(
            ListItem.RomItem(Rom(1, File("Mario.nes"), "NES", "Mario")),
            ListItem.RomItem(Rom(2, File("Sonic.md"), "MD", "Sonic")),
        ).map { SystemListViewModel.ListItem.GameItem(it) }
        val model = mockk<SystemListViewModel>(relaxed = true)
        every { model.state } returns MutableStateFlow(SystemListViewModel.State(
            items = items, isLoading = false, hasGameItems = true,
        ))
        compose.setContent { SystemListScreen(model, artWidth = 0, fiveGameHandheld = true) }
        compose.onNodeWithText("(NES)").assertIsDisplayed()
        compose.onNodeWithText("(MD)").assertIsDisplayed()
    }

    @Test fun `new collection hint appears only in the collections list`() {
        val state = MutableStateFlow(GameListViewModel.State(isCollectionsList = true))
        val model = mockk<GameListViewModel>(relaxed = true)
        every { model.state } returns state
        compose.setContent { GameListScreen(model, artWidth = 0) }

        compose.onNodeWithText("NEW").assertIsDisplayed()
        for (favorites in listOf(false, true)) {
            compose.runOnIdle {
                state.value = GameListViewModel.State(isCollection = true, isFavorites = favorites)
            }
            compose.onAllNodesWithText("NEW").assertCountEquals(0)
        }
        compose.runOnIdle { state.value = GameListViewModel.State(isCollectionsList = true) }
        compose.onNodeWithText("NEW").assertIsDisplayed()
    }
}
