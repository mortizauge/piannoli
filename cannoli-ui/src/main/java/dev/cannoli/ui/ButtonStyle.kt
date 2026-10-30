package dev.cannoli.ui

data class ButtonStyle(
    val labelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER,
    val confirmButton: ConfirmButton = ConfirmButton.EAST,
    val bindings: Map<MenuAction, MenuAction> = emptyMap(),
) {
    val south: String get() = labelSet.south
    val east: String get() = labelSet.east
    val west: String get() = label(MenuAction.WEST)
    val north: String get() = label(MenuAction.NORTH)
    val start: String get() = label(MenuAction.START)
    val select: String get() = label(MenuAction.SELECT)
    val menu: String get() = label(MenuAction.MENU)
    val l1: String get() = label(MenuAction.L1)
    val r1: String get() = label(MenuAction.R1)
    val l2: String get() = label(MenuAction.L2)
    val r2: String get() = label(MenuAction.R2)

    val confirm: String get() = label(MenuAction.CONFIRM)
    val back: String get() = label(MenuAction.BACK)

    fun label(action: MenuAction): String = physicalLabel(bindings[action] ?: action)

    fun physicalLabel(button: MenuAction): String = when (button) {
        MenuAction.CONFIRM -> if (confirmButton == ConfirmButton.SOUTH) labelSet.south else labelSet.east
        MenuAction.BACK -> if (confirmButton == ConfirmButton.SOUTH) labelSet.east else labelSet.south
        MenuAction.WEST -> labelSet.west
        MenuAction.NORTH -> labelSet.north
        MenuAction.START -> START_GLYPH
        MenuAction.MENU -> MENU_GLYPH
        else -> button.name
    }
}
