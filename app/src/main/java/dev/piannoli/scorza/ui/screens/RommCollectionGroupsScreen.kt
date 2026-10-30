package dev.piannoli.scorza.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.scorza.romm.RommCollectionGroup
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.R
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.List
import dev.piannoli.ui.components.PillRowKeyValue
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.ScreenTitle
import dev.piannoli.ui.components.footerReservation
import dev.piannoli.ui.components.listTitleSpacing
import dev.piannoli.ui.components.pillItemHeight
import dev.piannoli.ui.components.screenInsets

data class RommGroupRow(val group: RommCollectionGroup, val label: String, val count: Int)

@Composable
fun RommCollectionGroupsScreen(
    rows: List<RommGroupRow>,
    selectedIndex: Int,
    scrollTarget: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 6.dp,
    onListStateChanged: ((LazyListState?) -> Unit)? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(screenInsets())) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = stringResource(R.string.romm_collections_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                List(
                    items = rows,
                    selectedIndex = selectedIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
                    itemHeight = itemHeight,
                    scrollTarget = scrollTarget,
                    onListStateChanged = onListStateChanged,
                ) { _, row, isSelected ->
                    PillRowKeyValue(
                        label = row.label,
                        value = row.count.toString(),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
            )
        }
    }
}
