package dev.piannoli.scorza.input.runtime

import dev.piannoli.scorza.input.CanonicalButton
import dev.piannoli.scorza.input.ListNavigation

/**
 * Latch from the move that reaches the boundary, before the next held input enters a handler.
 * Contexts are immutable state references: checking them never compares or copies list contents.
 */
internal class NavigationRepeatGate {
    class Context(val screen: Any?, val dialog: Any?, val navigation: Any?) {
        fun matches(other: Context): Boolean =
            screen === other.screen && dialog === other.dialog && navigation === other.navigation
    }

    private var blockedButton: CanonicalButton? = null
    private var blockedContext: Context? = null

    fun reset(button: CanonicalButton? = null) {
        if (button == null || button == blockedButton) {
            blockedButton = null
            blockedContext = null
        }
    }

    /** Returns whether this input should produce feedback. */
    fun dispatch(button: CanonicalButton, context: () -> Context, action: () -> Unit): Boolean {
        if (ListNavigation.isRepeat && button == blockedButton && blockedContext?.matches(context()) == true) {
            return false
        }
        reset()
        val result = ListNavigation.observe(action)
        if (result.atBoundary) {
            blockedButton = button
            blockedContext = context()
        }
        return !ListNavigation.isRepeat || !result.usedList || result.moved
    }
}
