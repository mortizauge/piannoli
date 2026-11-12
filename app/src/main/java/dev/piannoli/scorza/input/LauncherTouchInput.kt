package dev.piannoli.scorza.input

import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.DPAD_HORIZONTAL
import dev.piannoli.ui.MenuAction
import dev.piannoli.ui.components.TouchInput

class LauncherTouchInput(
    private val router: InputRouter,
    private val buttons: ButtonStyle,
) : TouchInput {
    override val controllerInput get() = router.controllerInput
    override fun beginNavigation() = router.beginTouchNavigation()
    override fun select(index: Int, currentIndex: Int) {
        router.touchSelect(index, currentIndex)
    }

    override fun activate(cycle: Boolean) {
        if (cycle) router.touchAction(null) else router.touchAction(MenuAction.CONFIRM)
    }

    override fun contextMenu() {
        router.touchContextMenu()
    }

    override fun hint(glyph: String) {
        if (glyph == DPAD_HORIZONTAL) router.touchAction(null)
        else MenuAction.entries.firstOrNull { buttons.label(it) == glyph }?.let(router::touchAction)
    }
}
