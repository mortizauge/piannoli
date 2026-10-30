package dev.piannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import dev.piannoli.scorza.R
import dev.piannoli.scorza.romm.sync.PreLaunchOutcome
import dev.piannoli.scorza.romm.sync.SyncDirection
import dev.piannoli.scorza.ui.screens.SyncHistoryRow
import dev.piannoli.scorza.romm.download.DownloadStatus
import dev.piannoli.scorza.romm.download.RommDownloadItem
import dev.piannoli.scorza.romm.download.RommDownloadKind
import dev.piannoli.scorza.romm.download.inDisplayOrder
import dev.piannoli.scorza.ui.screens.ConflictChoice
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.screens.KeyboardHost
import dev.piannoli.scorza.romm.RommArtType
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.DPAD_HORIZONTAL
import dev.piannoli.ui.theme.PiannoliIcons
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.ui.theme.LocalPiannoliFont
import dev.piannoli.ui.theme.LocalPiannoliIconFont
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.QuickInfoOverlay
import dev.piannoli.ui.components.ColorPickerOverlay
import dev.piannoli.ui.components.HexColorInputOverlay
import dev.piannoli.ui.components.KeyboardHelpOverlay
import dev.piannoli.ui.components.KeyboardOverlay
import dev.piannoli.ui.components.LaunchErrorDialog
import dev.piannoli.ui.components.List
import dev.piannoli.ui.components.ListSection
import dev.piannoli.ui.components.PillRowInfo
import dev.piannoli.ui.components.PillRowKeyValue
import dev.piannoli.ui.components.PillRowText
import dev.piannoli.ui.components.MessageOverlay
import dev.piannoli.ui.components.MissingAppDialog
import dev.piannoli.ui.components.MissingCoreDialog
import dev.piannoli.ui.components.OverlayScrim
import dev.piannoli.ui.components.SectionedList
import dev.piannoli.ui.components.RAAccountOverlay
import dev.piannoli.ui.components.RALoggingInOverlay
import dev.piannoli.ui.components.LoadingOverlay
import dev.piannoli.ui.components.RommConnectedOverlay
import dev.piannoli.ui.components.RommPairingOverlay
import dev.piannoli.ui.components.RestartOverlay
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.ScreenTitle
import dev.piannoli.ui.components.UpdateDownloadOverlay
import dev.piannoli.ui.components.footerReservation
import dev.piannoli.ui.components.listTitleSpacing
import dev.piannoli.ui.components.pillItemHeight
import dev.piannoli.ui.components.screenPadding
import dev.piannoli.ui.theme.Spacing


@Composable
fun DialogOverlay(
    dialogState: DialogState,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    downloadProgress: Float = 0f,
    downloadError: String? = null,
    downloads: List<RommDownloadItem> = emptyList(),
    updateAvailable: Boolean = false,
    buttonStyle: ButtonStyle = ButtonStyle(),
    // Only the Tools and Ports lists offer to drop a shortcut for an app that has gone missing,
    // and that depends on the list being viewed rather than on anything the dialog carries.
    appListPlatformTag: String? = null,
    onKeyboardChange: ((dev.piannoli.ui.components.KeyboardState) -> Unit)? = null,
    onKeyboardConfirm: () -> Unit = {},
    onKeyboardCancel: () -> Unit = {},
    onKeyboardHelp: () -> Unit = {},
    useSystemKeyboard: Boolean = false,
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    when (dialogState) {
        is DialogState.ExtensionFilters -> ListDialogScreen(
            backgroundImagePath = backgroundImagePath,
            backgroundTint = backgroundTint,
            title = dialogState.title,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.filter_extension_action)),
            buttonStyle = buttonStyle,
        ) {
            List(
                items = dialogState.extensions,
                selectedIndex = dialogState.selectedIndex,
                itemHeight = itemHeight,
            ) { _, extension, selected ->
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = extension in dialogState.excluded,
                        onCheckedChange = null,
                    )
                    PillRowText(
                        label = if (extension.isEmpty()) stringResource(R.string.filter_no_extension) else ".$extension",
                        isSelected = selected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
            if (!dialogState.loading && dialogState.extensions.isEmpty()) {
                androidx.compose.material3.Text(stringResource(R.string.filter_extensions_empty),
                    color = dev.piannoli.ui.theme.LocalPiannoliColors.current.text)
            }
        }
        DialogState.ScraperOverwriteConfirm -> ConfirmOverlay(
            message = stringResource(R.string.scraper_overwrite_confirm),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.scraper_overwrite),
            backgroundImagePath = backgroundImagePath,
            backgroundTint = backgroundTint,
        )
        is DialogState.ContextMenu, is DialogState.ScraperMenu -> {
            val title: String
            val options: List<String>
            val selectedIndex: Int
            if (dialogState is DialogState.ContextMenu) {
                title = dialogState.gameName
                options = dialogState.options
                selectedIndex = dialogState.selectedOption
            } else {
                val menu = dialogState as DialogState.ScraperMenu
                title = menu.title
                options = menu.options
                selectedIndex = menu.selectedIndex
            }
            val preview = (dialogState as? DialogState.ScraperMenu)?.previews?.getOrNull(selectedIndex)
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = title,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = preview != null || options.any { it.contains('\t') },
                rightBottomItems = emptyList(),
                buttonStyle = buttonStyle
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        List(
                            items = options,
                            selectedIndex = selectedIndex,
                            itemHeight = itemHeight
                        ) { _, option, isSelected ->
                            val parts = option.split("\t", limit = 2)
                            if (parts.size == 2) {
                                PillRowKeyValue(
                                    label = parts[0],
                                    value = parts[1],
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding
                                )
                            } else {
                                PillRowText(
                                    label = option,
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding
                                )
                            }
                        }
                    }
                    if (preview != null) {
                        SubcomposeAsyncImage(
                            model = preview,
                            contentDescription = options.getOrNull(selectedIndex),
                            modifier = Modifier.fillMaxWidth(0.4f).fillMaxHeight().padding(start = 12.dp),
                            contentScale = ContentScale.Fit,
                            loading = {
                                Text(
                                    stringResource(R.string.steamgrid_preview_loading),
                                    color = LocalPiannoliColors.current.text,
                                )
                            },
                            error = {
                                Text(
                                    stringResource(R.string.steamgrid_preview_error),
                                    color = LocalPiannoliColors.current.text,
                                )
                            },
                        )
                    }
                }
            }
        }

        DialogState.ArtworkImporting -> LoadingOverlay(
            message = stringResource(R.string.artwork_importing),
            showBack = false,
        )
        is DialogState.ScraperStatus -> {
            if (dialogState.busy) {
                LoadingOverlay(
                    message = dialogState.message,
                    buttonStyle = buttonStyle,
                    title = stringResource(if (dialogState.bulk) R.string.bulk_scraping else dev.piannoli.ui.R.string.loading_title),
                )
            } else {
                MessageOverlay(
                    message = dialogState.message,
                    buttonStyle = buttonStyle,
                    buttonLabel = stringResource(R.string.label_back),
                )
            }
        }

        is DialogState.BulkContextMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.selected_count, dialogState.gamePaths.size),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = emptyList(),
                buttonStyle = buttonStyle
            ) {
                List(
                    items = dialogState.options,
                    selectedIndex = dialogState.selectedOption,
                    itemHeight = itemHeight
                ) { _, option, isSelected ->
                    PillRowText(
                        label = option,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.ColorPicker -> {
            ColorPickerOverlay(
                title = dialogState.title,
                selectedRow = dialogState.selectedRow,
                selectedCol = dialogState.selectedCol,
                currentColor = dialogState.currentColor,
                titleFontSize = listFontSize,
                titleLineHeight = listLineHeight,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.HexColorInput -> {
            HexColorInputOverlay(
                title = dialogState.title,
                currentHex = dialogState.currentHex,
                selectedIndex = dialogState.selectedIndex,
                titleFontSize = listFontSize,
                titleLineHeight = listLineHeight,
                buttonStyle = buttonStyle
            )
        }

        is KeyboardHost -> {
            val host = dialogState
            val keyboardTitle = host.titleRes?.let { stringResource(it) }
                ?: (dialogState as? DialogState.RenameInput)?.let { rn ->
                    when (rn.gameName) {
                        "launcher_global_search" -> stringResource(R.string.search_global)
                        "romm_global_search" -> stringResource(R.string.search_romm)
                        "launcher_search", "romm_search", "romm_collection_search" -> rn.searchScope?.let { stringResource(R.string.search_in_platform, it) }
                        "romm_device_name" -> stringResource(dev.piannoli.ui.R.string.dialog_romm_device_name_title)
                        else -> null
                    }
                }
            KeyboardOverlay(
                useSystemKeyboard = useSystemKeyboard,
                state = host.keyboard,
                masked = dialogState is DialogState.ScraperInput &&
                    dialogState.field in setOf(
                        dev.piannoli.scorza.scraper.ScraperField.PASSWORD,
                        dev.piannoli.scorza.scraper.ScraperField.API_KEY,
                    ),
                title = keyboardTitle,
                buttonStyle = buttonStyle,
                onStateChange = onKeyboardChange,
                onConfirm = onKeyboardConfirm,
                onCancel = onKeyboardCancel,
                onHelp = onKeyboardHelp,
            )
        }

        is DialogState.KeyboardHelp -> {
            KeyboardHelpOverlay(
                onClose = onKeyboardHelp,
                layout = dialogState.layout,
                titleFontSize = listFontSize,
                titleLineHeight = listLineHeight,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.About -> {
            AboutOverlay(statusMessage = dialogState.statusMessage, updateAvailable = updateAvailable, buttonStyle = buttonStyle)
        }

        is DialogState.RAAccount -> {
            RAAccountOverlay(username = dialogState.username, buttonStyle = buttonStyle)
        }

        is DialogState.RALoggingIn -> {
            RALoggingInOverlay(message = dialogState.message, buttonStyle = buttonStyle)
        }

        is DialogState.RAPreloadProgress -> {
            RALoggingInOverlay(
                message = stringResource(R.string.ra_preload_progress, dialogState.gameName),
                buttonStyle = buttonStyle,
            )
        }
        is DialogState.RAPreloadResult -> {
            MessageOverlay(
                message = dialogState.message,
                buttonStyle = buttonStyle,
                buttonLabel = stringResource(R.string.label_back),
            )
        }

        is DialogState.RommPairing -> {
            RommPairingOverlay(
                host = dialogState.host,
                message = dialogState.message,
                waitingApproval = dialogState.waitingApproval,
                qrBitmap = dialogState.qrBitmap,
                buttonStyle = buttonStyle,
            )
        }
        is DialogState.RommConnected -> {
            RommConnectedOverlay(host = dialogState.host, username = dialogState.username, version = dialogState.version, buttonStyle = buttonStyle)
        }

        is DialogState.UpdateDownload -> {
            UpdateDownloadOverlay(
                versionName = dialogState.versionName,
                changelog = dialogState.changelog,
                progress = downloadProgress,
                error = downloadError,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.RestartRequired -> {
            RestartOverlay(message = stringResource(R.string.restart_required), buttonStyle = buttonStyle)
        }

        is DialogState.LibrarySwitchConfirm -> {
            ConfirmOverlay(
                message = stringResource(R.string.library_switch_confirm),
                buttonStyle = buttonStyle,
                confirmLabel = stringResource(R.string.label_proceed),
            )
        }

        is DialogState.IntentAuditResult -> {
            RestartOverlay(message = dialogState.message, buttonStyle = buttonStyle)
        }

        is DialogState.SystemFoldersRegenerated -> {
            RestartOverlay(message = dialogState.message, buttonStyle = buttonStyle)
        }

        is DialogState.QuickMenu -> {
            val conflictCount = dialogState.conflictCount
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.quick_menu_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(
                    items = dialogState.rows,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight
                ) { _, row, isSelected ->
                    val label = when {
                        row == dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.CONFLICTS && conflictCount > 0 ->
                            pluralStringResource(dev.piannoli.ui.R.plurals.quick_menu_conflicts, conflictCount, conflictCount)
                        row == dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.ERRORS && dialogState.syncErrorCount > 0 ->
                            pluralStringResource(dev.piannoli.ui.R.plurals.quick_menu_sync_errors, dialogState.syncErrorCount, dialogState.syncErrorCount)
                        else -> quickMenuLabel(row)
                    }
                    PillRowText(
                        label = label,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }
        is DialogState.QuickInfo -> {
            QuickInfoOverlay(
                addresses = dialogState.addresses,
                selectedIndex = dialogState.selectedIndex,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.RommActionsMenu -> {
            val rows = RommActionRow.visibleRows(dialogState.hasDownloads)
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_actions_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(items = rows, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, row, isSelected ->
                    PillRowText(
                        label = stringResource(row.labelRes),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.RommVersionPicker -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(dev.piannoli.scorza.R.string.romm_version_picker_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(
                    buttonStyle.confirm to stringResource(dev.piannoli.scorza.R.string.label_download)
                ),
                buttonStyle = buttonStyle,
            ) {
                List(items = dialogState.members, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, entry, isSelected ->
                    PillRowKeyValue(
                        label = if (entry.isPrimary) "${PiannoliIcons.Primary.glyph} ${entry.label}" else entry.label,
                        value = dev.piannoli.scorza.ui.screens.RommGameDetailLayout.formatBytes(entry.game.sizeBytes),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        dotIndicator = if (entry.present) true else null,
                    )
                }
            }
        }

        is DialogState.RommSettingsMenu -> {
            val rows = RommSettingsRow.entries.toList()
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_settings_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                leftBottomItems = if (rows[dialogState.selectedIndex].isCycle)
                    listOf(DPAD_HORIZONTAL to stringResource(R.string.label_change)) else emptyList(),
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(items = rows, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, row, isSelected ->
                    when (row) {
                        RommSettingsRow.CONCURRENT -> PillRowKeyValue(
                            label = stringResource(R.string.romm_settings_concurrent),
                            value = dialogState.concurrent.toString(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSettingsRow.COVER_ART -> PillRowKeyValue(
                            label = stringResource(R.string.romm_settings_cover_art),
                            value = stringResource(rommArtLabelRes(dialogState.artType)),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        else -> PillRowText(
                            label = stringResource(row.labelRes),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
        }

        is DialogState.RommAdvancedMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_settings_advanced),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(
                    items = ROMM_ADVANCED_ROWS,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight
                ) { _, labelRes, isSelected ->
                    PillRowText(
                        label = stringResource(labelRes),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.RommSaveSyncMenu -> {
            val rows = RommSaveSyncRow.visibleRows(dialogState.supported, dialogState.enabled, dialogState.pendingConflicts, dialogState.syncErrors, dialogState.hasBackups)
            val selectedRow = rows.getOrNull(dialogState.selectedIndex)
            val isCycleRow = selectedRow == RommSaveSyncRow.TOGGLE || selectedRow == RommSaveSyncRow.BACKUPS
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.setting_romm_save_sync),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                leftBottomItems = if (isCycleRow)
                    listOf(DPAD_HORIZONTAL to stringResource(R.string.label_change)) else emptyList(),
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(items = rows, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, row, isSelected ->
                    when (row) {
                        RommSaveSyncRow.TOGGLE -> PillRowKeyValue(
                            label = stringResource(R.string.setting_romm_save_sync),
                            value = stringResource(
                                if (!dialogState.supported) R.string.romm_save_sync_needs_500
                                else if (dialogState.enabled) R.string.value_on else R.string.value_off
                            ),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.BACKUPS -> PillRowKeyValue(
                            label = stringResource(R.string.setting_romm_save_backups),
                            value = if (dialogState.backupCount <= 0) stringResource(R.string.value_off)
                            else dialogState.backupCount.toString(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.HISTORY -> PillRowText(
                            label = stringResource(R.string.sync_history_title),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.CONFLICTS -> PillRowText(
                            label = pluralStringResource(dev.piannoli.ui.R.plurals.quick_menu_conflicts, dialogState.pendingConflicts, dialogState.pendingConflicts),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.ERRORS -> PillRowText(
                            label = pluralStringResource(dev.piannoli.ui.R.plurals.quick_menu_sync_errors, dialogState.syncErrors, dialogState.syncErrors),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.RESTORE -> PillRowText(
                            label = stringResource(dev.piannoli.ui.R.string.save_backup_restore),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
        }

        is DialogState.RommConfirm -> {
            val message = when (dialogState.action) {
                dev.piannoli.scorza.ui.screens.RommConfirmAction.REBUILD_CACHE -> R.string.romm_rebuild_confirm
                dev.piannoli.scorza.ui.screens.RommConfirmAction.DISCONNECT -> R.string.romm_disconnect_confirm
                dev.piannoli.scorza.ui.screens.RommConfirmAction.CANCEL_DOWNLOAD -> R.string.romm_cancel_confirm
                dev.piannoli.scorza.ui.screens.RommConfirmAction.CANCEL_ALL -> R.string.romm_cancel_all_confirm
            }
            ConfirmOverlay(
                message = stringResource(message),
                buttonStyle = buttonStyle
            )
        }

        is DialogState.RommPlatformToggle -> {
            val colors = LocalPiannoliColors.current
            val font = LocalPiannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_platforms_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_toggle)),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.items.isEmpty()) {
                    Text(stringResource(R.string.romm_platforms_empty), color = colors.text.copy(alpha = 0.5f), fontFamily = font, fontSize = listFontSize)
                } else {
                    List(
                        items = dialogState.items,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight
                    ) { _, item, isSelected ->
                        PillRowText(
                            label = item.displayName,
                            isSelected = isSelected,
                            checkState = item.visible,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
        }

        is DialogState.RommCollectionToggle -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_collections_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_toggle)),
                buttonStyle = buttonStyle
            ) {
                List(
                    items = dialogState.items,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight
                ) { _, item, isSelected ->
                    PillRowText(
                        label = item.displayName,
                        isSelected = isSelected,
                        checkState = item.visible,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.RescanProgress -> {
            dev.piannoli.scorza.ui.screens.HousekeepingScreen(
                kind = dev.piannoli.scorza.ui.screens.HousekeepingKind.LIBRARY_REFRESH,
                progress = dialogState.progress,
                statusLabel = dialogState.label,
            )
        }

        is DialogState.RommArtResults -> {
            dev.piannoli.scorza.ui.screens.RommArtResultsScreen(
                results = dialogState.results,
                selectedIndex = dialogState.selectedIndex,
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = buttonStyle,
            )
        }

        is DialogState.RommDownloads -> {
            val colors = LocalPiannoliColors.current
            val font = LocalPiannoliFont.current
            val ordered = downloads.inDisplayOrder()
            val firstDoneIndex = ordered.indexOfFirst { it.status == DownloadStatus.Done }
            val confirmLabel = when (ordered.getOrNull(dialogState.selectedIndex)?.status) {
                is DownloadStatus.Failed -> R.string.label_retry
                DownloadStatus.Queued, is DownloadStatus.Downloading -> R.string.label_cancel
                else -> null
            }
            val activeCount = downloads.count {
                it.status == DownloadStatus.Queued || it.status is DownloadStatus.Downloading
            }
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_download_queue),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = buildList {
                    if (confirmLabel != null) add(buttonStyle.confirm to stringResource(confirmLabel))
                    if (activeCount >= 2) add(buttonStyle.north to stringResource(R.string.label_cancel_all))
                },
                buttonStyle = buttonStyle,
            ) {
                if (ordered.isEmpty()) {
                    Text(stringResource(R.string.romm_download_empty), color = colors.text.copy(alpha = 0.5f), fontFamily = font, fontSize = listFontSize)
                } else {
                    val active = if (firstDoneIndex < 0) ordered else ordered.subList(0, firstDoneIndex)
                    val done = if (firstDoneIndex < 0) emptyList() else ordered.subList(firstDoneIndex, ordered.size)
                    val completedHeader = stringResource(R.string.romm_download_completed).uppercase()
                    val sections = buildList {
                        if (active.isNotEmpty()) add(ListSection(header = null, items = active))
                        if (done.isNotEmpty()) add(ListSection(header = completedHeader, items = done))
                    }
                    SectionedList(
                        sections = sections,
                        selectedIndex = dialogState.selectedIndex,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        itemHeight = itemHeight,
                    ) { _, item, isSelected ->
                        DownloadRow(item, isSelected, listFontSize, listLineHeight, listVerticalPadding)
                    }
                }
            }
        }

        is DialogState.SaveSyncConflict -> {
            val unknown = stringResource(android.R.string.unknownName)
            val localLabel = dialogState.conflict.localTime ?: unknown
            val serverLabel = dialogState.conflict.serverTime ?: unknown
            val options = listOf(
                stringResource(R.string.save_conflict_keep_local) to localLabel,
                stringResource(R.string.save_conflict_use_server) to serverLabel,
            )
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.save_conflict_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle,
            ) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        text = dialogState.conflict.base,
                        color = LocalPiannoliColors.current.text,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.save_conflict_subtitle),
                        color = LocalPiannoliColors.current.text.copy(alpha = 0.55f),
                        fontSize = listFontSize * 0.8f,
                        lineHeight = listLineHeight * 0.8f,
                    )
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                    List(
                        items = options,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight,
                    ) { _, option, isSelected ->
                        PillRowKeyValue(
                            label = option.first,
                            value = option.second,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
        }

        is DialogState.SaveSyncStaleBlock -> {
            val options = listOf(
                stringResource(R.string.save_stale_play_local),
                stringResource(R.string.label_back),
            )
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.save_stale_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle,
            ) {
                List(
                    items = options,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight,
                ) { _, option, isSelected ->
                    PillRowText(
                        label = option,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
        }

        is DialogState.SyncHistory -> {
            val colors = LocalPiannoliColors.current
            val font = LocalPiannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.sync_history_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = emptyList(),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sync_history_empty),
                        color = colors.text.copy(alpha = 0.5f),
                        fontFamily = font,
                        fontSize = listFontSize,
                    )
                } else {
                    List(
                        items = dialogState.entries,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight,
                    ) { _, row, isSelected ->
                        SyncHistoryRowItem(row, isSelected, listFontSize, listLineHeight, listVerticalPadding)
                    }
                }
            }
        }

        is DialogState.SyncErrors -> {
            val colors = LocalPiannoliColors.current
            val font = LocalPiannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(dev.piannoli.ui.R.string.sync_errors_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = emptyList(),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.errors.isEmpty()) {
                    Text(
                        text = stringResource(dev.piannoli.ui.R.string.sync_errors_empty),
                        color = colors.text.copy(alpha = 0.5f),
                        fontFamily = font,
                        fontSize = listFontSize,
                    )
                } else {
                    List(
                        items = dialogState.errors,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight,
                    ) { _, err, isSelected ->
                        SyncErrorRowItem(err, isSelected, listFontSize, listLineHeight, listVerticalPadding)
                    }
                }
            }
        }

        is DialogState.RommSavesMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.title,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(items = dialogState.options, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, option, isSelected ->
                    PillRowText(
                        label = option,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.SaveBackupGames -> {
            val colors = LocalPiannoliColors.current
            val font = LocalPiannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(dev.piannoli.ui.R.string.save_backup_games_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.games.isEmpty()) {
                    Text(text = stringResource(dev.piannoli.ui.R.string.save_backup_none), color = colors.text.copy(alpha = 0.5f), fontFamily = font, fontSize = listFontSize)
                } else {
                    List(items = dialogState.games, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, game, isSelected ->
                        PillRowKeyValue(
                            label = game.displayName,
                            value = game.tag.uppercase(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
        }

        is DialogState.SaveBackupList -> {
            val colors = LocalPiannoliColors.current
            val font = LocalPiannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.displayName,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.backups.isEmpty()) {
                    Text(text = stringResource(dev.piannoli.ui.R.string.save_backup_none), color = colors.text.copy(alpha = 0.5f), fontFamily = font, fontSize = listFontSize)
                } else {
                    List(items = dialogState.backups, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, backup, isSelected ->
                        PillRowKeyValue(
                            label = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(backup.stamp)),
                            value = dev.piannoli.scorza.ui.screens.RommGameDetailLayout.formatBytes(backup.sizeBytes),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
        }

        is DialogState.SaveBackupRestoreConfirm -> {
            ConfirmOverlay(
                message = "${stringResource(dev.piannoli.ui.R.string.save_backup_restore_confirm)}\n${dialogState.dateLabel}",
                buttonStyle = buttonStyle,
            )
        }

        is DialogState.ConflictsMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(dev.piannoli.ui.R.string.conflicts_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                leftBottomItems = listOf(DPAD_HORIZONTAL to stringResource(R.string.label_change)),
                rightBottomItems = listOf(buttonStyle.start to stringResource(R.string.label_confirm)),
                buttonStyle = buttonStyle,
            ) {
                List(
                    items = dialogState.rows,
                    selectedIndex = dialogState.selectedIndex,
                ) { _, row, isSelected ->
                    ConflictRowItem(
                        row = row,
                        choiceLabel = conflictChoiceLabel(row.choice),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
        }

        is DialogState.ConflictsApplying -> {
            OverlayScrim {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.conflicts_applying),
                    color = LocalPiannoliColors.current.text,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
            }
        }

        is DialogState.MissingCore -> MissingCoreDialog(
            coreName = dialogState.coreName,
            packageLabel = dialogState.packageLabel,
            showChangeEmulator = dialogState.platformTag != null,
            buttonStyle = buttonStyle,
        )

        is DialogState.MissingApp -> MissingAppDialog(
            appName = dialogState.appName,
            showRemove = appListPlatformTag == "tools" || appListPlatformTag == "ports",
            showChangeEmulator = dialogState.platformTag != null,
            buttonStyle = buttonStyle,
        )

        is DialogState.LaunchError -> LaunchErrorDialog(dialogState.message, buttonStyle = buttonStyle)

        is DialogState.DeleteConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_delete_confirm, dialogState.gameName),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_delete),
        )

        is DialogState.DeleteCollectionConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_delete_confirm, dialogState.displayName),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_delete),
        )

        is DialogState.QuitConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_quit_confirm),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_quit),
        )

        is DialogState.PlatformResetConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_reset_platform_confirm, dialogState.platformName),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_reset),
        )

        is DialogState.RenameResult -> MessageOverlay(
            message = if (dialogState.success) {
                stringResource(R.string.dialog_rename_success)
            } else {
                stringResource(R.string.dialog_rename_failed, dialogState.message)
            },
            buttonStyle = buttonStyle,
        )

        is DialogState.CollectionCreated -> MessageOverlay(
            message = stringResource(R.string.collection_created, dialogState.collectionName),
            buttonStyle = buttonStyle,
        )

        // Held across a network round trip to the server while input is already blocked, so
        // without this the launcher reads as frozen for as long as the server takes to answer.
        is DialogState.SaveSyncChecking -> {
            OverlayScrim {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.save_sync_checking),
                    color = LocalPiannoliColors.current.text,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
            }
        }

        else -> {}
    }
}

private const val SYNC_GLYPH_SCALE = 0.9f
private const val SYNC_TIME_SCALE = 0.8f

@Composable
private fun SyncHistoryRowItem(row: SyncHistoryRow, isSelected: Boolean, fontSize: TextUnit, lineHeight: TextUnit, verticalPadding: Dp) {
    val colors = LocalPiannoliColors.current
    val textColor = if (isSelected) colors.highlightText else colors.text
    val muted = textColor.copy(alpha = 0.55f)
    val glyph = when (row.direction) {
        SyncDirection.UPLOAD -> PiannoliIcons.SyncUpload.glyph
        SyncDirection.DOWNLOAD -> PiannoliIcons.SyncDownload.glyph
        SyncDirection.CONFLICT, SyncDirection.ERROR -> PiannoliIcons.SyncAlert.glyph
    }
    dev.piannoli.ui.components.PillRow(
        isSelected = isSelected,
        verticalPadding = verticalPadding,
        lineHeight = lineHeight,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(glyph, color = textColor, fontFamily = LocalPiannoliIconFont.current, fontSize = (fontSize.value * SYNC_GLYPH_SCALE).sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    color = textColor,
                    fontFamily = LocalPiannoliFont.current,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (row.detail != null) {
                    Text(
                        text = row.detail,
                        color = muted,
                        fontFamily = LocalPiannoliFont.current,
                        fontSize = (fontSize.value * SYNC_TIME_SCALE).sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(row.relativeTime, color = muted, fontFamily = LocalPiannoliFont.current, fontSize = (fontSize.value * SYNC_TIME_SCALE).sp)
        }
    }
}

@Composable
private fun SyncErrorRowItem(err: dev.piannoli.scorza.romm.sync.SyncFailure, isSelected: Boolean, fontSize: TextUnit, lineHeight: TextUnit, verticalPadding: Dp) {
    val colors = LocalPiannoliColors.current
    val textColor = if (isSelected) colors.highlightText else colors.text
    val muted = textColor.copy(alpha = 0.55f)
    dev.piannoli.ui.components.PillRow(
        isSelected = isSelected,
        verticalPadding = verticalPadding,
        lineHeight = lineHeight,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = err.displayName,
                color = textColor,
                fontFamily = LocalPiannoliFont.current,
                fontSize = fontSize,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = err.reason,
                color = muted,
                fontFamily = LocalPiannoliFont.current,
                fontSize = (fontSize.value * SYNC_TIME_SCALE).sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DownloadRow(item: RommDownloadItem, isSelected: Boolean, fontSize: TextUnit, lineHeight: TextUnit, verticalPadding: Dp) {
    val colors = LocalPiannoliColors.current
    val font = LocalPiannoliFont.current
    val text = if (isSelected) colors.highlightText else colors.text
    val muted = text.copy(alpha = 0.55f)
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = if (item.kind == RommDownloadKind.MANUAL)
        "${item.displayName}  ·  ${stringResource(R.string.romm_download_manual)}"
    else item.displayName
    PillRowInfo(
        label = label,
        isSelected = isSelected,
        fontSize = fontSize,
        lineHeight = lineHeight,
        verticalPadding = verticalPadding
    ) {
        if (item.status == DownloadStatus.Done) {
            Text(PiannoliIcons.CheckCircle.glyph, color = text, fontFamily = LocalPiannoliIconFont.current, fontSize = (fontSize.value * 0.9f).sp, lineHeight = lineHeight)
        } else {
            val right = when (val s = item.status) {
                is DownloadStatus.Downloading ->
                    if (s.total > 0) stringResource(R.string.download_percent, (s.downloaded * 100 / s.total).coerceAtMost(100))
                    else if (s.downloaded > 0) android.text.format.Formatter.formatShortFileSize(context, s.downloaded)
                    else stringResource(R.string.romm_download_downloading)
                DownloadStatus.Queued -> stringResource(R.string.romm_download_queued)
                is DownloadStatus.Failed -> stringResource(R.string.romm_download_failed)
                else -> ""
            }
            Text(right, color = muted, fontFamily = font, fontSize = (fontSize.value * 0.8f).sp)
        }
    }
}

@Composable
private fun quickMenuLabel(row: dev.piannoli.scorza.ui.quickmenu.QuickMenuRow): String = when (row) {
    dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.ROMM -> stringResource(R.string.quick_menu_romm)
    dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.DOWNLOADS -> stringResource(R.string.quick_menu_download_queue)
    dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.SYNC_HISTORY -> stringResource(R.string.quick_menu_sync_history)
    dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.CONFLICTS -> stringResource(dev.piannoli.ui.R.string.conflicts_title)
    dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.ERRORS -> stringResource(dev.piannoli.ui.R.string.sync_errors_title)
    dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.SCRAPING -> stringResource(R.string.bulk_all)
    dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.SCRAPE_SYSTEMS -> stringResource(R.string.bulk_systems)
    dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.RESCAN -> stringResource(R.string.quick_menu_rescan)
    dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.INFO -> stringResource(R.string.quick_menu_info)
}

@Composable
private fun conflictChoiceLabel(choice: ConflictChoice): String = when (choice) {
    ConflictChoice.KEEP_LOCAL -> stringResource(dev.piannoli.ui.R.string.conflict_keep_local)
    ConflictChoice.USE_SERVER -> stringResource(dev.piannoli.ui.R.string.conflict_use_server)
    ConflictChoice.SKIP -> stringResource(dev.piannoli.ui.R.string.conflict_skip)
}

@Composable
private fun ConflictRowItem(
    row: dev.piannoli.scorza.ui.screens.ConflictRow,
    choiceLabel: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
) {
    val colors = LocalPiannoliColors.current
    val textColor = if (isSelected) colors.highlightText else colors.text
    val local = row.localMillis
    val server = row.serverMillis
    val localOlder = local != null && server != null && local < server
    val serverOlder = local != null && server != null && server < local
    val sub = fontSize * 0.72f
    val highlight = if (isSelected) {
        Modifier.clip(RoundedCornerShape(12.dp)).background(colors.highlight)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(highlight)
            .padding(horizontal = 14.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = textColor,
                fontSize = fontSize,
                lineHeight = lineHeight,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            ConflictTimeLine(stringResource(dev.piannoli.ui.R.string.conflict_yours), local, localOlder, sub, textColor)
            ConflictTimeLine(stringResource(dev.piannoli.ui.R.string.conflict_server), server, serverOlder, sub, textColor)
        }
        Spacer(modifier = Modifier.width(Spacing.Sm))
        Text(text = choiceLabel, color = textColor, fontSize = fontSize, lineHeight = lineHeight, maxLines = 1)
    }
}

@Composable
private fun ConflictTimeLine(
    label: String,
    millis: Long?,
    older: Boolean,
    fontSize: TextUnit,
    color: androidx.compose.ui.graphics.Color,
) {
    val suffix = if (older) "  · " + stringResource(dev.piannoli.ui.R.string.conflict_older) else ""
    Row(modifier = Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = color, fontSize = fontSize, lineHeight = fontSize * 1.1f, maxLines = 1, modifier = Modifier.width(58.dp))
        Text(
            text = formatConflictTime(millis) + suffix,
            color = color,
            fontSize = fontSize,
            lineHeight = fontSize * 1.1f,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private fun formatConflictTime(millis: Long?): String =
    millis?.let {
        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it))
    } ?: "—"

val ROMM_ADVANCED_ROWS = listOf(R.string.romm_settings_rebuild, R.string.romm_settings_download_art)

enum class RommActionRow(@androidx.annotation.StringRes val labelRes: Int) {
    DOWNLOADS(R.string.romm_download_queue),
    ;
    companion object {
        fun visibleRows(hasDownloads: Boolean): List<RommActionRow> =
            if (hasDownloads) entries else entries.filterNot { it == DOWNLOADS }
    }
}

enum class RommSettingsRow(@androidx.annotation.StringRes val labelRes: Int, val isCycle: Boolean = false) {
    COVER_ART(R.string.romm_settings_cover_art, isCycle = true),
    CONCURRENT(R.string.romm_settings_concurrent, isCycle = true),
    SAVE_SYNC(R.string.setting_romm_save_sync),
    PLATFORMS(R.string.romm_settings_platforms),
    COLLECTIONS(R.string.romm_settings_collections),
    ADVANCED(R.string.romm_settings_advanced),
    SERVER_INFO(R.string.romm_settings_server_info),
}

enum class RommSaveSyncRow {
    TOGGLE, BACKUPS, HISTORY, CONFLICTS, ERRORS, RESTORE;
    companion object {
        fun visibleRows(supported: Boolean, enabled: Boolean, pendingConflicts: Int = 0, syncErrors: Int = 0, hasBackups: Boolean = false): List<RommSaveSyncRow> =
            buildList {
                add(TOGGLE)
                if (supported && enabled) {
                    add(BACKUPS)
                    add(HISTORY)
                    if (pendingConflicts > 0) add(CONFLICTS)
                    if (syncErrors > 0) add(ERRORS)
                }
                // Restore is a recovery action, so it stays available even when sync is off.
                if (hasBackups) add(RESTORE)
            }
    }
}

@androidx.annotation.StringRes
fun rommArtLabelRes(artType: RommArtType): Int = when (artType) {
    RommArtType.DEFAULT -> R.string.romm_art_default
    RommArtType.NONE -> R.string.romm_art_off
    RommArtType.BOX2D -> R.string.romm_art_box2d
    RommArtType.BOX3D -> R.string.romm_art_box3d
    RommArtType.MIX -> R.string.romm_art_mix
    RommArtType.TITLE -> R.string.romm_art_title
    RommArtType.SCREENSHOT -> R.string.romm_art_screenshot
    RommArtType.MARQUEE -> R.string.romm_art_marquee
}

@Composable
private fun ConfirmOverlay(
    message: String,
    buttonStyle: ButtonStyle,
    cancelLabel: String? = null,
    confirmLabel: String? = null,
    backgroundImagePath: String? = dev.piannoli.ui.components.LocalScreenBackground.current.imagePath,
    backgroundTint: Int = dev.piannoli.ui.components.LocalScreenBackground.current.tint,
) {
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                color = LocalPiannoliColors.current.text,
                fontFamily = LocalPiannoliFont.current,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            BottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(screenPadding),
                leftItems = listOf(buttonStyle.back to (cancelLabel ?: stringResource(R.string.label_cancel))),
                rightItems = listOf(buttonStyle.confirm to (confirmLabel ?: stringResource(R.string.label_confirm)))
            )
        }
    }
}

@Composable
internal fun ListDialogScreen(
    backgroundImagePath: String?,
    backgroundTint: Int,
    title: String,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    fullWidth: Boolean = true,
    leftBottomItems: List<Pair<String, String>> = emptyList(),
    rightBottomItems: List<Pair<String, String>>,
    buttonStyle: ButtonStyle = ButtonStyle(),
    showBackButton: Boolean = true,
    content: @Composable () -> Unit
) {
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .then(if (fullWidth) Modifier.fillMaxSize() else Modifier.widthIn(max = 560.dp).fillMaxWidth())
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = title,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                content()
            }
            val left = if (showBackButton) listOf(buttonStyle.back to stringResource(R.string.label_back)) + leftBottomItems else leftBottomItems
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = left,
                rightItems = rightBottomItems
            )
        }
    }
}
