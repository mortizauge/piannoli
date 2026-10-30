package dev.cannoli.scorza.scraper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.withMenuDelta
import dev.cannoli.ui.KEY_BACKSPACE
import dev.cannoli.ui.KEY_ENTER
import dev.cannoli.ui.components.KeyboardOverlay
import dev.cannoli.ui.components.KeyboardHelpOverlay
import dev.cannoli.ui.components.KeyboardState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class SteamGridPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Piannoli keyboard routes arrows to keys and types physical keys at the cursor`() {
        val original = KeyboardState(text = "Mario", cursorPos = 2, keyRow = 1, keyCol = 2)
        val state = mutableStateOf(original)
        compose.setContent {
            KeyboardOverlay(state = state.value, onStateChange = { state.value = it })
        }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        val root = compose.onRoot()
        val directions = listOf(
            Key.DirectionRight to dev.cannoli.ui.components.Direction.RIGHT,
            Key.DirectionDown to dev.cannoli.ui.components.Direction.DOWN,
            Key.DirectionLeft to dev.cannoli.ui.components.Direction.LEFT,
            Key.DirectionUp to dev.cannoli.ui.components.Direction.UP,
        )
        var expected = original
        directions.forEach { (key, direction) ->
            root.performKeyInput { pressKey(key) }
            expected = dev.cannoli.ui.components.KeyboardController.moveSelection(expected, direction)
            compose.runOnIdle { assertEquals(expected, state.value) }
        }
        root.performKeyInput { pressKey(Key.X) }
        root.performKeyInput { pressKey(Key.Backspace) }
        root.performKeyInput { pressKey(Key.Z) }
        compose.runOnIdle {
            assertEquals("Mazrio", state.value.text)
            assertEquals(3, state.value.cursorPos)
        }
    }

    @Test fun `system keyboard uses focused text editor without Piannoli keys and supports done and cancel`() {
        val state = mutableStateOf(KeyboardState(text = "old", cursorPos = 3))
        var confirms = 0
        var cancels = 0
        compose.setContent {
            KeyboardOverlay(
                state = state.value,
                useSystemKeyboard = true,
                onStateChange = { state.value = it },
                onConfirm = { confirms++ },
                onCancel = { cancels++ },
            )
        }
        compose.onNodeWithText("q").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInputSelection(TextRange(0, 3))
        compose.onNode(hasSetTextAction()).performTextInput("new title")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasText("CANCEL") and hasClickAction()).performClick()
        compose.runOnIdle {
            assertEquals("new title", state.value.text)
            assertEquals(1, confirms)
            assertEquals(1, cancels)
        }
    }

    @Test fun `system number editor rejects letters`() {
        val state = mutableStateOf(KeyboardState(layout = dev.cannoli.ui.components.KeyboardLayout.Number))
        compose.setContent {
            KeyboardOverlay(state = state.value, useSystemKeyboard = true, onStateChange = { state.value = it })
        }
        compose.onNode(hasSetTextAction()).performTextInput("123")
        compose.onNode(hasSetTextAction()).performTextInput("x")
        compose.runOnIdle { assertEquals("123", state.value.text) }
    }

    @Test fun `touch keyboard keys edit text and invoke confirm and cancel`() {
        val state = mutableStateOf(KeyboardState())
        var confirms = 0
        var cancels = 0
        val helpVisible = mutableStateOf(false)
        compose.setContent {
            if (helpVisible.value) {
                KeyboardHelpOverlay(
                    layout = state.value.layout,
                    onClose = { helpVisible.value = false },
                )
            } else {
                KeyboardOverlay(
                    state = state.value,
                    onStateChange = { state.value = it },
                    onConfirm = { confirms++ },
                    onCancel = { cancels++ },
                    onHelp = { helpVisible.value = true },
                )
            }
        }
        compose.onNodeWithText("q").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals("q", state.value.text) }
        compose.onNodeWithText(KEY_BACKSPACE).performTouchInput { click() }
        compose.onNodeWithText("w").performTouchInput { click() }
        compose.onNodeWithText(KEY_ENTER).assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("HELP").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("CLOSE").assertIsDisplayed().performTouchInput { click() }
        compose.onNode(hasText("CONFIRM") and hasClickAction()).performTouchInput { click() }
        compose.onNode(hasText("CANCEL") and hasClickAction()).performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("w", state.value.text)
            assertEquals(1, state.value.cursorPos)
            assertEquals(2, confirms)
            assertEquals(1, cancels)
        }
    }

    @Test fun `paste inserts at cursor in masked API key editor without altering clipboard`() {
        val key = "1234567890abcdef1234567890abcdef"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("key", key))
        val state = mutableStateOf(DialogState.ScraperInput(
            ScraperField.API_KEY, KeyboardState(text = "", cursorPos = 0),
        ))
        compose.setContent {
            DialogOverlay(
                dialogState = state.value,
                backgroundImagePath = null,
                backgroundTint = 0,
                listFontSize = 16.sp,
                listLineHeight = 24.sp,
                listVerticalPadding = 4.dp,
                onKeyboardChange = { state.value = state.value.copy(keyboard = it) },
            )
        }
        compose.onNodeWithText("PASTE").performClick()
        compose.runOnIdle {
            assertEquals(key, state.value.currentName)
            assertEquals(key.length, state.value.cursorPos)
            assertEquals(key, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun `preview failure is visible without hiding cover choices`() {
        val state = mutableStateOf<DialogState>(DialogState.ScraperMenu(
            title = "Game",
            options = listOf("600 x 900\tFirst artist", "600 x 900\tSecond artist"),
            previews = listOf("file:///missing-first.png", "file:///missing-second.png"),
        ))
        compose.setContent {
            DialogOverlay(
                dialogState = state.value,
                backgroundImagePath = null,
                backgroundTint = 0,
                listFontSize = 16.sp,
                listLineHeight = 24.sp,
                listVerticalPadding = 4.dp,
            )
        }
        compose.onNodeWithText("First artist").assertIsDisplayed()
        val error = "Cannot load preview. Choose another cover or try again."
        compose.waitUntil(5_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.withMenuDelta(1)!! }
        compose.onNodeWithText("Second artist").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(error).assertIsDisplayed()
    }
}
