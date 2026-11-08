package dev.piannoli.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.ui.theme.Radius
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun <T> List(
    items: List<T>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    itemHeight: Dp = Dp.Unspecified,
    scrollTarget: Int = 0,
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = scrollTarget.coerceAtLeast(0)),
    reorderMode: Boolean = false,
    onListStateChanged: ((LazyListState?) -> Unit)? = null,
    key: ((index: Int, item: T) -> Any)? = null,
    isItemSelectable: (T) -> Boolean = { true },
    onTouchSelect: ((Int) -> Unit)? = null,
    itemContent: @Composable (index: Int, item: T, isSelected: Boolean) -> Unit
) {
    val touch = LocalTouchInput.current
    val surface = LocalTouchSurface.current
    val uiActive = LocalUiActive.current
    val currentIndex = rememberUpdatedState(selectedIndex)
    val currentItems = rememberUpdatedState(items)
    val selectable = rememberUpdatedState(isItemSelectable)
    val selectItem = rememberUpdatedState(onTouchSelect)
    val rows = remember { mutableStateMapOf<Int, TouchRow>() }
    val highlight = remember { DisplayedHighlight() }
    SideEffect { surface?.selectedRow = rows[selectedIndex] }
    ListScrollEffect(
        listState, selectedIndex, items.size, scrollTarget, reorderMode,
        touchScrolling = surface?.touchNavigation == true,
    )

    if (touch != null && surface != null) {
        DisposableEffect(surface, listState) {
            surface.listState = listState
            onDispose {
                if (surface.listState === listState) {
                    surface.listState = null
                    surface.selectedRow = null
                }
            }
        }
        LaunchedEffect(surface, listState, touch, items.size, uiActive) {
            uiActive.collectLatest { active ->
                if (!active) {
                    highlight.touchCenter = null
                    return@collectLatest
                }
                snapshotFlow { surface.scrolling }.collectLatest { scrolling ->
                    if (!scrolling) return@collectLatest
                    val initialLayout = listState.layoutInfo
                    val initialCenter = highlight.bounds?.center?.y ?: initialLayout.visibleItemsInfo
                        .firstOrNull { it.index == currentIndex.value }
                        ?.let { it.offset + (rows[it.index]?.pillBounds?.center?.y ?: it.size / 2f) }
                        ?: ((initialLayout.viewportStartOffset + initialLayout.viewportEndOffset) / 2f)
                    val motion = TouchHighlightMotion(initialCenter)
                    try {
                        while (true) {
                            val frameTime = withFrameNanos { it }
                            val layout = listState.layoutInfo
                            if (layout.viewportEndOffset <= layout.viewportStartOffset) continue
                            val rowHeight = rows[currentIndex.value]?.pillBounds?.height
                                ?: layout.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: continue
                            val center = motion.step(layout, frameTime, rowHeight)
                            highlight.touchCenter = center
                            var index: Int? = null
                            var nearestDistance = Float.MAX_VALUE
                            for (info in layout.visibleItemsInfo) {
                                val item = currentItems.value.getOrNull(info.index) ?: continue
                                if (!selectable.value(item)) continue
                                val pillCenter = rows[info.index]?.pillBounds?.center?.y ?: info.size / 2f
                                val distance = abs(info.offset + pillCenter - center)
                                if (distance < nearestDistance) {
                                    nearestDistance = distance
                                    index = info.index
                                }
                            }
                            if (index != null && index != currentIndex.value) {
                                val select = selectItem.value
                                if (select != null) select(index) else touch.select(index, currentIndex.value)
                            }
                        }
                    } finally {
                        highlight.touchCenter = null
                    }
                }
            }
        }
    }

    if (onListStateChanged != null) {
        DisposableEffect(listState) {
            onListStateChanged(listState)
            onDispose { onListStateChanged(null) }
        }
    }

    val rhythm = LocalListRhythm.current
    val spacing = rhythm?.itemSpacing ?: 0.dp
    // The rhythm already divided the screen into rows; re-deriving the count from measured pixels
    // rounds each row independently and can land a row short, which is what costs a short handheld a
    // whole row. Only fall back to measuring when this list is not the one the rhythm was solved for.
    val solvedRows = rhythm?.takeIf { abs(it.rowHeight.value - itemHeight.value) < 0.5f }?.rows

    val listModifier = if (itemHeight != Dp.Unspecified) {
        modifier.layout { measurable, constraints ->
            val itemPx = itemHeight.roundToPx()
            val spacingPx = spacing.roundToPx()
            val measured = ((constraints.maxHeight + spacingPx) / (itemPx + spacingPx)).coerceAtLeast(1)
            // Never lay out more rows than fit: one row over and the last one is clipped, which
            // takes it out of the fully-visible set that page jumps and reveal scrolling read.
            val fullItems = solvedRows?.coerceAtMost(measured) ?: measured
            val heightPx = (fullItems * itemPx + (fullItems - 1) * spacingPx)
                .coerceAtMost(constraints.maxHeight)
            val placeable = measurable.measure(
                constraints.copy(maxHeight = heightPx, minHeight = 0)
            )
            layout(placeable.width, heightPx) {
                placeable.place(0, 0)
            }
        }
    } else {
        modifier
    }

    Box(modifier = listModifier.clipToBounds()) {
        if (touch != null) {
            val layout = listState.layoutInfo
            var selectedItem: LazyListItemInfo? = null
            var firstItem: LazyListItemInfo? = null
            var lastItem: LazyListItemInfo? = null
            var firstFull: LazyListItemInfo? = null
            var lastFull: LazyListItemInfo? = null
            for (info in layout.visibleItemsInfo) {
                if (rows[info.index]?.pillBounds == null) continue
                if (firstItem == null) firstItem = info
                lastItem = info
                if (info.index == selectedIndex) selectedItem = info
                if (info.offset >= layout.viewportStartOffset && info.offset + info.size <= layout.viewportEndOffset) {
                    if (firstFull == null) firstFull = info
                    lastFull = info
                }
            }
            val item = selectedItem ?: if (selectedIndex < (firstItem?.index ?: selectedIndex)) firstFull ?: firstItem
                else lastFull ?: lastItem
            val bounds = item?.let { rows[it.index]?.pillBounds }
            SelectionHighlight(
                bounds = if (item != null && bounds != null) bounds.translate(0f, item.offset.toFloat()) else null,
                selectedIndex = selectedIndex,
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
                controllerScrollPosition = if (surface?.touchNavigation == true) null
                    else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
                displayed = highlight,
                listState = listState,
                touchScrolling = surface?.touchNavigation == true,
                touchMotion = surface?.scrolling == true && surface.touchNavigation,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            userScrollEnabled = touch == null || surface == null,
            contentPadding = PaddingValues(bottom = if (touch == null) 2000.dp else 0.dp)
        ) {
            itemsIndexed(items, key = key) { index, item ->
                if (touch == null) itemContent(index, item, selectedIndex == index)
                else {
                    val row = remember { TouchRow() }
                    row.selectable = isItemSelectable(item)
                    row.highlightBoundsProvider = remember(row, highlight) {
                        { highlight.bounds?.translate(0f, -row.viewportOffset.toFloat()) }
                    }
                    DisposableEffect(index, row) {
                        rows[index] = row
                        onDispose { if (rows[index] === row) rows.remove(index) }
                    }
                    CompositionLocalProvider(LocalTouchRow provides row) {
                        Box(Modifier.fillMaxWidth().onGloballyPositioned {
                            row.coordinates = it
                            listState.layoutInfo.visibleItemsInfo.firstOrNull { info -> info.index == index }?.let { info ->
                                row.viewportOffset = info.offset
                            }
                            row.updatePillBounds()
                        }.focusProperties { canFocus = false }.combinedClickable(
                            interactionSource = row.pressInteractions,
                            indication = null,
                            enabled = isItemSelectable(item),
                            onClick = {
                                highlight.snapToIndex = if (abs(index - currentIndex.value) > 1) index else null
                                surface?.touchNavigation = true
                                surface?.scrolling = false
                                if (onTouchSelect != null) onTouchSelect(index) else touch.select(index, currentIndex.value)
                                touch.activate(row.cycle)
                            },
                            onLongClick = {
                                surface?.touchNavigation = true
                                surface?.scrolling = false
                                if (onTouchSelect != null) onTouchSelect(index) else touch.select(index, currentIndex.value)
                                touch.contextMenu()
                            },
                        )) {
                            itemContent(index, item, selectedIndex == index)
                        }
                    }
                }
            }
        }
    }
}

private class TouchHighlightMotion(private val anchor: Float) {
    private var previousFirstIndex = -1
    private var previousFirstOffset = 0
    private var previousLastIndex = -1
    private var previousLastOffset = 0
    private var previousTime = 0L
    private var velocity = 0f
    private var position = anchor
    private var positionVelocity = 0f
    private var carriedDirection = 0

    fun step(layout: LazyListLayoutInfo, time: Long, rowHeight: Float): Float {
        val height = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(1).toFloat()
        val halfRow = rowHeight.coerceIn(0f, height) / 2f
        val start = layout.viewportStartOffset + halfRow
        val end = layout.viewportEndOffset - halfRow
        val base = anchor.coerceIn(start, end)
        val elapsed = ((time - previousTime) / 1_000_000_000f).coerceAtLeast(0.001f)
        previousTime = time
        val first = layout.visibleItemsInfo.firstOrNull()
        val last = layout.visibleItemsInfo.lastOrNull()
        val initial = previousFirstIndex < 0
        val firstReference = layout.visibleItemsInfo.firstOrNull { it.index == previousFirstIndex }
        val lastReference = if (firstReference == null)
            layout.visibleItemsInfo.firstOrNull { it.index == previousLastIndex } else null
        val distance = when {
            first == null || initial -> 0f
            firstReference != null -> (previousFirstOffset - firstReference.offset).toFloat()
            lastReference != null -> (previousLastOffset - lastReference.offset).toFloat()
            first.index > previousFirstIndex -> height
            first.index < previousFirstIndex -> -height
            else -> 0f
        }
        // LazyColumn can reuse measured items; retain offsets, not mutable layout objects.
        if (first != null && last != null) {
            previousFirstIndex = first.index
            previousFirstOffset = first.offset
            previousLastIndex = last.index
            previousLastOffset = last.offset
        }
        if (initial) {
            position = base
            return position
        }
        velocity += (distance / elapsed - velocity) * (1f - exp(-20f * elapsed))
        val direction = if (velocity > 0f) 1 else -1
        val speed = abs(velocity)
        if (speed < height * 0.9f || direction != carriedDirection) carriedDirection = 0
        if (speed >= height * 2.4f) carriedDirection = direction
        // Content carries the pill until it physically meets an edge; speed never teleports it.
        position = (position - distance).coerceIn(start, end)
        if (carriedDirection != 0) {
            positionVelocity = 0f
        } else {
            // Critically damped motion retains momentum without overshoot or per-row restarts.
            val frequency = 8f
            val offset = position - base
            val impulse = (positionVelocity + frequency * offset) * elapsed
            val decay = exp(-frequency * elapsed)
            position = base + (offset + impulse) * decay
            positionVelocity = (positionVelocity - frequency * impulse) * decay
        }
        position = position.coerceIn(start, end)
        return position
    }
}

private val RectConverter = TwoWayConverter<Rect, AnimationVector4D>(
    convertToVector = { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
    convertFromVector = { Rect(it.v1, it.v2, it.v3, it.v4) },
)

private class DisplayedHighlight {
    var bounds by mutableStateOf<Rect?>(null)
    var touchCenter by mutableStateOf<Float?>(null)
    var snapToIndex by mutableStateOf<Int?>(null)
    var scrollPosition: Pair<Int, Int>? = null
    var pinnedIndex: Int? = null
    var direction = 0
}

@Composable
private fun SelectionHighlight(
    bounds: Rect?,
    selectedIndex: Int,
    viewportStart: Int,
    viewportEnd: Int,
    controllerScrollPosition: Pair<Int, Int>?,
    displayed: DisplayedHighlight,
    listState: LazyListState,
    touchScrolling: Boolean,
    touchMotion: Boolean,
) {
    val translation = remember { Animatable(Rect.Zero, RectConverter) }
    val uiActive = LocalUiActive.current
    var animatedIndex by remember { mutableIntStateOf(selectedIndex) }
    val snapSelection = displayed.snapToIndex == selectedIndex
    val controllerScrolled = controllerScrollPosition != null && displayed.scrollPosition != null &&
        controllerScrollPosition != displayed.scrollPosition
    val direction = when {
        selectedIndex > animatedIndex -> 1
        selectedIndex < animatedIndex -> -1
        else -> displayed.direction
    }
    val pinned = controllerScrollPosition != null && (controllerScrolled || displayed.pinnedIndex == selectedIndex)
    val targetBounds = bounds?.let {
        if (!pinned || direction == 0) it else {
            val top = if (direction > 0) viewportEnd - it.height else viewportStart.toFloat()
            Rect(it.left, top, it.right, top + it.height)
        }
    }
    LaunchedEffect(selectedIndex, bounds != null, controllerScrollPosition, touchMotion, uiActive) {
        uiActive.collectLatest { active ->
            if (!active) {
                translation.snapTo(Rect.Zero)
                animatedIndex = selectedIndex
                return@collectLatest
            }
            val target = targetBounds ?: return@collectLatest
            val previous = displayed.bounds
            displayed.direction = direction
            if (controllerScrolled) displayed.pinnedIndex = selectedIndex
            if (controllerScrollPosition == null) displayed.pinnedIndex = null
            translation.snapTo(
                if (previous == null || snapSelection) Rect.Zero else Rect(
                    previous.left - target.left, if (controllerScrolled || touchMotion) 0f else previous.top - target.top,
                    previous.right - target.right, if (controllerScrolled || touchMotion) 0f else previous.bottom - target.bottom,
                )
            )
            animatedIndex = selectedIndex
            if (snapSelection) displayed.snapToIndex = null
            if (translation.value != Rect.Zero) {
                translation.animateTo(Rect.Zero, tween(if (touchScrolling) 160 else 48))
            }
        }
    }
    val delta = translation.value
    val animated = if (targetBounds == null) displayed.bounds else if (snapSelection) targetBounds
        else if (animatedIndex != selectedIndex && !controllerScrolled) displayed.bounds ?: targetBounds
        else Rect(
            targetBounds.left + delta.left, targetBounds.top + if (controllerScrolled) 0f else delta.top,
            targetBounds.right + delta.right, targetBounds.bottom + if (controllerScrolled) 0f else delta.bottom,
        )
    // Reveal scrolling moves the content under the pill; it must never carry the pill out of view.
    val position = animated?.takeIf { viewportEnd > viewportStart }?.let {
        val height = it.height.roundToInt().coerceIn(0, viewportEnd - viewportStart)
        val top = it.top.roundToInt().coerceIn(viewportStart, viewportEnd - height).toFloat()
        Rect(it.left, top, it.right, top + height)
    }
    if (position != null) {
        val density = LocalDensity.current
        Box(
            Modifier
                .offset {
                    // LazyColumn can reveal a row during measure, after this frame's composition.
                    val scroll = if (touchScrolling) null
                        else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    val moved = scroll != null && displayed.scrollPosition != null && scroll != displayed.scrollPosition
                    if (moved) {
                        displayed.pinnedIndex = selectedIndex
                        displayed.direction = direction
                    }
                    displayed.scrollPosition = scroll
                    val layout = listState.layoutInfo
                    val height = position.height.roundToInt().coerceIn(
                        0, (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
                    )
                    val atEdge = scroll != null && displayed.pinnedIndex == selectedIndex
                    val touchTop = if (touchMotion) displayed.touchCenter?.let { (it - height / 2f).roundToInt() } else null
                    val top = when {
                        touchTop != null -> touchTop.coerceIn(layout.viewportStartOffset, layout.viewportEndOffset - height)
                        atEdge && direction > 0 -> layout.viewportEndOffset - height
                        atEdge && direction < 0 -> layout.viewportStartOffset
                        else -> position.top.roundToInt().coerceIn(
                            layout.viewportStartOffset, layout.viewportEndOffset - height,
                        )
                    }

                    val left = position.left.roundToInt()
                    displayed.bounds = Rect(
                        left.toFloat(), top.toFloat(),
                        (left + position.width.roundToInt()).toFloat(), (top + height).toFloat(),
                    )
                    IntOffset(left, top)
                }
                .size(
                    with(density) { position.width.coerceAtLeast(0f).toDp() },
                    with(density) { position.height.coerceAtLeast(0f).toDp() },
                )
                .background(LocalPiannoliColors.current.highlight, Radius.Pill)
                .testTag("listSelectionHighlight")
        )
    }
}
