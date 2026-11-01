package dev.piannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.input.ListNavigation
import dev.piannoli.scorza.input.ScreenInputHandler
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import javax.inject.Inject

@ActivityScoped
class SaveStatePickerInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val launcherActions: LauncherActions,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.SaveStatePicker? =
        nav.currentScreen as? LauncherScreen.SaveStatePicker

    override fun onLeft() = move(-1)
    override fun onRight() = move(1)

    private fun move(delta: Int) {
        val s = current() ?: return
        val next = ListNavigation.move(s.selectedSlotIndex, delta, 11)
        nav.replaceTop(s.copy(selectedSlotIndex = next))
    }

    override fun onConfirm() {
        val s = current() ?: return
        if (s.awaitConfirmRelease) return
        if (!s.slotOccupied.getOrElse(s.selectedSlotIndex) { false }) return
        val error = launcherActions.launchRomFromSlot(s.rom, s.selectedSlotIndex)
        if (error != null) {
            nav.dialogState.value = error
        } else {
            if (s.rom.platformTag != "tools") {
                launcherActions.recordRecentlyPlayedByPath(s.rom.path.absolutePath)
            }
            nav.pop()
        }
    }

    override fun onConfirmUp() {
        val s = current() ?: return
        if (s.awaitConfirmRelease) nav.replaceTop(s.copy(awaitConfirmRelease = false))
    }

    override fun onBack() {
        nav.pop()
    }
}
