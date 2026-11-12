package dev.piannoli.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

interface TouchInput {
    val controllerInput: Flow<Unit> get() = emptyFlow()
    fun beginNavigation() {}
    fun select(index: Int, currentIndex: Int)
    fun activate(cycle: Boolean)
    fun contextMenu()
    fun hint(glyph: String)
}

val LocalTouchInput = staticCompositionLocalOf<TouchInput?> { null }
internal val LocalTouchSurface = staticCompositionLocalOf<TouchSurface?> { null }
internal val LocalTouchRow = staticCompositionLocalOf<TouchRow?> { null }

internal class TouchRow {
    val pressInteractions = MutableInteractionSource()
    var cycle = false
    var selectable = true
    var viewportOffset by mutableIntStateOf(0)
    // Observable so row text recomposes and redraws when the viewport pill is detached or restored.
    var highlightBoundsProvider by mutableStateOf<(() -> Rect?)?>(null)
    val highlightBounds: Rect? get() = highlightBoundsProvider?.invoke()
    var coordinates: LayoutCoordinates? = null
    var pillCoordinates: LayoutCoordinates? = null
    var pillBounds by mutableStateOf<Rect?>(null)
    private var foregroundMasks: MutableSet<ForegroundMask>? = null

    fun registerForegroundMask(mask: ForegroundMask) {
        val masks = foregroundMasks ?: mutableSetOf<ForegroundMask>().also { foregroundMasks = it }
        masks.add(mask)
    }

    fun unregisterForegroundMask(mask: ForegroundMask) {
        foregroundMasks?.let {
            it.remove(mask)
            if (it.isEmpty()) foregroundMasks = null
        }
    }

    /** Drops cached drawing objects only. Geometry is kept because layout callbacks do not fire
     *  again when nothing moved, so clearing it here would leave text unmasked on return. */
    fun clearAnimationResources() {
        foregroundMasks?.forEach { it.clear() }
    }

    fun detach() {
        clearAnimationResources()
        coordinates = null
        pillCoordinates = null
        pillBounds = null
    }

    fun updatePillBounds() {
        val container = coordinates ?: return
        val pill = pillCoordinates ?: return
        if (container.isAttached && pill.isAttached) {
            pillBounds = container.localBoundingBoxOf(pill, clipBounds = false)
        }
    }
}

internal class TouchSurface {
    var listState by mutableStateOf<LazyListState?>(null)
    var contentState by mutableStateOf<ScrollableState?>(null)
    var scrolling by mutableStateOf(false)
    var touchNavigation by mutableStateOf(false)
    var selectedRow by mutableStateOf<TouchRow?>(null)
    val interactions = MutableInteractionSource()
}

@Composable
internal fun Modifier.touchScrolling(surface: TouchSurface): Modifier {
    val touch = LocalTouchInput.current
    val uiActive = LocalUiActive.current
    val animationsEnabled = androidx.compose.runtime.rememberUpdatedState(LocalAnimationsEnabled.current)
    val context = LocalContext.current
    val defaultFling = ScrollableDefaults.flingBehavior()
    val maxVelocity = android.view.ViewConfiguration.get(context).scaledMaximumFlingVelocity * 0.85f
    val fling = remember(defaultFling, maxVelocity) { TouchFlingBehavior(defaultFling, maxVelocity) }
    LaunchedEffect(surface, touch, uiActive) {
        uiActive.collectLatest { active ->
            if (!active) {
                surface.scrolling = false
                (surface.contentState ?: surface.listState)?.stopScroll(MutatePriority.PreventUserInput)
                return@collectLatest
            }
            coroutineScope {
                var settle: Job? = null
                launch {
                    touch?.controllerInput?.collect {
                        settle?.cancel()
                        surface.touchNavigation = false
                        surface.scrolling = false
                        (surface.contentState ?: surface.listState)?.stopScroll(MutatePriority.PreventUserInput)
                    }
                }
                launch {
                    surface.interactions.interactions.collect {
                        when (it) {
                            is DragInteraction.Start -> {
                                touch?.beginNavigation()
                                settle?.cancel()
                                surface.touchNavigation = true
                                surface.scrolling = true
                            }
                            is DragInteraction.Stop, is DragInteraction.Cancel -> {
                                settle = launch {
                                    // Drag end is emitted before the fling starts.
                                    withFrameNanos { }
                                    withFrameNanos { }
                                    snapshotFlow {
                                        (surface.contentState ?: surface.listState)?.isScrollInProgress == true
                                    }.first { moving -> !moving }
                                    if (surface.touchNavigation && surface.contentState == null) {
                                        surface.listState?.let { list ->
                                            val layout = list.layoutInfo
                                            val nearest = layout.visibleItemsInfo.minByOrNull {
                                                abs(it.offset - layout.viewportStartOffset)
                                            }
                                            val distance = nearest?.let { it.offset - layout.viewportStartOffset } ?: 0
                                            if (distance != 0) {
                                                if (animationsEnabled.value) list.animateScrollBy(distance.toFloat(), tween(120))
                                                else list.scrollBy(distance.toFloat())
                                            }
                                        }
                                    }
                                    surface.scrolling = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val state = surface.contentState ?: surface.listState ?: return this
    return scrollable(
        state = state,
        orientation = Orientation.Vertical,
        reverseDirection = true,
        interactionSource = surface.interactions,
        flingBehavior = fling,
    )
}

class TouchFlingBehavior(
    private val delegate: FlingBehavior,
    private val maxVelocity: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
        with(delegate) { performFling(initialVelocity.coerceIn(-maxVelocity, maxVelocity)) }
}

fun Modifier.screenVerticalScroll(state: ScrollState): Modifier = composed {
    val surface = LocalTouchSurface.current
    if (surface != null) {
        DisposableEffect(surface, state) {
            surface.contentState = state
            onDispose {
                if (surface.contentState === state) surface.contentState = null
            }
        }
    }
    verticalScroll(state, enabled = surface == null)
}

@Composable
fun touchScrollInProgress(): Boolean = LocalTouchSurface.current?.scrolling == true

fun Modifier.activateSelectedItemOnTap(): Modifier = composed {
    val touch = LocalTouchInput.current
    val surface = LocalTouchSurface.current
    val interactions = remember { MutableInteractionSource() }
    if (touch == null) this else focusProperties { canFocus = false }.clickable(
        interactionSource = interactions,
        indication = null,
        enabled = surface?.selectedRow?.selectable != false,
    ) {
        touch.beginNavigation()
        surface?.touchNavigation = true
        surface?.scrolling = false
        touch.activate(surface?.selectedRow?.cycle == true)
    }
}
