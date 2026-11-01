package dev.piannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.scorza.ui.components.artworkClip
import dev.piannoli.scorza.ui.components.artworkStatusBarPadding
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import coil.compose.AsyncImage
import dev.piannoli.ui.theme.PiannoliIcons
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.scorza.romm.LocalState
import dev.piannoli.scorza.romm.RommArtUrl
import dev.piannoli.scorza.ui.viewmodel.RommGameRow
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

@Composable
fun RommGameListScreen(
    title: String,
    search: String = "",
    games: List<RommGameRow>,
    loading: Boolean = false,
    selectedIndex: Int,
    scrollTarget: Int,
    host: String,
    artWidth: Int,
    artRadius: Int = 25,
    artType: dev.piannoli.scorza.romm.RommArtType = dev.piannoli.scorza.romm.RommArtType.NONE,
    multiSelect: Boolean = false,
    checkedIds: Set<Int> = emptySet(),
    showFirmware: Boolean = false,
    platformLabelForGame: ((dev.piannoli.scorza.romm.RommGame) -> String?)? = null,
    imageLoader: coil.ImageLoader,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 6.dp,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val showArt = artWidth > 0 && games.isNotEmpty() && artType != dev.piannoli.scorza.romm.RommArtType.NONE

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(screenInsets())) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = if (search.isBlank()) title else "$title: “$search”",
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                if (loading) {
                    // Blank during the load gap so a freshly-entered list never flashes "No results".
                    Box(modifier = Modifier.fillMaxSize()) {}
                } else if (games.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.romm_no_results),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = listFontSize,
                                lineHeight = listLineHeight
                            ),
                            color = LocalPiannoliColors.current.text
                        )
                    }
                } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .then(
                                if (showArt) Modifier.fillMaxWidth(1f - artWidth / 100f)
                                else Modifier.fillMaxWidth()
                            )
                    ) {
                        List(
                            items = games,
                            selectedIndex = selectedIndex.coerceIn(0, (games.size - 1).coerceAtLeast(0)),
                            itemHeight = itemHeight,
                            scrollTarget = scrollTarget,
                            onListStateChanged = onListStateChanged,
                        ) { _, row, isSelected ->
                            val checkable = multiSelect && row.localState == LocalState.REMOTE
                            val folded = row.versionCount > 1
                            val platformLabel = platformLabelForGame?.invoke(row.game)
                            val value = when {
                                folded && platformLabel != null -> "${row.versionCount} · $platformLabel"
                                folded -> row.versionCount.toString()
                                else -> platformLabel ?: ""
                            }
                            PillRowKeyValue(
                                label = row.game.name,
                                value = value,
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                valueIcon = if (folded) PiannoliIcons.Variants.glyph else null,
                                dotIndicator = if (row.anyPresent) true else null,
                                checkState = if (checkable) row.game.id in checkedIds else null,
                            )
                        }
                    }
                    if (showArt) {
                        val focused = games.getOrNull(selectedIndex)
                        val coverUrl = focused?.let { RommArtUrl.forType(host, it.game, artType) }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                                .artworkStatusBarPadding(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverUrl != null) {
                                var artworkSize by remember(coverUrl) { mutableStateOf(Size.Unspecified) }
                                AsyncImage(
                                    model = coverUrl,
                                    contentDescription = null,
                                    imageLoader = imageLoader,
                                    modifier = Modifier.fillMaxSize().artworkClip(artRadius, artworkSize, ContentScale.Fit),
                                    onSuccess = { artworkSize = it.painter.intrinsicSize },
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                }
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = buildList {
                    add(buttonStyle.back to stringResource(if (multiSelect) R.string.label_cancel else R.string.label_back))
                    if (!multiSelect && showFirmware) add(buttonStyle.west to stringResource(R.string.label_firmware))
                },
                rightItems = if (multiSelect) listOf(
                    buttonStyle.confirm to stringResource(R.string.label_toggle),
                    buttonStyle.start to stringResource(R.string.label_download),
                ) else listOf(
                    buttonStyle.confirm to stringResource(R.string.label_select),
                ),
            )
        }
    }
}
