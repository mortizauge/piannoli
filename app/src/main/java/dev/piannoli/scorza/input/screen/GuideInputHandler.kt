package dev.piannoli.scorza.input.screen

import dev.piannoli.scorza.input.ListNavigation

import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.igm.GuideController
import dev.piannoli.igm.GuideFile
import dev.piannoli.igm.GuideManager
import dev.piannoli.igm.GuideType
import dev.piannoli.igm.GuideZoom
import dev.piannoli.scorza.input.ScreenInputHandler
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.ui.screens.DialogState
import javax.inject.Inject

@ActivityScoped
class GuideInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val settings: SettingsRepository,
) : ScreenInputHandler {

    val controller = GuideController()

    fun startGuides(rom: Rom) {
        controller.attach(GuideManager(settings.sdCardRoot, rom.platformTag, rom.path.nameWithoutExtension))
        val files = controller.guideFiles.value
        nav.dialogState.value = DialogState.None
        when {
            files.isEmpty() -> {}
            files.size == 1 -> openGuide(files[0])
            else -> nav.push(LauncherScreen.GuidePicker(files))
        }
    }

    private fun openGuide(guide: GuideFile) {
        val open = controller.prepareGuide(guide) ?: return
        nav.push(
            LauncherScreen.Guide(
                filePath = open.filePath,
                guideType = guide.type,
                page = open.initialPage,
                textZoom = open.textZoom,
            )
        )
    }

    private fun picker(): LauncherScreen.GuidePicker? = nav.currentScreen as? LauncherScreen.GuidePicker
    private fun guide(): LauncherScreen.Guide? = nav.currentScreen as? LauncherScreen.Guide

    override fun onUp() {
        val p = picker()
        if (p != null) {
            if (p.files.isEmpty()) return
            nav.replaceTop(p.copy(selectedIndex = ListNavigation.move(p.selectedIndex, -1, p.files.size)))
            return
        }
        if (guide() != null) controller.scroll(-1)
    }

    override fun onDown() {
        val p = picker()
        if (p != null) {
            if (p.files.isEmpty()) return
            nav.replaceTop(p.copy(selectedIndex = ListNavigation.move(p.selectedIndex, 1, p.files.size)))
            return
        }
        if (guide() != null) controller.scroll(1)
    }

    override fun onUpRelease() { if (guide() != null) controller.scroll(0) }
    override fun onDownRelease() { if (guide() != null) controller.scroll(0) }

    override fun onLeft() {
        val g = guide() ?: return
        if (g.guideType != GuideType.TXT && g.textZoom > 1) controller.scrollX(-1)
    }

    override fun onRight() {
        val g = guide() ?: return
        if (g.guideType != GuideType.TXT && g.textZoom > 1) controller.scrollX(1)
    }

    override fun onLeftRelease() { if (guide() != null) controller.scrollX(0) }
    override fun onRightRelease() { if (guide() != null) controller.scrollX(0) }

    override fun onL1() {
        val g = guide() ?: return
        if (g.guideType == GuideType.PDF) {
            nav.replaceTop(g.copy(page = (g.page - 1).coerceAtLeast(0)))
        } else {
            controller.pageJump(-1)
        }
    }

    override fun onR1() {
        val g = guide() ?: return
        if (g.guideType == GuideType.PDF) {
            nav.replaceTop(g.copy(page = (g.page + 1).coerceAtMost(controller.guidePageCount.intValue - 1)))
        } else {
            controller.pageJump(1)
        }
    }

    override fun onNorth() {
        val g = guide() ?: return
        controller.beginZoomReseed()
        nav.replaceTop(g.copy(textZoom = if (g.textZoom >= GuideZoom.levels) 1 else g.textZoom + 1))
    }

    override fun onConfirm() {
        val p = picker() ?: return
        p.files.getOrNull(p.selectedIndex)?.let { openGuide(it) }
    }

    override fun onBack() {
        val g = guide()
        if (g != null) {
            val guideFile = controller.guideFiles.value.firstOrNull { it.file.absolutePath == g.filePath }
            if (guideFile != null) {
                controller.saveGuide(guideFile, if (g.guideType == GuideType.PDF) g.page else null, g.textZoom)
            }
            controller.scroll(0)
            controller.scrollX(0)
            nav.pop()
            return
        }
        if (picker() != null) nav.pop()
    }
}
