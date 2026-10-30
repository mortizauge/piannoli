package dev.piannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.ui.theme.LocalPiannoliFont
import dev.piannoli.ui.theme.LocalScaleFactor
import dev.piannoli.ui.theme.Radius

private const val GlyphTextSp = 14f
private const val GlyphPadVDp = 4f
private const val LegendTextSp = 12f
private const val LegendPadVDp = 6f

@Composable
private fun barTextStyle(sizeSp: Float, sf: Float): TextStyle = TextStyle(
    fontFamily = LocalPiannoliFont.current,
    fontWeight = FontWeight.Bold,
    fontSize = (sizeSp * sf).sp,
    lineHeight = (sizeSp * sf).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    ),
)

/**
 * What the bar actually measures, so the space reserved under a list matches it.
 *
 * The text is measured rather than taken as its line height: both runs set [LineHeightStyle.Trim]
 * to `Both`, which trims the line box back to the glyphs, so a run renders shorter than the line
 * height asks for. Assuming otherwise over-reserved by about 5dp.
 */
@Composable
fun bottomBarHeight(): Dp {
    val sf = LocalScaleFactor.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val glyphStyle = barTextStyle(GlyphTextSp, sf)
    val legendStyle = barTextStyle(LegendTextSp, sf)
    return remember(glyphStyle, legendStyle, density, sf) {
        val glyphText = measurer.measure("A", glyphStyle, constraints = Constraints()).size.height
        val legendText = measurer.measure("A", legendStyle, constraints = Constraints()).size.height
        with(density) {
            val glyphPill = glyphText.toDp() + (GlyphPadVDp * 2 * sf).dp
            maxOf(glyphPill, legendText.toDp()) + (LegendPadVDp * 2 * sf).dp
        }
    }
}

@Composable
fun GlyphPill(content: @Composable () -> Unit) {
    val innerPill = LocalPiannoliColors.current.accent.copy(alpha = 0.30f)
    val sf = LocalScaleFactor.current
    Box(
        modifier = Modifier
            .clip(Radius.Pill)
            .background(innerPill)
            .padding(horizontal = (10 * sf).dp, vertical = (GlyphPadVDp * sf).dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun GlyphPill(button: String) {
    val accent = LocalPiannoliColors.current.accent
    val sf = LocalScaleFactor.current
    GlyphPill {
        Text(
            text = button,
            style = barTextStyle(GlyphTextSp, sf).copy(color = accent)
        )
    }
}

@Composable
fun LegendPill(label: String, modifier: Modifier = Modifier, glyphs: @Composable () -> Unit) {
    val accent = LocalPiannoliColors.current.accent
    val outerPill = accent.copy(alpha = 0.15f)
    val sf = LocalScaleFactor.current

    Row(
        modifier = modifier
            .clip(Radius.Pill)
            .background(outerPill)
            .padding(start = (5 * sf).dp, end = (14 * sf).dp, top = (LegendPadVDp * sf).dp, bottom = (LegendPadVDp * sf).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((8 * sf).dp)
    ) {
        glyphs()

        Text(
            text = label,
            style = barTextStyle(LegendTextSp, sf).copy(color = accent)
        )
    }
}

@Composable
fun LegendPill(button: String, label: String, modifier: Modifier = Modifier) =
    LegendPill(label = label, modifier = modifier) { GlyphPill(button) }

@Composable
fun BottomBar(
    modifier: Modifier = Modifier,
    leftItems: List<Pair<String, String>>,
    rightItems: List<Pair<String, String>>,
    onLeftItemClick: ((Int) -> Unit)? = null,
    onRightItemClick: ((Int) -> Unit)? = null,
) {
    val sf = LocalScaleFactor.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((8 * sf).dp)) {
            leftItems.forEachIndexed { index, (button, label) ->
                LegendPill(
                    button = button,
                    label = label,
                    modifier = if (onLeftItemClick != null) Modifier.clickable { onLeftItemClick(index) } else Modifier,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy((8 * sf).dp)) {
            rightItems.forEachIndexed { index, (button, label) ->
                LegendPill(
                    button = button,
                    label = label,
                    modifier = if (onRightItemClick != null) Modifier.clickable { onRightItemClick(index) } else Modifier,
                )
            }
        }
    }
}
