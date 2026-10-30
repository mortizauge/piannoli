package dev.piannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.List
import dev.piannoli.ui.components.PillRowKeyValue
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.ScreenTitle
import dev.piannoli.ui.components.footerReservation
import dev.piannoli.ui.components.listTitleSpacing
import dev.piannoli.ui.components.pillItemHeight
import dev.piannoli.ui.components.screenInsets

@Composable
fun RetroAchievementsOfflinePlatformsScreen(
    screen: LauncherScreen.RetroAchievementsOfflinePlatforms,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 6.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
    onListStateChanged: ((LazyListState?) -> Unit)? = null,
) {
    val platforms = screen.platforms
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = modifier.fillMaxSize().padding(screenInsets())) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = stringResource(dev.piannoli.ui.R.string.ra_offline_sets_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                if (platforms.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = stringResource(dev.piannoli.ui.R.string.ra_offline_sets_empty))
                    }
                } else {
                    List(
                        items = platforms,
                        selectedIndex = screen.selectedIndex.coerceIn(0, (platforms.size - 1).coerceAtLeast(0)),
                        itemHeight = pillItemHeight(listLineHeight, listVerticalPadding),
                        scrollTarget = screen.scrollTarget,
                        onListStateChanged = onListStateChanged,
                    ) { _, platform, isSelected ->
                        PillRowKeyValue(
                            label = platform.name,
                            value = platform.count.toString(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(dev.piannoli.ui.R.string.label_back)),
                rightItems = if (platforms.isEmpty()) emptyList() else listOf(
                    buttonStyle.confirm to stringResource(dev.piannoli.ui.R.string.label_select),
                ),
            )
        }
    }
}
