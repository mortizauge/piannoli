package dev.piannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.scorza.R
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.DPAD_HORIZONTAL
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.List
import dev.piannoli.ui.components.PillRowKeyValue
import dev.piannoli.ui.components.PillRowText
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.ScreenTitle
import dev.piannoli.ui.components.WithoutScreenTitle
import dev.piannoli.ui.components.footerReservation
import dev.piannoli.ui.components.listTitleSpacing
import dev.piannoli.ui.components.pillItemHeight
import dev.piannoli.ui.components.screenInsets
import dev.piannoli.ui.components.screenBackground
import androidx.compose.material3.Text
import dev.piannoli.ui.theme.LocalPiannoliColors

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val state by viewModel.state.collectAsState()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelRemapping() }
    }

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenInsets())
    ) {
        if (state.inSubList) {
            WithoutScreenTitle(active = state.activeCategoryLabel == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                val categoryLabel = state.activeCategoryLabel
                if (categoryLabel != null) {
                    ScreenTitle(
                        text = stringResource(categoryLabel),
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                    )
                    Spacer(modifier = Modifier.height(listTitleSpacing()))
                }
                List(
                    items = state.items,
                    selectedIndex = state.selectedIndex,
                    itemHeight = itemHeight,
                    onListStateChanged = onListStateChanged,
                    key = { _, item -> item.key }
                ) { _, item, isSelected ->
                    val hasValue = item.valueText != null || item.valueRes != null || item.swatchColor != null
                    Box(modifier = Modifier.fillMaxWidth().alpha(if (item.disabled) 0.4f else 1f)) {
                    if (hasValue) {
                        PillRowKeyValue(
                            label = item.labelText ?: stringResource(item.labelRes),
                            value = if (item.key.startsWith("remap:") && item.valueText != null)
                                buttonStyle.physicalLabel(dev.piannoli.ui.MenuAction.valueOf(item.valueText))
                            else item.valueText ?: item.valueRes?.let { stringResource(it) } ?: "",
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            swatchColor = item.swatchColor
                        )
                    } else {
                        PillRowText(
                            label = item.labelText ?: stringResource(item.labelRes),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                    }
                }
            }
            }

            val selectedItem = state.items.getOrNull(state.selectedIndex)
            val isColorItem = selectedItem?.key?.startsWith("color_") == true
            val isEditableItem = selectedItem?.isEditable == true
            val isFghCollection = selectedItem?.key == "fgh_collection"
            val showChange = selectedItem?.canCycle != false && selectedItem?.disabled != true && (!isEditableItem || isFghCollection)
            val leftItems = if (showChange) {
                listOf(buttonStyle.back to stringResource(R.string.label_back), DPAD_HORIZONTAL to stringResource(R.string.label_change))
            } else {
                listOf(buttonStyle.back to stringResource(R.string.label_back))
            }
            val showClear = selectedItem?.key == "rom_directory" && selectedItem.valueText != null
            val isRommPairAction = selectedItem?.key == "romm_pair" || selectedItem?.key == "romm_pair_code"
            val isNavInto = selectedItem?.isEditable == true
                && selectedItem.valueText == null
                && selectedItem.valueRes == null
                && selectedItem.swatchColor == null
                && !isFghCollection
            val rightItems = if (selectedItem?.key == "remap_reset") {
                listOf(buttonStyle.confirm to stringResource(R.string.label_reset))
            } else if (selectedItem?.key?.startsWith("remap:") == true) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_set))
            } else if (isColorItem) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_select))
            } else if (isFghCollection) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_choose))
            } else if (state.activeCategory == "screen_geometry") {
                listOf(buttonStyle.north to stringResource(R.string.label_reset))
            } else if (showClear) {
                listOf(buttonStyle.north to stringResource(R.string.label_clear))
            } else if (isRommPairAction) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_select))
            } else if (isNavInto) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_open))
            } else {
                emptyList()
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = leftItems,
                rightItems = rightItems
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = stringResource(R.string.settings_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                List(
                    items = state.categories,
                    selectedIndex = state.categoryIndex,
                    itemHeight = itemHeight,
                    onListStateChanged = onListStateChanged,
                    key = { _, category -> category.key }
                ) { _, category, isSelected ->
                    PillRowText(
                        label = stringResource(category.labelRes),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select))
            )
            if (state.remappingAction != null) {
                val colors = LocalPiannoliColors.current
                Box(Modifier.fillMaxSize().screenBackground(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val selected = state.items.getOrNull(state.selectedIndex)
                        Text(
                            text = selected?.let { stringResource(it.labelRes) }.orEmpty(),
                            fontSize = listFontSize,
                            color = colors.text,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.remap_press_button), color = colors.text)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.remap_cancel_hint), color = colors.text.copy(alpha = 0.6f))
                    }
                }
            }
        }
        }
    }
}
