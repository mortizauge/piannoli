package dev.piannoli.igm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.ListSection
import dev.piannoli.ui.components.PillRowKeyValue
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.ScreenTitle
import dev.piannoli.ui.components.SectionedList
import dev.piannoli.ui.components.footerReservation
import dev.piannoli.ui.components.pillItemHeight
import dev.piannoli.ui.components.pillVerticalPadding
import dev.piannoli.ui.components.screenInsets
import dev.piannoli.ui.theme.Spacing

data class CheatRowUi(val label: String, val enabled: Boolean, val supported: Boolean)

@Composable
fun CheatsScreen(
    title: String,
    sections: List<ListSection<CheatRowUi>>,
    selectedIndex: Int,
    onLabel: String,
    offLabel: String,
    bottomBarLeft: List<Pair<String, String>>,
    bottomBarRight: List<Pair<String, String>>,
    fontSize: TextUnit = 22.sp,
    lineHeight: TextUnit = 32.sp,
) {
    val verticalPadding = pillVerticalPadding()
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)

    ScreenBackground(backgroundAlpha = 0.85f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenInsets())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(text = title, fontSize = fontSize, lineHeight = lineHeight)
                Spacer(modifier = Modifier.height(Spacing.Sm))
                SectionedList(
                    sections = sections,
                    selectedIndex = selectedIndex,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    verticalPadding = verticalPadding,
                    itemHeight = itemHeight
                ) { _, item, isSelected ->
                    Box(modifier = if (item.supported) Modifier else Modifier.alpha(0.4f)) {
                        PillRowKeyValue(
                            label = item.label,
                            value = if (item.enabled) onLabel else offLabel,
                            isSelected = isSelected,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            verticalPadding = verticalPadding
                        )
                    }
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = bottomBarLeft,
                rightItems = bottomBarRight
            )
        }
    }
}
