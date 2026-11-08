package dev.piannoli.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import dev.piannoli.ui.theme.LocalPiannoliColors
import kotlin.math.abs

private class ForegroundMask {
    val path = Path()
    var bounds: Rect? = null
}

@Composable
fun pillTextColor(isSelected: Boolean, normal: Color): Color =
    if (isSelected && LocalTouchRow.current == null) LocalPiannoliColors.current.highlightText else normal

/** Apply only to foreground glyphs, never a row containing artwork, swatches or backgrounds. */
@Composable
fun Modifier.pillForeground(): Modifier {
    val row = LocalTouchRow.current ?: return this
    val color = LocalPiannoliColors.current.highlightText
    val paint = remember(color) { Paint().apply { colorFilter = ColorFilter.tint(color) } }
    var origin by remember(row) { mutableStateOf(Offset.Zero) }
    val mask = remember { ForegroundMask() }
    return onGloballyPositioned { coordinates ->
        val container = row.coordinates
        if (container?.isAttached == true && coordinates.isAttached) {
            origin = container.localPositionOf(coordinates, Offset.Zero)
        }
    }.drawWithContent {
        val bounds = row.highlightBounds
        if (bounds == null || row.pillBounds == null) {
            drawContent()
        } else {
            val local = bounds.translate(-origin)
            if (local.right <= 0f || local.bottom <= 0f || local.left >= size.width || local.top >= size.height) {
                drawContent()
                return@drawWithContent
            }
            val radius = minOf(local.width, local.height) / 2
            val dx = maxOf(
                abs(0f.coerceIn(local.left + radius, local.right - radius)),
                abs(size.width - size.width.coerceIn(local.left + radius, local.right - radius)),
            )
            val dy = maxOf(
                abs(0f.coerceIn(local.top + radius, local.bottom - radius)),
                abs(size.height - size.height.coerceIn(local.top + radius, local.bottom - radius)),
            )
            fun drawHighlighted() {
                // Reuse the same laid-out glyphs; the layer replaces their RGB, retaining coverage.
                val canvas = drawContext.canvas
                canvas.saveLayer(Rect(Offset.Zero, size), paint)
                this@drawWithContent.drawContent()
                canvas.restore()
            }
            if (dx * dx + dy * dy <= radius * radius) {
                drawHighlighted()
            } else {
                if (mask.bounds != local) {
                    mask.bounds = local
                    mask.path.reset()
                    mask.path.addRoundRect(RoundRect(local, CornerRadius(radius)))
                }
                clipPath(mask.path, ClipOp.Difference) { this@drawWithContent.drawContent() }
                clipPath(mask.path) { drawHighlighted() }
            }
        }
    }
}
