package dev.piannoli.scorza.util

import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import dev.piannoli.scorza.settings.KeyboardType
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.ui.components.KeyboardController
import dev.piannoli.ui.components.KeyboardPress
import dev.piannoli.ui.components.KeyboardState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class MenuHapticsTest {
    private val view = mockk<View>()
    private val window = mockk<Window> { every { decorView } returns view }
    private val activity = mockk<Activity>()
    private val settings = mockk<SettingsRepository> {
        every { hapticFeedback } returns false
        every { navigationSound } returns false
        every { keyboardType } returns KeyboardType.PIANNOLI
    }
    private val haptics = MenuHaptics(activity, settings)

    init {
        every { activity.window } returns window
    }

    @Test fun `feedback toggles immediately independently of navigation sound`() {
        for (action in MenuSound.entries) haptics.play(action)
        verify(exactly = 0) { activity.window }
        every { settings.hapticFeedback } returns true
        every { view.performHapticFeedback(any<Int>()) } returns true
        for (action in MenuSound.entries) haptics.play(action)
        every { settings.hapticFeedback } returns false
        every { settings.navigationSound } returns true
        for (action in MenuSound.entries) haptics.play(action)

        verifySequence {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        verify(exactly = 0) { view.performHapticFeedback(any(), any()) }
        verify(exactly = 0) { settings.navigationSound }
    }

    @Test fun `unsupported or system disabled feedback uses a bounded standard fallback without ignore flags`() {
        every { settings.hapticFeedback } returns true
        every { view.performHapticFeedback(any<Int>()) } returns false
        for (action in MenuSound.entries) haptics.play(action)

        verifySequence {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        verify(exactly = 0) { view.performHapticFeedback(any(), any()) }
    }

    @Test fun `piannoli keyboard edits and selection changes use the same haptic`() {
        every { settings.hapticFeedback } returns true
        every { view.performHapticFeedback(any<Int>()) } returns true
        val empty = KeyboardState(keyRow = 1, keyCol = 0)
        val typed = (KeyboardController.press(empty) as KeyboardPress.Update).state
        val spaced = KeyboardController.insertChar(typed, " ")
        val deleted = KeyboardController.backspace(spaced)
        val shifted = typed.copy(caps = true)
        val moved = typed.copy(keyCol = 1)
        val cursorMoved = typed.copy(cursorPos = 0)

        haptics.keyboardChanged(empty, typed)
        haptics.keyboardChanged(typed, spaced)
        haptics.keyboardChanged(spaced, deleted)
        haptics.keyboardChanged(typed, shifted)
        haptics.keyboardChanged(typed, moved)
        haptics.keyboardChanged(typed, cursorMoved)
        haptics.keyboardChanged(typed, typed)

        verifySequence {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    @Test fun `keyboard typing haptics respect the toggle and leave system keyboard feedback to the IME`() {
        val typed = KeyboardState(text = "a", cursorPos = 1)
        haptics.keyboardChanged(KeyboardState(), typed)
        every { settings.hapticFeedback } returns true
        every { settings.keyboardType } returns KeyboardType.SYSTEM
        haptics.keyboardChanged(KeyboardState(), typed)

        verify(exactly = 0) { activity.window }
    }
}
