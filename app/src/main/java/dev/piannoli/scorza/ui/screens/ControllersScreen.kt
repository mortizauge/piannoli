package dev.piannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.scorza.R
import dev.piannoli.scorza.input.DeviceMapping
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.ui.viewmodel.ConnectedRow
import dev.piannoli.scorza.ui.viewmodel.ControllersViewModel
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.HintRow
import dev.piannoli.ui.components.List
import dev.piannoli.ui.components.PillRowKeyValue
import dev.piannoli.ui.components.PillRowText
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.ScreenTitle
import dev.piannoli.ui.components.SectionHeader
import dev.piannoli.ui.components.footerReservation
import dev.piannoli.ui.components.listTitleSpacing
import dev.piannoli.ui.components.pillItemHeight
import dev.piannoli.ui.components.screenInsets

sealed interface ControllersListEntry {
    val isSelectable: Boolean
    data class Header(val label: String) : ControllersListEntry { override val isSelectable = false }
    data class Hint(val text: String) : ControllersListEntry { override val isSelectable = false }
    data class ConnectedItem(val row: ConnectedRow) : ControllersListEntry { override val isSelectable = true }
    data class SavedItem(val mapping: DeviceMapping) : ControllersListEntry { override val isSelectable = true }
}

@Composable
fun ControllersScreen(
    screen: LauncherScreen.Controllers,
    viewModel: ControllersViewModel,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    backgroundAlpha: Float = 1f,
    backgroundColor: androidx.compose.ui.graphics.Color? = null,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val state by viewModel.state.collectAsState()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)

    val connectedHeader = stringResource(R.string.controllers_connected_now).uppercase()
    val emptyHint = stringResource(R.string.controllers_empty_connected)
    val savedHeader = stringResource(R.string.controllers_saved_mappings).uppercase()

    val entries = remember(state, connectedHeader, emptyHint, savedHeader) {
        buildList {
            add(ControllersListEntry.Header(connectedHeader))
            if (state.connected.isEmpty()) {
                add(ControllersListEntry.Hint(emptyHint))
            } else {
                state.connected.forEach { add(ControllersListEntry.ConnectedItem(it)) }
            }
            if (state.savedMappings.isNotEmpty()) {
                add(ControllersListEntry.Header(savedHeader))
                state.savedMappings.forEach { add(ControllersListEntry.SavedItem(it)) }
            }
        }
    }

    val selectableIndices = remember(entries) {
        entries.mapIndexedNotNull { idx, e -> if (e.isSelectable) idx else null }
    }
    val highlightedEntryIndex = selectableIndices.getOrNull(
        screen.selectedIndex.coerceIn(0, (selectableIndices.size - 1).coerceAtLeast(0))
    ) ?: -1

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint, backgroundAlpha = backgroundAlpha, backgroundColor = backgroundColor) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(screenInsets())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = stringResource(R.string.setting_controllers),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                List(
                    items = entries,
                    selectedIndex = highlightedEntryIndex,
                    itemHeight = itemHeight,
                ) { index, entry, isSelected ->
                    when (entry) {
                        is ControllersListEntry.Header -> SectionHeader(
                            text = entry.label,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        is ControllersListEntry.Hint -> HintRow(
                            text = entry.text,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        is ControllersListEntry.ConnectedItem -> {
                            val portLabel = when {
                                entry.row.isBuiltIn -> "—"
                                entry.row.port != null -> "P${entry.row.port + 1}"
                                else -> "—"
                            }
                            PillRowKeyValue(
                                label = entry.row.mapping.displayName,
                                value = portLabel,
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                            )
                        }
                        is ControllersListEntry.SavedItem -> PillRowText(
                            label = entry.mapping.displayName,
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
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = if (highlightedEntryIndex >= 0)
                    listOf(buttonStyle.confirm to stringResource(R.string.label_select))
                else emptyList()
            )
        }
    }
}

