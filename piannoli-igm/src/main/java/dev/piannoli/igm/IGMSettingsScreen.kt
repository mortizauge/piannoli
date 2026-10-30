package dev.piannoli.igm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.List
import dev.piannoli.ui.components.PillRowKeyValue
import dev.piannoli.ui.components.PillRowText
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.ScreenTitle
import dev.piannoli.ui.components.footerReservation
import dev.piannoli.ui.components.pillInternalPadding
import dev.piannoli.ui.components.pillItemHeight
import dev.piannoli.ui.components.pillVerticalPadding
import dev.piannoli.ui.components.screenInsets
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.ui.theme.LocalPiannoliTypography
import dev.piannoli.ui.theme.Spacing

@Composable
fun IGMSettingsScreen(
    title: String,
    items: kotlin.collections.List<IGMSettingsItem>,
    selectedIndex: Int,
    bottomBarLeft: kotlin.collections.List<Pair<String, String>>,
    bottomBarRight: kotlin.collections.List<Pair<String, String>>,
    coreInfo: String = "",
    description: String? = null,
    fontSize: TextUnit = 22.sp,
    lineHeight: TextUnit = 32.sp
) {
    val typo = LocalPiannoliTypography.current
    val verticalPadding = pillVerticalPadding()
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)
    val colors = LocalPiannoliColors.current

    ScreenBackground(backgroundAlpha = 0.85f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenInsets())
        ) {
            if (description != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = footerReservation())
                ) {
                    ScreenTitle(
                        text = items.getOrNull(selectedIndex)?.label ?: "",
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                    Spacer(modifier = Modifier.height(Spacing.Md))
                    Text(
                        text = description,
                        style = typo.bodyMedium.copy(
                            color = colors.text.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.padding(start = pillInternalPadding())
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = footerReservation())
                ) {
                    ScreenTitle(
                        text = title,
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                    List(
                        items = items,
                        selectedIndex = selectedIndex,
                        itemHeight = itemHeight
                    ) { _, item, isSelected ->
                        if (item.value != null) {
                            PillRowKeyValue(
                                label = item.label,
                                value = item.value,
                                isSelected = isSelected,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                verticalPadding = verticalPadding
                            )
                        } else {
                            PillRowText(
                                label = item.label,
                                isSelected = isSelected,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                verticalPadding = verticalPadding
                            )
                        }
                    }
                }

                if (coreInfo.isNotEmpty()) {
                    Text(
                        text = coreInfo,
                        style = typo.labelSmall.copy(
                            color = colors.text.copy(alpha = 0.7f)
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 44.dp)
                            .fillMaxWidth(0.9f)
                    )
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = bottomBarLeft,
                rightItems = if (description != null) emptyList() else bottomBarRight
            )
        }
    }
}
