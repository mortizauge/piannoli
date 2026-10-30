package dev.piannoli.scorza.ui.screens

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
import dev.piannoli.scorza.R
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.util.LoggingPrefs
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.DPAD_HORIZONTAL
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
fun LoggingSettingsScreen(
    screen: LauncherScreen.LoggingSettings,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val categories = LoggingPrefs.Category.entries.toList()

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = modifier.fillMaxSize().padding(screenInsets())) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = stringResource(R.string.logging_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                List(
                    items = categories,
                    selectedIndex = screen.selectedIndex.coerceIn(0, categories.size - 1),
                    itemHeight = itemHeight,
                    scrollTarget = screen.scrollTarget,
                ) { _, category, isSelected ->
                    PillRowKeyValue(
                        label = labelFor(category),
                        value = if (LoggingPrefs.isEnabled(category)) stringResource(R.string.value_on)
                            else stringResource(R.string.value_off),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(
                    buttonStyle.back to stringResource(R.string.label_back),
                    DPAD_HORIZONTAL to stringResource(R.string.label_toggle),
                ),
                rightItems = emptyList(),
            )
        }
    }
}

@Composable
private fun labelFor(category: LoggingPrefs.Category): String = when (category) {
    LoggingPrefs.Category.ROM_SCAN -> stringResource(R.string.logging_rom_scan)
    LoggingPrefs.Category.INPUT -> stringResource(R.string.logging_input)
    LoggingPrefs.Category.SESSION -> stringResource(R.string.logging_session)
    LoggingPrefs.Category.STORAGE -> stringResource(R.string.logging_storage)
    LoggingPrefs.Category.ROMM -> stringResource(R.string.logging_romm)
}
