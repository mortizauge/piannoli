package dev.piannoli.scorza.ui

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Indication
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.ui.DPAD_HORIZONTAL
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.LegendPill
import dev.piannoli.ui.components.ConfirmOverlay
import dev.piannoli.ui.components.List
import dev.piannoli.ui.components.LocalTouchInput
import dev.piannoli.ui.components.LocalAnimationsEnabled
import dev.piannoli.ui.components.AnimationMode
import dev.piannoli.ui.components.LocalUiActive
import dev.piannoli.ui.components.PillRowKeyValue
import dev.piannoli.ui.components.PillRowText
import dev.piannoli.ui.components.SectionedList
import dev.piannoli.ui.components.ListSection
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.TouchInput
import dev.piannoli.ui.components.TouchFlingBehavior
import dev.piannoli.ui.components.screenVerticalScroll
import dev.piannoli.ui.components.activateSelectedItemOnTap
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.ui.theme.PiannoliColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp")
class TouchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val selected = mutableIntStateOf(0)
    private lateinit var listState: LazyListState
    private val actions = mutableListOf<String>()
    private val controllerInputEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val animationMode = mutableStateOf(AnimationMode.FULL)
    private val touchInputActive = mutableStateOf(false)
    private val renderedSelections = mutableMapOf<String, Boolean>()
    private val touch = object : TouchInput {
        override val controllerInput = controllerInputEvents
        override fun beginNavigation() { touchInputActive.value = true }
        override fun select(index: Int, currentIndex: Int) { selected.intValue = index }
        override fun activate(cycle: Boolean) { actions += "${if (cycle) "cycle" else "open"}:${selected.intValue}" }
        override fun contextMenu() { actions += "context:${selected.intValue}" }
        override fun hint(glyph: String) { actions += glyph }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun renderAnimationModes(
        activity: StateFlow<Boolean>? = null,
        initialIndex: Int = 0,
        disablePrefetch: Boolean = false,
    ) {
        compose.setContent {
            LaunchedEffect(Unit) { controllerInputEvents.collect { touchInputActive.value = false } }
            val animationsEnabled = animationMode.value.enabled(touchInputActive.value)
            CompositionLocalProvider(
                LocalTouchInput provides touch,
                LocalUiActive provides (activity ?: LocalUiActive.current),
                LocalAnimationsEnabled provides animationsEnabled,
                dev.piannoli.ui.components.LocalAnimationMode provides animationMode.value,
                LocalIndication provides if (animationsEnabled) LocalIndication.current
                    else dev.piannoli.ui.components.NoAnimationIndication,
                LocalPiannoliColors provides PiannoliColors(text = Color.Red, highlightText = Color.Blue),
            ) {
                ScreenBackground(modifier = Modifier.testTag("surface")) {
                    // Native Robolectric prefetch can spin on vsync when the test clock is paused.
                    listState = if (disablePrefetch) rememberLazyListState(
                        initialFirstVisibleItemIndex = initialIndex,
                        prefetchStrategy = androidx.compose.runtime.remember {
                            object : LazyListPrefetchStrategy {
                                override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {}
                                override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {}
                                override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {}
                            }
                        },
                    ) else rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                    List(
                        items = (0 until 100).map { "Game $it" },
                        selectedIndex = selected.intValue,
                        listState = listState,
                        scrollTarget = initialIndex,
                        modifier = Modifier.width(220.dp).height(200.dp).testTag("modeList"),
                    ) { _, item, isSelected ->
                        androidx.compose.runtime.SideEffect { renderedSelections[item] = isSelected }
                        PillRowText(item, isSelected, 16.sp, 24.sp, 8.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `backgrounding removes animation rendering and restores it without losing selection`() {
        val activity = MutableStateFlow(true)
        renderAnimationModes(activity)
        assertEquals("idle highlights must not retain a separate animation observer", 3, activity.subscriptionCount.value)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Game 1").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { activity.value = false }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.onNodeWithTag("listSelectionHighlight").assertDoesNotExist()
        assertEquals(1, selected.intValue)
        compose.runOnIdle { activity.value = true }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.onNodeWithTag("listSelectionHighlight").assertExists()
        assertTrue(textPixels("Game 1").blue > 0)
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals("completed transitions must release their activity observer", 3, activity.subscriptionCount.value)
        compose.mainClock.autoAdvance = true
    }

    @Test fun `Off mode keeps touch selection and settling without a weighted animated pill`() {
        animationMode.value = AnimationMode.OFF
        renderAnimationModes()
        compose.onNodeWithTag("surface").performTouchInput {
            swipe(
                start = Offset(width * 0.9f, height * 0.7f),
                end = Offset(width * 0.9f, height * 0.3f),
                durationMillis = 250,
            )
        }
        compose.waitForIdle()
        assertTrue(listState.firstVisibleItemIndex > 0)
        assertTrue(selected.intValue >= listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        compose.onNodeWithTag("listSelectionHighlight").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `Off touch scrolling locks the static pill at either edge until scrolling ends`() {
        assertTouchEdgesStayPinned(AnimationMode.OFF, "listStaticSelectionHighlight")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `Touch Input scrolling keeps the animated pill locked at either edge as the fling slows`() {
        assertTouchEdgesStayPinned(AnimationMode.TOUCH_INPUT, "listSelectionHighlight")
    }

    private fun assertTouchEdgesStayPinned(mode: AnimationMode, tag: String) {
        animationMode.value = mode
        selected.intValue = 42
        renderAnimationModes(initialIndex = 40, disablePrefetch = true)
        compose.mainClock.autoAdvance = false
        val viewport = compose.onNodeWithTag("modeList").fetchSemanticsNode().boundsInRoot
        val highlightColor = PiannoliColors().highlight.toArgb()
        for (down in listOf(true, false)) {
            compose.runOnIdle { selected.intValue = listState.firstVisibleItemIndex + 2 }
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            compose.onNodeWithTag("surface").performTouchInput {
                swipe(
                    start = Offset(width * 0.9f, height * if (down) 0.65f else 0.2f),
                    end = Offset(width * 0.9f, height * if (down) 0.2f else 0.65f),
                    durationMillis = 160,
                )
            }
            var pinned = false
            val pinnedSelections = mutableSetOf<Int>()
            repeat(90) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val pill = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("pill must remain inside the viewport: $pill",
                    pill.top >= viewport.top && pill.bottom <= viewport.bottom)
                withFrame { bitmap ->
                    assertEquals("the pill background must be painted on every scrolling frame", highlightColor,
                        bitmap.getPixel(pill.center.x.toInt(), pill.center.y.toInt()))
                }
                if (listState.isScrollInProgress) {
                    val edge = if (down) pill.top else pill.bottom
                    val target = if (down) viewport.top else viewport.bottom
                    if (abs(edge - target) <= 1f) pinned = true
                    if (pinned) {
                        assertEquals("scrolling must not release or flicker the $mode pill", target, edge, 1f)
                        pinnedSelections += selected.intValue
                    }
                }
            }
            assertTrue("$mode must reach the ${if (down) "top" else "bottom"} edge", pinned)
            assertTrue("selection must keep changing under the fixed pill", pinnedSelections.size > 1)
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            val label = compose.onNodeWithText("Game ${selected.intValue}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val pill = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertEquals("the resting pill must match the selected row", label.center.y, pill.center.y, 1f)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun `Off controller repeats retain a static pill at the reveal edge without animation observers`() {
        animationMode.value = AnimationMode.OFF
        val activity = MutableStateFlow(true)
        renderAnimationModes(activity)
        compose.mainClock.autoAdvance = false
        val viewport = compose.onNodeWithTag("modeList").fetchSemanticsNode().boundsInRoot
        for (down in listOf(true, false)) {
            for (index in if (down) 1..24 else 23 downTo 0) {
                val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                compose.runOnIdle {
                    controllerInputEvents.tryEmit(Unit)
                    selected.intValue = index
                }
                repeat(2) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    val pill = compose.onNodeWithTag("listStaticSelectionHighlight").fetchSemanticsNode().boundsInRoot
                    assertTrue("the static pill must never disappear outside the viewport",
                        pill.top >= viewport.top && pill.bottom <= viewport.bottom)
                    if (before != (listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)) {
                        assertEquals("controller reveal must pin the static pill at the edge",
                            if (down) viewport.bottom else viewport.top,
                            if (down) pill.bottom else pill.top, 0.5f)
                    }
                }
            }
        }
        assertEquals("static highlights must not add animation activity observers", 2, activity.subscriptionCount.value)
        compose.mainClock.autoAdvance = true
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `Off uses immediate per-row highlights while touch and controller navigation still work`() {
        animationMode.value = AnimationMode.OFF
        renderAnimationModes()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Game 1").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        assertEquals(listOf("open:1"), actions)
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        compose.waitForIdle()
        compose.onNodeWithTag("listSelectionHighlight").assertDoesNotExist()
        val selectedPixels = textPixels("Game 1", animated = false)
        assertTrue("selected row must immediately use highlight text: $selectedPixels selections=$renderedSelections",
            selectedPixels.blue > 0)
        assertTrue(textPixels("Game 0", animated = false).red > 0)
        compose.runOnIdle {
            controllerInputEvents.tryEmit(Unit)
            selected.intValue = 2
        }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        compose.waitForIdle()
        assertTrue(textPixels("Game 2", animated = false).blue > 0)
        assertTrue(textPixels("Game 1", animated = false).red > 0)
        compose.mainClock.autoAdvance = true
    }

    @Test fun `Touch Input switches the animated pill on for touch and off for controller navigation`() {
        animationMode.value = AnimationMode.TOUCH_INPUT
        renderAnimationModes()
        compose.onNodeWithTag("listSelectionHighlight").assertDoesNotExist()
        compose.onNodeWithText("Game 1").performTouchInput { click() }
        compose.onNodeWithTag("listSelectionHighlight").assertExists()
        compose.runOnIdle {
            controllerInputEvents.tryEmit(Unit)
            selected.intValue = 25
        }
        compose.waitForIdle()
        compose.onNodeWithTag("listSelectionHighlight").assertDoesNotExist()
        compose.onNodeWithText("Game 25").assertExists()
        compose.onNodeWithText("Game 25").performTouchInput { click() }
        compose.onNodeWithTag("listSelectionHighlight").assertExists()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `Touch Input handoff preserves the pill position and foreground on the first touch frame`() {
        animationMode.value = AnimationMode.TOUCH_INPUT
        renderAnimationModes()
        compose.mainClock.autoAdvance = false
        repeat(3) {
            val resting = compose.onNodeWithTag("listStaticSelectionHighlight").fetchSemanticsNode().boundsInRoot
            assertTrue(textPixels("Game 0", animated = false).blue > 0)
            compose.onNodeWithTag("surface").performTouchInput {
                down(Offset(width * 0.9f, height * 0.9f))
            }
            repeat(3) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val pill = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
                assertEquals("handoff must not move or recreate an empty pill", resting, pill)
                assertTrue("the selected text must never lose its highlight during handoff", textPixels("Game 0").blue > 0)
            }
            compose.onNodeWithTag("surface").performTouchInput { up() }
            compose.runOnIdle { controllerInputEvents.tryEmit(Unit) }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertEquals(resting,
                compose.onNodeWithTag("listStaticSelectionHighlight").fetchSemanticsNode().boundsInRoot)
        }
        assertTrue(actions.isEmpty())
        compose.mainClock.autoAdvance = true
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `turning animations off cancels an in-flight slide and immediately highlights the selected row`() {
        renderAnimationModes()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Game 1").performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("listSelectionHighlight").assertExists()
        compose.runOnIdle { animationMode.value = AnimationMode.OFF }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.onNodeWithTag("listSelectionHighlight").assertDoesNotExist()
        assertTrue(textPixels("Game 1", animated = false).blue > 0)
        assertTrue(textPixels("Game 0", animated = false).red > 0)
        compose.mainClock.autoAdvance = true
    }

    @Test fun `touch fling caps fast swipes in either direction but preserves gentle momentum`() = runTest {
        val velocities = mutableListOf<Float>()
        val delegate = object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                velocities += initialVelocity
                return initialVelocity / 2
            }
        }
        val behavior = TouchFlingBehavior(delegate, 6800f)
        val scope = object : ScrollScope {
            override fun scrollBy(pixels: Float) = pixels
        }
        with(behavior) {
            assertEquals(3400f, scope.performFling(8000f), 0f)
            assertEquals(-3400f, scope.performFling(-8000f), 0f)
            assertEquals(250f, scope.performFling(500f), 0f)
        }
        assertEquals(listOf(6800f, -6800f, 500f), velocities)
    }

    @Test fun `hint pills reject controller focus but remain tappable`() {
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch) {
                Column {
                    BottomBar(
                        leftItems = listOf("B" to "Back"),
                        rightItems = listOf("A" to "Open"),
                    )
                    LegendPill("start", "Explicit", onClick = { actions += "explicit" })
                }
            }
        }
        compose.onNodeWithText("START").assertExists()
        for (label in listOf("BACK", "OPEN", "EXPLICIT")) {
            val hint = compose.onNodeWithText(label)
            hint.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            hint.assertIsNotFocused()
            hint.performTouchInput { click() }
        }
        assertEquals(listOf("B", "A", "explicit"), actions)
    }

    private fun render(
        modal: Boolean = false,
        disabled: Set<Int> = emptySet(),
        customSelection: ((Int) -> Unit)? = null,
        artwork: Boolean = false,
        indication: Indication? = null,
        activity: StateFlow<Boolean>? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalTouchInput provides touch,
                LocalIndication provides (indication ?: LocalIndication.current),
                LocalUiActive provides (activity ?: LocalUiActive.current),
            ) {
                ScreenBackground(modifier = Modifier.testTag("surface")) {
                    listState = rememberLazyListState()
                    List(
                        items = (0 until 25).toList(),
                        selectedIndex = selected.intValue,
                        listState = listState,
                        scrollTarget = -1,
                        modifier = Modifier.width(220.dp).height(200.dp),
                        isItemSelectable = { it !in disabled },
                        onTouchSelect = customSelection,
                    ) { index, _, isSelected ->
                        if (index == 2) {
                            PillRowKeyValue(
                                label = "Setting",
                                value = "Value",
                                isSelected = isSelected,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                verticalPadding = 8.dp,
                                touchCycle = true,
                            )
                        } else Box(Modifier.height(40.dp)) { Text("Row $index") }
                    }
                    Box(Modifier.fillMaxSize()) {
                        if (artwork) {
                            Box(Modifier.align(Alignment.CenterEnd).size(100.dp)
                                .activateSelectedItemOnTap().testTag("artwork")) {
                                Text("Artwork")
                            }
                        }
                        BottomBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            leftItems = listOf("B" to "Back"),
                            rightItems = listOf(DPAD_HORIZONTAL to "Change", "A" to "Open"),
                        )
                    }
                }
                if (modal) ConfirmOverlay("Modal")
            }
        }
        compose.waitForIdle()
    }

    @Test fun `list rows and artwork reject controller focus while keeping touch actions`() {
        render(artwork = true)
        val row = compose.onNodeWithText("Row 1")
        row.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        row.assertIsNotFocused()
        row.performTouchInput { click() }
        assertEquals(1, selected.intValue)
        val artwork = compose.onNodeWithTag("artwork")
        artwork.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        artwork.assertIsNotFocused()
        artwork.performTouchInput { click() }
        assertEquals(listOf("open:1", "open:1"), actions)
    }

    @Test fun `backgrounding cancels touch fling and detaches input collectors until foregrounded`() {
        val active = MutableStateFlow(true)
        render(activity = active)
        assertEquals(1, controllerInputEvents.subscriptionCount.value)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("surface").performTouchInput {
            swipe(
                start = Offset(width * 0.9f, height * 0.6f),
                end = Offset(width * 0.9f, height * 0.3f),
                durationMillis = 180,
            )
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            assertTrue(listState.isScrollInProgress)
            active.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(0, controllerInputEvents.subscriptionCount.value)
        assertTrue("a hidden launcher must not keep flinging", !listState.isScrollInProgress)
        val stopped = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(stopped, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        compose.runOnIdle { active.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(1, controllerInputEvents.subscriptionCount.value)
        assertEquals(stopped, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        compose.mainClock.autoAdvance = true
    }

    @Test fun `tap selects the touched row before opening it`() {
        render()
        compose.onNodeWithText("Row 1").performTouchInput { click() }
        assertEquals(listOf("open:1"), actions)
    }

    @Test fun `long press opens context instead of activating`() {
        render()
        compose.onNodeWithText("Row 1").performTouchInput { longClick() }
        assertEquals(listOf("context:1"), actions)
    }

    @Test fun `setting tap cycles and footer hints dispatch their glyphs`() {
        render()
        compose.onNodeWithText("Setting").performTouchInput { click() }
        compose.onNodeWithText("CHANGE").performTouchInput { click() }
        compose.onNodeWithText("BACK").performTouchInput { click() }
        assertEquals(listOf("cycle:2", DPAD_HORIZONTAL, "B"), actions)
    }

    @Test fun `swiping outside the list scrolls with selection following and controller handoff`() {
        render()
        compose.onNodeWithTag("surface").performTouchInput {
            swipe(
                start = Offset(width * 0.9f, height * 0.8f),
                end = Offset(width * 0.9f, height * 0.2f),
                durationMillis = 250,
            )
        }
        compose.waitForIdle()
        assertTrue(listState.firstVisibleItemIndex > 0)
        assertTrue(selected.intValue > 0)
        assertTrue(actions.isEmpty())
        compose.runOnIdle {
            controllerInputEvents.tryEmit(Unit)
            selected.intValue = 0
        }
        compose.waitForIdle()
        assertEquals(0, listState.firstVisibleItemIndex)
    }

    @Test fun `modal backdrop does not activate an underlying row`() {
        render(modal = true)
        compose.onNodeWithTag("surface").performTouchInput { click(Offset(20f, 55f)) }
        assertTrue(actions.isEmpty())
        assertEquals(0, selected.intValue)
    }

    @Test fun `disabled rows are not activated and custom index translation is respected`() {
        render(disabled = setOf(0), customSelection = { index -> selected.intValue = index + 10 })
        compose.onNodeWithText("Row 0").performTouchInput { click() }
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Row 1").performTouchInput { click() }
        assertEquals(listOf("open:11"), actions)
    }

    @Test fun `a fast swipe continues moving after the finger is lifted`() {
        render()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("surface").performTouchInput {
            swipe(
                start = Offset(width * 0.9f, height * 0.6f),
                end = Offset(width * 0.9f, height * 0.3f),
                durationMillis = 180,
            )
        }
        compose.mainClock.advanceTimeBy(32)
        var releasedIndex = 0
        var releasedOffset = 0
        compose.runOnIdle {
            releasedIndex = listState.firstVisibleItemIndex
            releasedOffset = listState.firstVisibleItemScrollOffset
        }
        compose.mainClock.advanceTimeBy(180)
        compose.runOnIdle {
            assertTrue("fling should continue after release",
                listState.firstVisibleItemIndex > releasedIndex ||
                    (listState.firstVisibleItemIndex == releasedIndex && listState.firstVisibleItemScrollOffset > releasedOffset))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun `controller input cancels an ongoing fling and restores its selection`() {
        render()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("surface").performTouchInput {
            swipe(
                start = Offset(width * 0.9f, height * 0.6f),
                end = Offset(width * 0.9f, height * 0.3f),
                durationMillis = 180,
            )
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            controllerInputEvents.tryEmit(Unit)
            selected.intValue = 0
        }
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, selected.intValue)
        assertEquals(0, listState.firstVisibleItemIndex)
    }

    @Test fun `detail content scrolls when dragging outside its text column`() {
        lateinit var state: ScrollState
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch) {
                ScreenBackground(modifier = Modifier.testTag("detail")) {
                    state = rememberScrollState()
                    Column(Modifier.width(220.dp).height(200.dp).screenVerticalScroll(state)) {
                        repeat(50) { Text("Detail $it") }
                    }
                }
            }
        }
        compose.onNodeWithTag("detail").performTouchInput {
            swipe(
                start = Offset(width * 0.9f, height * 0.7f),
                end = Offset(width * 0.9f, height * 0.3f),
                durationMillis = 250,
            )
        }
        compose.waitForIdle()
        assertTrue(state.value > 0)
    }

    @Test fun `touch fling settling keeps the pill on the selected game instead of a controller edge`() {
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch) {
                ScreenBackground(modifier = Modifier.testTag("touchListSurface")) {
                    listState = rememberLazyListState()
                    List(
                        items = (0 until 100).map { "Game $it" },
                        selectedIndex = selected.intValue,
                        listState = listState,
                        modifier = Modifier.width(220.dp).height(200.dp),
                    ) { _, item, isSelected ->
                        PillRowText(item, isSelected, 16.sp, 24.sp, 8.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            controllerInputEvents.tryEmit(Unit)
            selected.intValue = 25
        }
        compose.waitForIdle()
        fun assertPillMatchesSelection() {
            val label = compose.onNodeWithText("Game ${selected.intValue}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val pill = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
            assertEquals("pill must match the selected game after touch settles: label=$label pill=$pill",
                label.center.y, pill.center.y, 1f)
            val layout = listState.layoutInfo
            assertEquals("touch settling must align the first row", 0, listState.firstVisibleItemScrollOffset)
            assertTrue("touch settling must not clip the bottom row", layout.visibleItemsInfo.all {
                it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset
            })
        }
        compose.mainClock.autoAdvance = false
        for (upward in listOf(true, false, true)) {
            compose.onNodeWithTag("touchListSurface").performTouchInput {
                swipe(
                    start = Offset(width * 0.9f, if (upward) height * 0.6f else height * 0.3f),
                    end = Offset(width * 0.9f, if (upward) height * 0.3f else height * 0.6f),
                    durationMillis = 250,
                )
            }
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            assertPillMatchesSelection()
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun `fast touch flings are carried to the leading edge and release as they slow in both directions`() {
        selected.intValue = 42
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch) {
                ScreenBackground(modifier = Modifier.testTag("weightedSurface")) {
                    listState = rememberLazyListState(initialFirstVisibleItemIndex = 40)
                    List(
                        items = (0 until 400).map { "Game $it" },
                        selectedIndex = selected.intValue,
                        listState = listState,
                        scrollTarget = -1,
                        modifier = Modifier.width(220.dp).height(200.dp).testTag("weightedList"),
                    ) { _, item, isSelected ->
                        PillRowText(item, isSelected, 16.sp, 24.sp, 8.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val viewport = compose.onNodeWithTag("weightedList").fetchSemanticsNode().boundsInRoot
        fun centerSelection() {
            compose.runOnIdle { selected.intValue = listState.firstVisibleItemIndex + 2 }
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            val resting = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
            assertEquals("fling begins with a centered selection", viewport.center.y, resting.center.y, 1f)
        }
        for (down in listOf(true, false)) {
            centerSelection()
            val beforeDrag = listState.firstVisibleItemIndex * 40 + listState.firstVisibleItemScrollOffset
            var fingerY = 0f
            compose.onNodeWithTag("weightedSurface").performTouchInput {
                fingerY = height * 0.5f
                this.down(Offset(width * 0.9f, fingerY))
                fingerY += if (down) -16f else 16f
                moveTo(Offset(width * 0.9f, fingerY))
            }
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            repeat(4) {
                compose.onNodeWithTag("weightedSurface").performTouchInput {
                    fingerY += if (down) -10f else 10f
                    moveTo(Offset(width * 0.9f, fingerY))
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val swept = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
            assertTrue("pill must travel with the content before meeting an edge",
                if (down) swept.center.y < viewport.center.y else swept.center.y > viewport.center.y)
            val dragDistance = listState.firstVisibleItemIndex * 40 + listState.firstVisibleItemScrollOffset - beforeDrag
            assertTrue("speed must not teleport the pill to an edge: down=$down distance=$dragDistance pill=$swept viewport=$viewport",
                swept.top > viewport.top + 1f && swept.bottom < viewport.bottom - 1f)
            compose.onNodeWithTag("weightedSurface").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            centerSelection()
            compose.onNodeWithTag("weightedSurface").performTouchInput {
                swipe(
                    start = Offset(width * 0.9f, height * if (down) 0.65f else 0.2f),
                    end = Offset(width * 0.9f, height * if (down) 0.2f else 0.65f),
                    durationMillis = 160,
                )
            }
            var pinned = false
            var released = false
            val samples = mutableListOf<String>()
            repeat(90) { frame ->
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val pill = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
                assertTrue("weighted pill must stay inside its viewport: $pill",
                    pill.top >= viewport.top && pill.bottom <= viewport.bottom)
                val edgeDistance = if (down) abs(pill.top - viewport.top) else abs(pill.bottom - viewport.bottom)
                if (frame % 10 == 0) samples += "$frame: index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset} pill=$pill moving=${listState.isScrollInProgress}"
                if (listState.isScrollInProgress && edgeDistance <= 1f) pinned = true
                if (pinned && listState.isScrollInProgress && edgeDistance > 8f) released = true
            }
            assertTrue("fast fling must reach ${if (down) "top" else "bottom"} with moving content", pinned)
            assertTrue("pill must release from the edge as the fling slows (down=$down): $samples", released)
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            val label = compose.onNodeWithText("Game ${selected.intValue}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val pill = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
            assertEquals("weighted motion must settle on the selected row", label.center.y, pill.center.y, 1f)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `fast controller repeats keep the pill visible and pinned at the scrolling edge`() {
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch) {
                ScreenBackground {
                    listState = rememberLazyListState()
                    List(
                        items = (0 until 40).map { "Game $it" },
                        selectedIndex = selected.intValue,
                        listState = listState,
                        modifier = Modifier.width(220.dp).height(160.dp).testTag("controllerList"),
                    ) { _, item, isSelected ->
                        PillRowText(item, isSelected, 16.sp, 24.sp, 8.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val viewport = compose.onNodeWithTag("controllerList").fetchSemanticsNode().boundsInRoot
        fun visiblePosition() = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.index to it.offset }
        fun step(index: Int, down: Boolean, frames: Int = 2) {
            val before = visiblePosition()
            compose.runOnIdle {
                controllerInputEvents.tryEmit(Unit)
                selected.intValue = index
            }
            if (frames == 5) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            repeat(frames) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
                compose.waitForIdle()
                drawFrame()
                val pill = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
                assertTrue("pill must remain visible at index $index: pill=$pill viewport=$viewport",
                    pill.top >= viewport.top && pill.bottom <= viewport.bottom)
                val after = visiblePosition()
                if (after != before) {
                    assertEquals("pill must stay at the scrolling edge at index $index",
                        if (down) viewport.bottom else viewport.top,
                        if (down) pill.bottom else pill.top, 0.5f)
                }
            }
            if (frames == 5) {
                val label = compose.onNodeWithText("Game $index", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val pill = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
                assertEquals("each row must be reached before the next 80ms controller repeat: index=$index",
                    label.center.y, pill.center.y, 0.5f)
            }
        }
        for (index in 1..8) step(index, down = true, frames = 5)
        for (index in 7 downTo 0) step(index, down = false, frames = 5)
        for (index in 1..24) step(index, down = true)
        for (index in 23 downTo 0) step(index, down = false)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(actions.isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `pill spatially reveals outgoing and incoming text and accent colors`() {
        val colors = PiannoliColors(text = Color.Red, highlightText = Color.Blue, accent = Color.Green)
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch, LocalPiannoliColors provides colors) {
                ScreenBackground {
                    List(
                        items = listOf("One", "A much longer row"),
                        selectedIndex = selected.intValue,
                        modifier = Modifier.width(220.dp).height(160.dp),
                    ) { _, item, isSelected ->
                        if (item == "One") {
                            PillRowText(item, isSelected, 16.sp, 24.sp, 8.dp, tagSuffix = "Tag")
                        } else {
                            PillRowKeyValue(item, "Value", isSelected, 16.sp, 24.sp, 8.dp, swatchColor = Color.Yellow)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(textPixels("One").blue > 0)
        assertEquals(0, textPixels("One").red)
        assertTrue(textPixels("Tag").blue > 0)
        assertTrue(textPixels("Value").green > 0)
        val start = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("A much longer row").performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        var outgoingPartial = false
        var incomingPartial = false
        var suffixPartial = false
        var valuePartial = false
        val positions = mutableListOf<androidx.compose.ui.geometry.Rect>()
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val outgoing = textPixels("One")
            val incoming = textPixels("A much longer row")
            val suffix = textPixels("Tag")
            val value = textPixels("Value")
            outgoingPartial = outgoingPartial || (outgoing.blue > 0 && outgoing.red > 0)
            incomingPartial = incomingPartial || (incoming.blue > 0 && incoming.red > 0)
            suffixPartial = suffixPartial || (suffix.blue > 0 && suffix.green > 0)
            valuePartial = valuePartial || (value.blue > 0 && value.green > 0)
            val pill = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
            positions += pill
        }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        val end = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
        assertTrue("outgoing glyphs must contain both colors", outgoingPartial)
        assertTrue("incoming glyphs must contain both colors", incomingPartial)
        assertTrue("outgoing accent glyphs must contain both colors", suffixPartial)
        assertTrue("incoming accent glyphs must contain both colors", valuePartial)
        assertTrue("pill must slide and resize: start=$start end=$end", positions.any {
            it.top > start.top && it.top < end.top && it.width > start.width && it.width < end.width
        })
        assertEquals(0, textPixels("A much longer row").red)
        assertTrue(textPixels("A much longer row").blue > 0)
        assertEquals(0, textPixels("Value").green)
        assertTrue(textPixels("Value").blue > 0)
        assertTrue(textPixels("One").red > 0)
        assertEquals(0, textPixels("One").blue)
        assertTrue("swatch must retain its own color", framePixels(end).yellow > 0)
        compose.mainClock.autoAdvance = true
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `distant row taps snap in either direction while adjacent taps still slide`() {
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch) {
                ScreenBackground {
                    List(
                        items = (0 until 5).map { "Game $it" },
                        selectedIndex = selected.intValue,
                        modifier = Modifier.width(220.dp).height(240.dp),
                    ) { _, item, isSelected ->
                        PillRowText(item, isSelected, 16.sp, 24.sp, 8.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        fun pill() = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
        fun label(index: Int) = compose.onNodeWithText("Game $index", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        fun renderTap() {
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                drawFrame()
            }
        }
        for (index in listOf(3, 0)) {
            compose.onNodeWithText("Game $index").performTouchInput { click() }
            assertEquals(index, selected.intValue)
            renderTap()
            assertEquals("distant tap must snap immediately to row $index", label(index).center.y, pill().center.y, 0.5f)
        }
        assertEquals(listOf("open:3", "open:0"), actions)
        val start = pill()
        compose.onNodeWithText("Game 1").performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        assertTrue("adjacent taps must retain the sliding animation",
            pill().center.y > start.center.y && pill().center.y < label(1).center.y)
        compose.onNodeWithText("Game 4").performTouchInput { click() }
        renderTap()
        assertEquals("distant taps must also cancel an in-progress slide", label(4).center.y, pill().center.y, 0.5f)
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals(label(4).center.y, pill().center.y, 0.5f)
        compose.mainClock.autoAdvance = true
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `redirected pill reveals crossed unselected rows and settles at the new destination`() {
        val colors = PiannoliColors(text = Color.Red, highlightText = Color.Blue)
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch, LocalPiannoliColors provides colors) {
                ScreenBackground {
                    List(
                        items = listOf("One", "Two", "Three"),
                        selectedIndex = selected.intValue,
                        modifier = Modifier.width(220.dp).height(160.dp),
                    ) { _, item, isSelected ->
                        PillRowText(item, isSelected, 16.sp, 24.sp, 8.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Two").performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.runOnIdle { selected.intValue = 2 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        var crossedPartial = false
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val crossed = textPixels("Two")
            crossedPartial = crossedPartial || (crossed.red > 0 && crossed.blue > 0)
        }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertTrue("unselected crossed row must be spatially highlighted", crossedPartial)
        assertTrue(textPixels("Two").red > 0)
        assertEquals(0, textPixels("Two").blue)
        assertTrue(textPixels("Three").blue > 0)
        assertEquals(0, textPixels("Three").red)
        compose.mainClock.autoAdvance = true
    }

    private data class Pixels(var red: Int = 0, var green: Int = 0, var blue: Int = 0, var yellow: Int = 0)

    private fun textPixels(text: String, animated: Boolean = true): Pixels = framePixels(
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot,
        if (animated) compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot else null,
    )

    private fun framePixels(
        bounds: androidx.compose.ui.geometry.Rect,
        pill: androidx.compose.ui.geometry.Rect? = null,
    ): Pixels = withFrame { bitmap ->
        val pixels = Pixels()
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height)) {
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width)) {
                val color = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(color)
                val g = android.graphics.Color.green(color)
                val b = android.graphics.Color.blue(color)
                if (r > 200 && g < 50 && b < 50) pixels.red++
                if (g > 200 && r < 50 && b < 50) pixels.green++
                if (b > 200 && r < 50 && g < 50) pixels.blue++
                if (r > 200 && g > 200 && b < 50) pixels.yellow++
                if (pill != null && ((b > 200 && r < 50 && g < 50) ||
                        (r > 200 && g < 50 && b < 50) || (g > 200 && r < 50 && b < 50))) {
                    val radius = minOf(pill.width, pill.height) / 2
                    val dx = x + 0.5f - (x + 0.5f).coerceIn(pill.left + radius, pill.right - radius)
                    val dy = y + 0.5f - (y + 0.5f).coerceIn(pill.top + radius, pill.bottom - radius)
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (b > 200) {
                        assertTrue("highlighted pixel ($x,$y) must be inside rounded pill $pill",
                            distance <= radius + 1)
                    } else {
                        assertTrue("normal pixel ($x,$y) must be outside rounded pill $pill",
                            distance >= radius - 1)
                    }
                }
            }
        }
        pixels
    }

    @Test fun `selection pill excludes a section header`() {
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch) {
                ScreenBackground {
                    SectionedList(
                        sections = listOf(ListSection("Section", listOf("Game"))),
                        selectedIndex = 0,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        verticalPadding = 8.dp,
                        modifier = Modifier.width(220.dp).height(160.dp),
                    ) { _, item, isSelected ->
                        PillRowText(item, isSelected, 16.sp, 24.sp, 8.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        val header = compose.onNodeWithText("Section", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
        assertTrue("highlight belongs to the game, not its header: pill=$pill header=$header", pill.top >= header.bottom)
    }

    @Test fun `artwork tap activates the current row and respects cycling`() {
        render(artwork = true)
        compose.runOnIdle { selected.intValue = 1 }
        compose.onNodeWithTag("artwork").performTouchInput { click() }
        compose.runOnIdle { selected.intValue = 2 }
        compose.onNodeWithTag("artwork").performTouchInput { click() }
        assertEquals(listOf("open:1", "cycle:2"), actions)
    }

    @Test fun `dragging on artwork scrolls without activating it`() {
        render(artwork = true)
        compose.onNodeWithTag("artwork").performTouchInput {
            swipe(start = center, end = center - Offset(0f, 150f), durationMillis = 250)
        }
        compose.waitForIdle()
        assertTrue(listState.firstVisibleItemIndex > 0)
        assertTrue(actions.isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `holding artwork produces no press indication`() {
        val indication = RecordingPressIndication()
        render(artwork = true, indication = indication)
        compose.onNodeWithTag("artwork").performTouchInput { down(center) }
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        drawFrame()
        val feedback = indication.drawnSizes.toList()
        compose.onNodeWithTag("artwork").performTouchInput { up() }
        assertTrue("artwork must not draw press feedback", feedback.isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `item press feedback is sized to its pill instead of the row hit area`() {
        val indication = RecordingPressIndication()
        compose.setContent {
            CompositionLocalProvider(LocalTouchInput provides touch, LocalIndication provides indication) {
                ScreenBackground {
                    List(
                        items = listOf("Short"),
                        selectedIndex = 0,
                        modifier = Modifier.width(220.dp).height(160.dp),
                    ) { _, item, isSelected ->
                        PillRowText(item, isSelected, 16.sp, 24.sp, 8.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        val pill = compose.onNodeWithTag("listSelectionHighlight").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithText("Short").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Short").performTouchInput { down(center) }
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        drawFrame()
        val feedback = indication.drawnSizes.toList()
        compose.onNodeWithText("Short").performTouchInput { up() }
        assertTrue("the pill should show press feedback", feedback.isNotEmpty())
        assertTrue("feedback should not cover the full hit area", feedback.all { it.width < row.width })
        feedback.forEach {
            assertEquals(pill.width, it.width, 0.5f)
            assertEquals(pill.height, it.height, 0.5f)
        }
    }

    private fun drawFrame() = withFrame { }

    private fun <T> withFrame(read: (android.graphics.Bitmap) -> T): T = compose.runOnIdle {
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(bitmap))
            read(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private class RecordingPressIndication : IndicationNodeFactory {
        val drawnSizes = mutableListOf<Size>()

        override fun create(interactionSource: InteractionSource): Modifier.Node =
            object : Modifier.Node(), DrawModifierNode {
                private var pressed = false

                override fun onAttach() {
                    coroutineScope.launch {
                        interactionSource.interactions.collect {
                            when (it) {
                                is PressInteraction.Press -> pressed = true
                                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                            }
                            invalidateDraw()
                        }
                    }
                }

                override fun ContentDrawScope.draw() {
                    drawContent()
                    if (pressed) drawnSizes.add(size)
                }
            }

        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
}
