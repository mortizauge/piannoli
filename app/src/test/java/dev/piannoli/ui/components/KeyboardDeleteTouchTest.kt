package dev.piannoli.ui.components

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.piannoli.ui.KEY_BACKSPACE
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardDeleteTouchTest {
    @get:Rule val compose = createComposeRule()

    private fun advance(millis: Long) {
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)) }
        compose.waitForIdle()
    }

    @Test fun `touch hold repeats across recompositions deletes at cursor and stops on release`() {
        val state = mutableStateOf(KeyboardState(text = "abcdeXYZ", cursorPos = 5))
        var edits = 0
        compose.setContent {
            KeyboardOverlay(state = state.value, onStateChange = { state.value = it; edits++ })
        }
        val backspace = compose.onNodeWithText(KEY_BACKSPACE)
        backspace.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals("abcdXYZ", state.value.text) }
        advance(200)
        compose.runOnIdle { assertEquals(1, edits) }
        advance(200)
        compose.runOnIdle { assertEquals("abcXYZ", state.value.text) }
        advance(60)
        compose.runOnIdle { assertEquals("abXYZ", state.value.text) }
        backspace.performTouchInput { up() }
        advance(1000)
        compose.runOnIdle { assertEquals(3, edits) }
        backspace.performTouchInput { click() }
        compose.runOnIdle { assertEquals("aXYZ", state.value.text); assertEquals(4, edits) }
        backspace.performClick()
        compose.runOnIdle { assertEquals("XYZ", state.value.text); assertEquals(5, edits) }
        backspace.performTouchInput { down(center) }
        advance(1000)
        backspace.performTouchInput { up() }
        compose.runOnIdle { assertEquals("XYZ", state.value.text); assertEquals(5, edits) }
    }

    @Test fun `number keyboard touch cancellation replacement and inactive window stop holds`() {
        val active = MutableStateFlow(true)
        val state = mutableStateOf(KeyboardState(text = "123456", cursorPos = 6, layout = KeyboardLayout.Number))
        compose.setContent {
            CompositionLocalProvider(LocalUiActive provides active) {
                KeyboardOverlay(state = state.value, onStateChange = { state.value = it })
            }
        }
        val backspace = compose.onNodeWithText(KEY_BACKSPACE)
        backspace.performTouchInput { down(center) }
        backspace.performTouchInput { cancel() }
        advance(1000)
        compose.runOnIdle { assertEquals("12345", state.value.text) }
        backspace.performTouchInput { down(center) }
        compose.runOnIdle { active.value = false }
        advance(1000)
        compose.runOnIdle { assertEquals("1234", state.value.text) }
        backspace.performTouchInput { up() }
        compose.runOnIdle { active.value = true }
        backspace.performTouchInput { down(center) }
        compose.runOnIdle {
            state.value = KeyboardState(text = "999", cursorPos = 3, layout = KeyboardLayout.Number)
        }
        advance(1000)
        backspace.performTouchInput { up() }
        compose.runOnIdle { assertEquals("999", state.value.text) }
    }

    @Test fun `closing keyboard during touch hold cannot delete in next keyboard`() {
        val visible = mutableStateOf(true)
        val state = mutableStateOf(KeyboardState(text = "before", cursorPos = 6))
        compose.setContent {
            if (visible.value) KeyboardOverlay(state = state.value, onStateChange = { state.value = it })
        }
        compose.onNodeWithText(KEY_BACKSPACE).performTouchInput { down(center) }
        compose.runOnIdle { visible.value = false }
        advance(1000)
        compose.runOnIdle {
            state.value = KeyboardState(text = "next", cursorPos = 4)
            visible.value = true
        }
        advance(1000)
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertEquals("next", state.value.text) }
    }

    @Test fun `hardware backspace uses OS repeats without starting another repeat timer`() {
        val state = mutableStateOf(KeyboardState(text = "abcXYZ", cursorPos = 3))
        lateinit var activity: android.app.Activity
        compose.setContent {
            activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
            KeyboardOverlay(state = state.value, onStateChange = { state.value = it })
        }
        fun key(action: Int, repeat: Int = 0) {
            compose.runOnIdle {
                activity.dispatchKeyEvent(android.view.KeyEvent(
                    0, android.os.SystemClock.uptimeMillis(), action, android.view.KeyEvent.KEYCODE_DEL, repeat,
                ))
            }
        }
        key(android.view.KeyEvent.ACTION_DOWN)
        advance(1000)
        compose.runOnIdle { assertEquals("abXYZ", state.value.text) }
        key(android.view.KeyEvent.ACTION_DOWN, repeat = 1)
        compose.runOnIdle { assertEquals("aXYZ", state.value.text) }
        key(android.view.KeyEvent.ACTION_UP)
        advance(1000)
        compose.runOnIdle { assertEquals("aXYZ", state.value.text) }
    }
}
