package dev.piannoli.scorza.input

import android.view.KeyEvent

interface ScreenInputHandler {
    /** Immutable list state reference; replacing it releases a held navigation boundary latch. */
    val navigationContext: Any? get() = this
    fun onCaptureButton(button: CanonicalButton, mapping: DeviceMapping): Boolean = false
    fun onUp() {}
    fun onDown() {}
    fun onLeft() {}
    fun onRight() {}
    fun onUpRelease() {}
    fun onDownRelease() {}
    fun onLeftRelease() {}
    fun onRightRelease() {}
    fun onConfirm() {}
    fun onBack() {}
    fun onStart() {}
    fun onSelect() {}
    fun onSelectUp() {}
    fun onConfirmUp() {}
    fun onNorthUp() {}
    fun onNorth() {}
    fun onWest() {}
    fun onL1() {}
    fun onR1() {}
    fun onL2() {}
    fun onR2() {}
    fun onL3() {}
    fun onR3() {}
    fun onMenu() {}

    fun onRawKeyDown(keyCode: Int, event: KeyEvent): Boolean = false
    fun onRawKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
}
