package dev.piannoli.scorza.input.runtime

import android.view.KeyEvent
import android.view.MotionEvent
import dev.piannoli.scorza.input.CanonicalButton
import dev.piannoli.scorza.input.DeviceMapping
import dev.piannoli.scorza.input.ListNavigation
import dev.piannoli.scorza.util.MenuSound
import dev.piannoli.ui.components.DeleteKeyRepeat
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class InputDispatcher @Inject constructor(
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val screenInputRegistry: ScreenInputRegistry = ScreenInputRegistry(),
) {

    /** Overridable for tests; production calls System.currentTimeMillis. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    var onUp: () -> Unit = {}
    var onDown: () -> Unit = {}
    var onLeft: () -> Unit = {}
    var onRight: () -> Unit = {}
    var onUpRelease: () -> Unit = {}
    var onDownRelease: () -> Unit = {}
    var onLeftRelease: () -> Unit = {}
    var onRightRelease: () -> Unit = {}
    var onConfirm: () -> Unit = {}
    var onBack: () -> Unit = {}
    var onSelect: () -> Unit = {}
    var onSelectUp: () -> Unit = {}
    var onConfirmUp: () -> Unit = {}
    var onNorthUp: () -> Unit = {}
    var onStart: () -> Unit = {}
    var onL1: () -> Unit = {}
    var onR1: () -> Unit = {}
    var onL2: () -> Unit = {}
    var onR2: () -> Unit = {}
    var onL3: () -> Unit = {}
    var onR3: () -> Unit = {}
    var onWest: () -> Unit = {}
    var onNorth: () -> Unit = {}
    var onMenu: () -> Unit = {}

    // When set, resolves the active screen handler synchronously from nav state instead of the
    // recompose-lagging registry. Set per wiring via wireToRegistry: the launcher passes one, the
    // IGM passes null so it falls back to registry top. Reset on every wireToRegistry call so the
    // last activity to wire wins (the IGM clears the launcher's resolver when it takes over).
    private var screenResolver: (() -> dev.piannoli.scorza.input.ScreenInputHandler)? = null
    private var remap: (CanonicalButton, DeviceMapping) -> CanonicalButton = { button, _ -> button }
    private val pressedActions = mutableMapOf<Pair<Int, CanonicalButton>, CanonicalButton>()
    private val capturedButtons = mutableSetOf<Pair<Int, CanonicalButton>>()
    private var menuKeyAction: CanonicalButton? = null
    private var menuKeyCaptured = false
    private var deleteRepeat: DeleteKeyRepeat? = null
    private var deleteOwner: Pair<Int, CanonicalButton>? = null
    private val keyboardButtons = mutableSetOf<Pair<Int, CanonicalButton>>()
    private var keyboardInputActive: () -> Boolean = { false }
    private var deleteHoldAction: (Boolean) -> (() -> Boolean)? = { null }
    private var deleteFeedback: (MenuSound) -> Unit = {}
    private val navigationRepeat = NavigationRepeatGate()

    fun cancelNavigationRepeat(button: CanonicalButton? = null) {
        navigationRepeat.reset(button)
    }

    fun cancelKeyboardDeleteHold() {
        deleteRepeat?.stop()
        deleteOwner = null
    }

    private fun dispatchPressed(
        deviceId: Int,
        physical: CanonicalButton,
        mapped: CanonicalButton,
        mapping: DeviceMapping,
        allowDeleteRepeat: Boolean = true,
    ): Boolean {
        val key = deviceId to physical
        if (keyboardInputActive()) keyboardButtons.add(key)
        val confirm = mapped == mapping.menuConfirm
        val delete = if (allowDeleteRepeat && (confirm || mapped == mapping.menuBack)) deleteHoldAction(confirm) else null
        if (delete == null) return dispatchMapped(mapped, mapping)
        cancelKeyboardDeleteHold()
        deleteOwner = key
        val repeater = deleteRepeat ?: DeleteKeyRepeat().also { deleteRepeat = it }
        repeater.start {
            val currentMapping = if (deviceId == MENU_DEVICE_ID) {
                activeMappingHolder.active.value ?: DEFAULT_MENU_MAPPING
            } else portRouter.mappingFor(deviceId)
            if (deleteOwner != key || currentMapping != mapping || remap(physical, mapping) != mapped) {
                false
            } else {
                delete().also { changed ->
                    if (changed) deleteFeedback(if (confirm) MenuSound.CONFIRM else MenuSound.BACK)
                }
            }
        }
        return true
    }

    private fun releaseKeyboardButton(deviceId: Int, physical: CanonicalButton) {
        val key = deviceId to physical
        keyboardButtons.remove(key)
        if (deleteOwner == key) cancelKeyboardDeleteHold()
    }

    private fun activeScreen(): dev.piannoli.scorza.input.ScreenInputHandler =
        screenResolver?.invoke() ?: screenInputRegistry.top

    fun handleMenuKey(pressed: Boolean, isRepeat: Boolean = false) {
        if (pressed && isRepeat) return
        val mapping = activeMappingHolder.active.value ?: DEFAULT_MENU_MAPPING
        if (menuKeyCaptured) {
            if (!pressed) menuKeyCaptured = false
            return
        }
        if (pressed && activeScreen().onCaptureButton(CanonicalButton.BTN_MENU, mapping)) {
            menuKeyCaptured = true
            return
        }
        val button = menuKeyAction ?: remap(CanonicalButton.BTN_MENU, mapping)
        if (pressed) {
            menuKeyAction = button
            dispatchPressed(MENU_DEVICE_ID, CanonicalButton.BTN_MENU, button, mapping, allowDeleteRepeat = false)
        } else {
            releaseKeyboardButton(MENU_DEVICE_ID, CanonicalButton.BTN_MENU)
            menuKeyAction = null
            menuKeyCaptured = false
            dispatchReleased(button, mapping)
        }
    }

    fun handleKeyEvent(event: KeyEvent, keyboardNavigation: Boolean = false): Boolean {
        // Raw event hook: screens that need keycode/deviceId/repeatCount (binding capture,
        // shortcut chord capture) override onRawKeyDown/onRawKeyUp and can short-circuit before
        // canonical dispatch.
        val handler = activeScreen()
        if (handler !== dev.piannoli.scorza.input.screen.EmptyScreenInputHandler) {
            val consumed = when (event.action) {
                KeyEvent.ACTION_DOWN -> handler.onRawKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> handler.onRawKeyUp(event.keyCode, event)
                else -> false
            }
            if (consumed) return true
        }
        if (keyboardNavigation && handleKeyboardDirectionForTest(event.deviceId, event.keyCode, event.action, event.repeatCount)) return true
        return handleKeyEventForTest(
            deviceId = event.deviceId,
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
        )
    }

    internal fun handleKeyboardDirectionForTest(deviceId: Int, keyCode: Int, action: Int, repeatCount: Int = 0): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> CanonicalButton.BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> CanonicalButton.BTN_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> CanonicalButton.BTN_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> CanonicalButton.BTN_RIGHT
            else -> return false
        }
        val mapping = portRouter.mappingFor(deviceId)
        val hasKeyBinding = mapping?.bindings?.values?.any { bindings ->
            bindings.any { it is dev.piannoli.scorza.input.InputBinding.Button && it.keyCode == keyCode }
        } == true
        if (hasKeyBinding) return false
        // Hat motion may already have dispatched this direction and owns its held repeat.
        if (portRouter.evaluatorFor(deviceId)?.currentlyPressed()?.contains(direction) == true) return true
        when (action) {
            KeyEvent.ACTION_DOWN, KeyEvent.ACTION_MULTIPLE ->
                ListNavigation.withRepeat(action == KeyEvent.ACTION_MULTIPLE || repeatCount > 0) {
                    dispatchMapped(direction, DEFAULT_MENU_MAPPING)
                }
            KeyEvent.ACTION_UP -> dispatchReleased(direction, DEFAULT_MENU_MAPPING)
        }
        return true
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        val axisValues = device.motionRanges.associate { it.axis to event.getAxisValue(it.axis) }
        return handleMotionEventForTest(event.deviceId, axisValues)
    }

    private val bridgedKeyboardHat = mutableSetOf<Pair<Int, CanonicalButton>>()

    /**
     * Pads like the Xbox controller report the D-pad only as hat axes. The launcher normally
     * relies on Android synthesizing D-pad keys from unhandled hat motion, but the Piannoli
     * keyboard consumes motion, so hat directions are bridged here unless the mapping already
     * pressed the same direction.
     */
    fun handleKeyboardHat(deviceId: Int, keyCode: Int, pressed: Boolean): Boolean {
        val direction = dev.piannoli.scorza.input.HatKeys.canonicalFor(keyCode) ?: return false
        val key = deviceId to direction
        if (!pressed) {
            return bridgedKeyboardHat.remove(key) && dispatchReleased(direction, DEFAULT_MENU_MAPPING)
        }
        if (portRouter.evaluatorFor(deviceId)?.currentlyPressed()?.contains(direction) == true) return true
        val isRepeat = !bridgedKeyboardHat.add(key)
        dev.piannoli.scorza.util.InputLog.write("keyboard hat id=$deviceId -> ${direction.name}")
        return ListNavigation.withRepeat(isRepeat) { dispatchMapped(direction, DEFAULT_MENU_MAPPING) }
    }

    internal fun handleKeyEventForTest(deviceId: Int, keyCode: Int, action: Int, repeatCount: Int): Boolean =
        ListNavigation.withRepeat(action == KeyEvent.ACTION_MULTIPLE || repeatCount > 0) {
            handleKeyEventInternal(deviceId, keyCode, action, repeatCount)
        }

    private fun handleKeyEventInternal(deviceId: Int, keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (isAndroidButtonRepeat(keyCode, action, repeatCount)) return true
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return false
        val evaluator = portRouter.evaluatorFor(deviceId) ?: return false
        val mapping = portRouter.mappingFor(deviceId) ?: return false
        return when (action) {
            KeyEvent.ACTION_DOWN, KeyEvent.ACTION_MULTIPLE -> {
                val isRepeat = action == KeyEvent.ACTION_MULTIPLE || repeatCount > 0
                if (isRepeat) {
                    val direct = evaluator.canonicalsHeldByKeyCode(keyCode)
                    if (direct.any { deviceId to it in capturedButtons }) return true
                    if (direct.any { deviceId to it in keyboardButtons }) return true
                    val fallback = if (direct.isEmpty()) dpadFallbackForRepeat(evaluator, keyCode) else emptyList()
                    val canonicals = (if (direct.isNotEmpty()) direct else fallback)
                        .map { pressedActions[deviceId to it] ?: remap(it, mapping) }
                        .filter { it !in NAV_CANONICALS && it != mapping.menuBack }
                    // Nav auto-repeat is driven by MenuNavigationPoller (held-state poll on
                    // PortRouter). Android keycode auto-repeats for nav buttons are dropped here
                    // so pads that emit both hat motion and synthesized KEYCODE_DPAD_* (GameSir
                    // Pocket Taco) do not double-navigate.
                    if (canonicals.isEmpty()) return direct.any {
                        (pressedActions[deviceId to it] ?: remap(it, mapping)) == mapping.menuBack
                    }
                    maybeActivate(deviceId)
                    activeMappingHolder.set(mapping)
                    var fired = false
                    for (canonical in canonicals) {
                        if (dispatchMapped(canonical, mapping)) fired = true
                    }
                    fired
                } else {
                    val deltas = evaluator.evaluateKeyDown(keyCode, isAndroidRepeat = false)
                    if (deltas.isNotEmpty()) {
                        dev.piannoli.scorza.util.InputLog.write(
                            "key id=$deviceId code=$keyCode -> " + deltas.joinToString(",") {
                                if (it is CanonicalEvent.Pressed) it.button.name else "release"
                            }
                        )
                    } else {
                        dev.piannoli.scorza.util.InputLog.write("key id=$deviceId code=$keyCode -> unbound")
                    }
                    if (deltas.isEmpty()) return false
                    maybeActivate(deviceId)
                    activeMappingHolder.set(mapping)
                    var fired = false
                    for (delta in deltas) {
                        if (delta is CanonicalEvent.Pressed) {
                            if (captureButton(deviceId, delta.button, mapping)) { fired = true; continue }
                            val mapped = pressedActions.getOrPut(deviceId to delta.button) { remap(delta.button, mapping) }
                            if (dispatchPressed(deviceId, delta.button, mapped, mapping,
                                    allowDeleteRepeat = !isAndroidButton(keyCode))) fired = true
                        }
                    }
                    fired
                }
            }
            KeyEvent.ACTION_UP -> {
                val deltas = evaluator.evaluateKeyUp(keyCode)
                if (deltas.isEmpty()) return false
                var fired = false
                for (delta in deltas) {
                    if (delta !is CanonicalEvent.Released) continue
                    releaseKeyboardButton(deviceId, delta.button)
                    if (capturedButtons.remove(deviceId to delta.button)) { fired = true; continue }
                    val mapped = pressedActions.remove(deviceId to delta.button) ?: remap(delta.button, mapping)
                    if (dispatchReleased(mapped, mapping)) fired = true
                }
                fired
            }
            else -> false
        }
    }

    internal fun handleMotionEventForTest(deviceId: Int, axisValues: Map<Int, Float>): Boolean {
        val evaluator = portRouter.evaluatorFor(deviceId) ?: return false
        val mapping = portRouter.mappingFor(deviceId) ?: return false
        val deltas = evaluator.evaluateAxis(axisValues)
        if (deltas.isEmpty()) return false
        var fired = false
        for (delta in deltas) {
            if (delta is CanonicalEvent.Pressed) {
                maybeActivate(deviceId)
                activeMappingHolder.set(mapping)
                if (captureButton(deviceId, delta.button, mapping)) { fired = true; continue }
                val mapped = pressedActions.getOrPut(deviceId to delta.button) { remap(delta.button, mapping) }
                if (dispatchPressed(deviceId, delta.button, mapped, mapping)) fired = true
            } else if (delta is CanonicalEvent.Released) {
                releaseKeyboardButton(deviceId, delta.button)
                if (capturedButtons.remove(deviceId to delta.button)) { fired = true; continue }
                val mapped = pressedActions.remove(deviceId to delta.button) ?: remap(delta.button, mapping)
                if (dispatchReleased(mapped, mapping)) fired = true
            }
        }
        return fired
    }

    private fun maybeActivate(deviceId: Int) {
        if (!portRouter.isActivated(deviceId)) {
            portRouter.activate(deviceId, clock())
        }

    }

    private fun captureButton(deviceId: Int, button: CanonicalButton, mapping: DeviceMapping): Boolean {
        if (!activeScreen().onCaptureButton(button, mapping)) return false
        capturedButtons.add(deviceId to button)
        return true
    }

    private fun dpadFallbackForRepeat(evaluator: PortEvaluator, keyCode: Int): List<CanonicalButton> {
        val canonical = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> CanonicalButton.BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> CanonicalButton.BTN_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> CanonicalButton.BTN_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> CanonicalButton.BTN_RIGHT
            else -> return emptyList()
        }
        return if (evaluator.currentlyPressed().contains(canonical)) listOf(canonical) else emptyList()
    }

    private fun dispatchReleased(button: CanonicalButton, mapping: DeviceMapping): Boolean {
        cancelNavigationRepeat(button)
        when (button) {
            mapping.menuConfirm -> onConfirmUp()
            CanonicalButton.BTN_SELECT -> onSelectUp()
            CanonicalButton.BTN_NORTH -> onNorthUp()
            CanonicalButton.BTN_UP -> onUpRelease()
            CanonicalButton.BTN_DOWN -> onDownRelease()
            CanonicalButton.BTN_LEFT -> onLeftRelease()
            CanonicalButton.BTN_RIGHT -> onRightRelease()
            else -> return false
        }
        return true
    }

    private fun dispatchMapped(canonical: CanonicalButton, mapping: DeviceMapping): Boolean {
        when (canonical) {
            CanonicalButton.BTN_UP -> onUp()
            CanonicalButton.BTN_DOWN -> onDown()
            CanonicalButton.BTN_LEFT -> onLeft()
            CanonicalButton.BTN_RIGHT -> onRight()
            CanonicalButton.BTN_EAST -> when {
                mapping.menuConfirm == CanonicalButton.BTN_EAST -> onConfirm()
                mapping.menuBack == CanonicalButton.BTN_EAST -> onBack()
                else -> return false
            }
            CanonicalButton.BTN_SOUTH -> when {
                mapping.menuConfirm == CanonicalButton.BTN_SOUTH -> onConfirm()
                mapping.menuBack == CanonicalButton.BTN_SOUTH -> onBack()
                else -> return false
            }
            CanonicalButton.BTN_WEST -> onWest()
            CanonicalButton.BTN_NORTH -> onNorth()
            CanonicalButton.BTN_L -> onL1()
            CanonicalButton.BTN_R -> onR1()
            CanonicalButton.BTN_L2 -> onL2()
            CanonicalButton.BTN_R2 -> onR2()
            CanonicalButton.BTN_L3 -> onL3()
            CanonicalButton.BTN_R3 -> onR3()
            CanonicalButton.BTN_START -> onStart()
            CanonicalButton.BTN_SELECT -> onSelect()
            CanonicalButton.BTN_MENU -> onMenu()
        }
        return true
    }

    /**
     * Wires the dispatcher's canonical-event callbacks to its [ScreenInputRegistry]. Every
     * frontend activity calls this during initialization to share one wiring path.
     *
     * [dialogHandler] is consulted before the registry's top handler. The handler
     * returns true to consume the event; on false, dispatch falls through to the screen handler.
     *
     * The launcher passes [onSelectUpOverride] because it owns select-hold nav-flag state that
     * the generic helper cannot know about; on the IGM and in tests the override stays null and
     * the helper's default body (cancel-hold then dialog-or-screen) is used.
     */
    fun wireToRegistry(
        dialogHandler: dev.piannoli.scorza.input.DialogPrecedence? = null,
        onSelectUpOverride: (() -> Unit)? = null,
        screenResolver: (() -> dev.piannoli.scorza.input.ScreenInputHandler)? = null,
        onMenuSound: (MenuSound) -> Unit = {},
        remap: (CanonicalButton, DeviceMapping) -> CanonicalButton = { button, _ -> button },
        navigationContext: () -> Any? = { null },
    ) {
        this.screenResolver = screenResolver
        this.remap = remap
        cancelNavigationRepeat()
        cancelKeyboardDeleteHold()
        keyboardButtons.clear()
        keyboardInputActive = { dialogHandler?.keyboardInputActive == true }
        deleteHoldAction = { confirm -> dialogHandler?.deleteHoldAction(confirm) }
        deleteFeedback = onMenuSound
        pressedActions.clear()
        capturedButtons.clear()
        menuKeyAction = null
        fun screen(): dev.piannoli.scorza.input.ScreenInputHandler = activeScreen()
        fun sounding(sound: MenuSound, action: () -> Unit): () -> Unit = {
            cancelKeyboardDeleteHold()
            onMenuSound(sound)
            action()
        }
        fun navigating(button: CanonicalButton, action: () -> Unit): () -> Unit = {
            val feedback = navigationRepeat.dispatch(
                button,
                { NavigationRepeatGate.Context(screen().navigationContext, dialogHandler?.navigationContext, navigationContext()) },
            ) {
                cancelKeyboardDeleteHold()
                action()
            }
            if (feedback) onMenuSound(MenuSound.TICK)
        }
        onUp = navigating(CanonicalButton.BTN_UP) { if (dialogHandler?.onUp() != true) screen().onUp() }
        onDown = navigating(CanonicalButton.BTN_DOWN) { if (dialogHandler?.onDown() != true) screen().onDown() }
        onLeft = navigating(CanonicalButton.BTN_LEFT) { if (dialogHandler?.onLeft() != true) screen().onLeft() }
        onRight = navigating(CanonicalButton.BTN_RIGHT) { if (dialogHandler?.onRight() != true) screen().onRight() }
        onUpRelease = { screen().onUpRelease() }
        onDownRelease = { screen().onDownRelease() }
        onLeftRelease = { screen().onLeftRelease() }
        onRightRelease = { screen().onRightRelease() }
        onConfirm = sounding(MenuSound.CONFIRM) { if (dialogHandler?.onConfirm() != true) screen().onConfirm() }
        onBack = sounding(MenuSound.BACK) { if (dialogHandler?.onBack() != true) screen().onBack() }
        onStart = sounding(MenuSound.CONFIRM) { if (dialogHandler?.onStart() != true) screen().onStart() }
        onSelect = { if (dialogHandler?.onSelect() != true) screen().onSelect() }
        onSelectUp = onSelectUpOverride ?: {
            dialogHandler?.cancelSelectHold()
            if (dialogHandler?.onSelectUp() != true) screen().onSelectUp()
        }
        onConfirmUp = { if (dialogHandler?.onConfirmUp() != true) screen().onConfirmUp() }
        onNorthUp = { if (dialogHandler?.onNorthUp() != true) screen().onNorthUp() }
        onNorth = sounding(MenuSound.CONFIRM) { if (dialogHandler?.onNorth() != true) screen().onNorth() }
        onWest = sounding(MenuSound.BACK) { if (dialogHandler?.onWest() != true) screen().onWest() }
        onL1 = navigating(CanonicalButton.BTN_L) { if (dialogHandler?.onL1() != true) screen().onL1() }
        onR1 = navigating(CanonicalButton.BTN_R) { if (dialogHandler?.onR1() != true) screen().onR1() }
        onL2 = { if (dialogHandler?.onL2() != true) screen().onL2() }
        onR2 = { if (dialogHandler?.onR2() != true) screen().onR2() }
        onL3 = { if (dialogHandler?.onL3() != true) screen().onL3() }
        onR3 = { if (dialogHandler?.onR3() != true) screen().onR3() }
        onMenu = sounding(MenuSound.CONFIRM) { if (dialogHandler?.onMenu() != true) screen().onMenu() }
    }

    companion object {
        private fun isAndroidButton(keyCode: Int): Boolean =
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_MODE

        internal fun isAndroidButtonRepeat(keyCode: Int, action: Int, repeatCount: Int): Boolean =
            isAndroidButton(keyCode) &&
                (action == KeyEvent.ACTION_MULTIPLE || action == KeyEvent.ACTION_DOWN && repeatCount > 0)

        private const val MENU_DEVICE_ID = Int.MIN_VALUE
        private val DEFAULT_MENU_MAPPING = DeviceMapping(
            id = "menu-key",
            displayName = "Menu key",
            match = dev.piannoli.scorza.input.DeviceMatchRule(),
            bindings = emptyMap(),
            source = dev.piannoli.scorza.input.MappingSource.ANDROID_DEFAULT,
        )
        private val NAV_CANONICALS = setOf(
            CanonicalButton.BTN_UP,
            CanonicalButton.BTN_DOWN,
            CanonicalButton.BTN_LEFT,
            CanonicalButton.BTN_RIGHT,
        )
    }
}
