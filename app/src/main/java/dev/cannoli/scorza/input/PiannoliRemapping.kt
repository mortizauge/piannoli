package dev.cannoli.scorza.input

import dev.cannoli.ui.MenuAction

object PiannoliRemapping {
    fun remappableButton(button: CanonicalButton, mapping: DeviceMapping): MenuAction? =
        MenuAction.remappable.firstOrNull { canonical(it, mapping) == button }

    private fun canonical(action: MenuAction, mapping: DeviceMapping): CanonicalButton = when (action) {
        MenuAction.CONFIRM -> mapping.menuConfirm
        MenuAction.BACK -> mapping.menuBack
        MenuAction.WEST -> CanonicalButton.BTN_WEST
        MenuAction.NORTH -> CanonicalButton.BTN_NORTH
        MenuAction.START -> CanonicalButton.BTN_START
        MenuAction.SELECT -> CanonicalButton.BTN_SELECT
        MenuAction.MENU -> CanonicalButton.BTN_MENU
        MenuAction.L1 -> CanonicalButton.BTN_L
        MenuAction.R1 -> CanonicalButton.BTN_R
        MenuAction.L2 -> CanonicalButton.BTN_L2
        MenuAction.R2 -> CanonicalButton.BTN_R2
        MenuAction.L3 -> CanonicalButton.BTN_L3
        MenuAction.R3 -> CanonicalButton.BTN_R3
    }

    fun translate(button: CanonicalButton, mapping: DeviceMapping, bindings: Map<MenuAction, MenuAction>): CanonicalButton {
        if (bindings.isEmpty()) return button
        val physical = MenuAction.entries.firstOrNull { canonical(it, mapping) == button } ?: return button
        val action = MenuAction.entries.first { (bindings[it] ?: it) == physical }
        return canonical(action, mapping)
    }
}
