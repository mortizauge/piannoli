package dev.piannoli.scorza.util

import android.app.Activity
import android.view.HapticFeedbackConstants
import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.settings.KeyboardType
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.ui.components.KeyboardState
import javax.inject.Inject

@ActivityScoped
class MenuHaptics @Inject constructor(
    private val activity: Activity,
    private val settings: SettingsRepository,
) {
    fun play(action: MenuSound) {
        if (!settings.hapticFeedback) return
        perform(
            when (action) {
                MenuSound.TICK, MenuSound.CONFIRM, MenuSound.BACK -> HapticFeedbackConstants.CONTEXT_CLICK
            }
        )
    }

    /** Feedback for Piannoli keyboard edits made by touch or a hardware keyboard (not controller buttons). */
    fun keyboardChanged(old: KeyboardState, new: KeyboardState) {
        if (old == new || !settings.hapticFeedback) return
        // The system IME already provides its own native key feedback.
        if (settings.keyboardType == KeyboardType.SYSTEM) return
        perform(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun perform(feedback: Int) {
        val view = activity.window.decorView
        // Let Android honor system/view settings and handle devices without haptic support.
        if (!view.performHapticFeedback(feedback)) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}