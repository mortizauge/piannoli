package dev.piannoli.scorza.input

import android.view.KeyEvent

/**
 * Bridges controller hat axes to Android D-pad keycodes for the frontend keyboard.
 */
object HatKeys {
    const val PRESS_THRESHOLD = 0.5f
    const val RELEASE_THRESHOLD = 0.3f

    fun keyCodeFor(button: CanonicalButton): Int? = when (button) {
        CanonicalButton.BTN_UP -> KeyEvent.KEYCODE_DPAD_UP
        CanonicalButton.BTN_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        CanonicalButton.BTN_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        CanonicalButton.BTN_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> null
    }

    fun canonicalFor(keyCode: Int): CanonicalButton? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> CanonicalButton.BTN_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> CanonicalButton.BTN_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> CanonicalButton.BTN_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> CanonicalButton.BTN_RIGHT
        else -> null
    }
}

/**
 * Per-device hat state with press/release hysteresis, emitting D-pad keycode down/up events.
 * Lets the frontend keyboard accept a hat D-pad just like a keycode D-pad.
 */
class HatKeySync {

    private val heldByDevice = mutableMapOf<Int, MutableSet<Int>>()

    fun sync(deviceId: Int, hatX: Float, hatY: Float, onDown: (Int) -> Unit, onUp: (Int) -> Unit) {
        update(deviceId, KeyEvent.KEYCODE_DPAD_LEFT, -hatX, onDown, onUp)
        update(deviceId, KeyEvent.KEYCODE_DPAD_RIGHT, hatX, onDown, onUp)
        update(deviceId, KeyEvent.KEYCODE_DPAD_UP, -hatY, onDown, onUp)
        update(deviceId, KeyEvent.KEYCODE_DPAD_DOWN, hatY, onDown, onUp)
    }

    fun releaseAll(deviceId: Int, onUp: (Int) -> Unit) {
        heldByDevice.remove(deviceId)?.forEach(onUp)
    }

    fun reset() {
        heldByDevice.clear()
    }

    private fun update(
        deviceId: Int,
        keyCode: Int,
        value: Float,
        onDown: (Int) -> Unit,
        onUp: (Int) -> Unit,
    ) {
        val held = heldByDevice.getOrPut(deviceId) { mutableSetOf() }
        val wasHeld = keyCode in held
        if (value > HatKeys.PRESS_THRESHOLD && !wasHeld) {
            held.add(keyCode)
            onDown(keyCode)
        } else if (value < HatKeys.RELEASE_THRESHOLD && wasHeld) {
            held.remove(keyCode)
            onUp(keyCode)
        }
    }
}
