package dev.piannoli.scorza.input

import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.DPAD_HORIZONTAL
import dev.piannoli.ui.MenuAction
import io.mockk.mockk
import io.mockk.every
import io.mockk.verifySequence
import io.mockk.verify
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.input.screen.SystemListInputHandler
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.util.MenuSound
import dev.piannoli.scorza.util.MenuSounds
import dev.piannoli.scorza.util.MenuHaptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherTouchInputTest {
    @Test fun `touch selection and actions give feedback after navigation without controller interruption`() = runTest {
        val nav = mockk<NavigationController>(relaxed = true)
        val dialogs = MutableStateFlow<DialogState>(DialogState.None)
        every { nav.dialogState } returns dialogs
        every { nav.currentScreen } returns LauncherScreen.SystemList
        every { nav.navigating } returns false
        val handler = mockk<SystemListInputHandler>(relaxed = true)
        var state = Any()
        every { handler.navigationContext } answers { state }
        every { handler.selectTouchItem(2) } answers { state = Any() }
        every { handler.onConfirm() } answers { dialogs.value = DialogState.QuitConfirm }
        val sounds = mockk<MenuSounds>(relaxed = true)
        val haptics = mockk<MenuHaptics>(relaxed = true)
        val router = testRouter(nav, handler, sounds, haptics)
        var controllerEvents = 0
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            router.controllerInput.collect { controllerEvents++ }
        }
        router.touchSelect(2, 0)
        router.touchSelect(2, 2)
        router.touchSelect(99, 2)
        router.touchAction(null)
        router.touchAction(MenuAction.CONFIRM)
        verifySequence {
            handler.navigationContext
            handler.selectTouchItem(2)
            handler.navigationContext
            sounds.play(MenuSound.TICK)
            haptics.play(MenuSound.TICK)
            handler.navigationContext
            handler.selectTouchItem(99)
            handler.navigationContext
            handler.navigationContext
            handler.onRight()
            handler.navigationContext
            handler.navigationContext
            handler.onConfirm()
            handler.onConfirmUp()
            handler.navigationContext
            sounds.play(MenuSound.CONFIRM)
            haptics.play(MenuSound.CONFIRM)
        }
        assertEquals(0, controllerEvents)
        collector.cancel()
    }

    private fun testRouter(
        nav: NavigationController,
        handler: SystemListInputHandler,
        sounds: MenuSounds,
        haptics: MenuHaptics,
    ) = InputRouter(
        nav = nav,
        dialogHandler = mockk(relaxed = true),
        systemListHandler = handler,
        gameListHandler = mockk(relaxed = true),
        settingsHandler = mockk(relaxed = true),
        onboardingHandler = mockk(relaxed = true),
        inputTesterHandler = mockk(relaxed = true),
        saveStatePickerHandler = mockk(relaxed = true),
        saveSlotsHandler = mockk(relaxed = true),
        guideHandler = mockk(relaxed = true),
        controllerDetailHandler = mockk(relaxed = true),
        controllersHandler = mockk(relaxed = true),
        editButtonsHandler = mockk(relaxed = true),
        loggingSettingsHandler = mockk(relaxed = true),
        scrollListFactory = mockk(relaxed = true),
        platformConfig = mockk(relaxed = true),
        gameOverrideStore = mockk(relaxed = true),
        installedCoreService = mockk(relaxed = true),
        emulatorMappingBuilder = mockk(relaxed = true),
        launcherActions = mockk(relaxed = true),
        screenInputRegistry = mockk(relaxed = true),
        context = mockk(relaxed = true),
        settings = mockk(relaxed = true),
        coreInstaller = mockk(relaxed = true),
        rommBrowseViewModel = mockk(relaxed = true),
        rommDownloader = mockk(relaxed = true),
        osdController = mockk(relaxed = true),
        raPreloadController = mockk(relaxed = true),
        ioScope = mockk(relaxed = true),
        menuSounds = sounds,
        menuHaptics = haptics,
    )

    @Test fun `hints resolve swapped bindings once and horizontal hint cycles right`() {
        val router = mockk<InputRouter>(relaxed = true)
        val buttons = ButtonStyle(bindings = MenuAction.swap(emptyMap(), MenuAction.CONFIRM, MenuAction.NORTH))
        val touch = LauncherTouchInput(router, buttons)
        touch.hint(buttons.confirm)
        touch.hint(buttons.back)
        touch.hint(buttons.menu)
        touch.hint(DPAD_HORIZONTAL)
        verify(exactly = 1) { router.touchAction(MenuAction.CONFIRM) }
        verify(exactly = 1) { router.touchAction(MenuAction.BACK) }
        verify(exactly = 1) { router.touchAction(MenuAction.MENU) }
        verify(exactly = 1) { router.touchAction(null) }
        verify(exactly = 0) { router.touchAction(MenuAction.NORTH) }
    }

    @Test fun `touch context uses only context capable screens`() {
        val router = mockk<InputRouter>(relaxed = true)
        val touch = LauncherTouchInput(router, ButtonStyle())
        touch.select(3, 0)
        touch.contextMenu()
        touch.activate(false)
        verify { router.touchSelect(3, 0) }
        verify { router.touchContextMenu() }
        verify { router.touchAction(MenuAction.CONFIRM) }
        verify(exactly = 0) { router.touchAction(MenuAction.START) }
    }
}
