package dev.piannoli.scorza.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.piannoli.scorza.ui.components.artworkClip
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.piannoli.ui.components.collectAsStateWhileActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.scorza.R
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.model.ListItem
import dev.piannoli.scorza.settings.ArtScale
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.ConfirmOverlay
import dev.piannoli.ui.components.LaunchErrorDialog
import dev.piannoli.ui.components.List
import dev.piannoli.ui.components.MessageOverlay
import dev.piannoli.ui.components.MissingAppDialog
import dev.piannoli.ui.components.MissingCoreDialog
import dev.piannoli.ui.components.PillRowText
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.ScreenTitle
import dev.piannoli.ui.components.footerReservation
import dev.piannoli.ui.components.listTitleSpacing
import dev.piannoli.ui.components.pillItemHeight
import dev.piannoli.ui.components.screenInsets
import dev.piannoli.ui.theme.LocalPiannoliColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

private fun ListItem.rowDisplayName(): String = when (this) {
    is ListItem.RomItem -> rom.displayName
    is ListItem.AppItem -> app.displayName
    is ListItem.SubfolderItem -> name
    is ListItem.CollectionItem -> collection.displayName
    is ListItem.ChildCollectionItem -> collection.displayName
}

private val ListItem.itemKey: String get() = when (this) {
    is ListItem.RomItem -> "rom:${rom.id}"
    is ListItem.AppItem -> "app:${app.id}"
    is ListItem.SubfolderItem -> "sub:$path"
    is ListItem.CollectionItem -> "coll:${collection.id}"
    is ListItem.ChildCollectionItem -> "child:${collection.id}"
}

private val ListItem.isLeafSelectable: Boolean get() = this is ListItem.RomItem || this is ListItem.AppItem

private val ListItem.artPath: String? get() = when (this) {
    is ListItem.RomItem -> rom.artFile?.absolutePath
    is ListItem.AppItem -> app.artFile?.absolutePath
    is ListItem.SubfolderItem -> artFile?.absolutePath
    else -> null
}

private val ListItem.resumeKey: String? get() = (this as? ListItem.RomItem)?.rom?.path?.absolutePath

@Composable
fun GameListScreen(
    viewModel: GameListViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    resumableGames: Set<String> = emptySet(),
    swapPlayResume: Boolean = false,
    artWidth: Int = 40,
    artRadius: Int = 25,
    artScale: ArtScale = ArtScale.FIT,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val state by viewModel.state.collectAsStateWhileActive()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.scrollTarget.coerceAtLeast(0))

    DisposableEffect(Unit) {
        onDispose { viewModel.savePosition(listState.firstVisibleItemIndex) }
    }
    androidx.compose.runtime.LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { viewModel.firstVisibleIndex = it }
    }

    val selected = state.items.getOrNull(state.selectedIndex)
    val resumeKey = selected?.resumeKey
    val hasResumeState = resumeKey != null && resumableGames.contains(resumeKey)

    val artPath = selected?.artPath
    val uiActive = dev.piannoli.ui.components.LocalUiActive.current
    val selectedArt by produceState<ImageBitmap?>(null, artPath, state.artworkRevision, uiActive) {
        var loaded = false
        uiActive.collectLatest { focused ->
            if (!focused || loaded) return@collectLatest
            value = if (artPath != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val opts = BitmapFactory.Options()
                        opts.inJustDecodeBounds = true
                        BitmapFactory.decodeFile(artPath, opts)
                        val maxDim = 1024
                        var sampleSize = 1
                        while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) sampleSize *= 2
                        opts.inJustDecodeBounds = false
                        opts.inSampleSize = sampleSize
                        BitmapFactory.decodeFile(artPath, opts)?.asImageBitmap()
                    } catch (_: Exception) { null }
                }
            } else null
            loaded = true
        }
    }

    val inFavoritesCollection = state.isCollection && state.isFavorites
    val showFavoriteStars = viewModel.showFavoriteStars && !inFavoritesCollection
    val favoriteRomIds = state.favoriteRomIds
    val favoriteAppIds = state.favoriteAppIds
    val showPlatformInSuffix = state.isCollection || state.platformTag == "recently_played"
    val romTagSuffixById = remember(state.items, showPlatformInSuffix) {
        val result = HashMap<Long, String>()
        val byName = state.items
            .filterIsInstance<ListItem.RomItem>()
            .groupBy { it.rom.displayName }
        for ((_, group) in byName) {
            if (group.size < 2) continue
            if (showPlatformInSuffix) {
                val byPlatform = group.groupBy { it.rom.platformTag }
                for ((platform, pgroup) in byPlatform) {
                    val platformPart = "(${platform.uppercase()})"
                    if (pgroup.size == 1) {
                        result[pgroup[0].rom.id] = platformPart
                    } else {
                        for (ri in pgroup) {
                            result[ri.rom.id] = listOfNotNull(platformPart, ri.rom.tags).joinToString(" ")
                        }
                    }
                }
            } else {
                for (ri in group) {
                    ri.rom.tags?.let { result[ri.rom.id] = it }
                }
            }
        }
        result
    }
    val toolsLabel = stringResource(R.string.origin_tools)
    val portsLabel = stringResource(R.string.origin_ports)
    val collectionLabel = stringResource(R.string.origin_collection)

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenInsets())
        ) {
            val showArt = selectedArt != null && artWidth > 0
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = state.searchTerm?.let { "${state.breadcrumb}: “$it”" } ?: state.breadcrumb,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                if (state.items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.empty_list, state.breadcrumb),
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
                                .then(if (showArt) Modifier.fillMaxWidth(1f - artWidth / 100f) else Modifier.fillMaxWidth())
                        ) {
                            List(
                                items = state.items,
                                selectedIndex = state.selectedIndex,
                                itemHeight = itemHeight,
                                scrollTarget = state.scrollTarget,
                                listState = listState,
                                reorderMode = state.reorderMode,
                                onListStateChanged = onListStateChanged,
                                key = if (state.reorderMode) null else { _, item -> item.itemKey }
                            ) { index, item, isSelected ->
                                val starred = showFavoriteStars && when (item) {
                                    is ListItem.RomItem -> item.rom.id in favoriteRomIds
                                    is ListItem.AppItem -> item.app.id in favoriteAppIds
                                    else -> false
                                }
                                val tagSuffix = if (state.isGlobalSearch) {
                                    dev.piannoli.scorza.ui.viewmodel.globalOriginTag(item, toolsLabel, portsLabel, collectionLabel)
                                } else {
                                    (item as? ListItem.RomItem)?.let { romTagSuffixById[it.rom.id] }
                                }
                                PillRowText(
                                    label = item.rowDisplayName(),
                                    showFavoriteIcon = starred,
                                    showFolderIcon = item is ListItem.SubfolderItem || item is ListItem.ChildCollectionItem,
                                    showPinIcon = (item as? ListItem.AppItem)?.app?.isPinned == true,
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding,
                                    showReorderIcon = state.reorderMode && isSelected,
                                    checkState = if (state.multiSelectMode && item.isLeafSelectable) index in state.checkedIndices else null,
                                    tagSuffix = tagSuffix,
                                )
                            }
                        }
                        if (showArt) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth()
                                    .padding(start = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val art = selectedArt ?: return@Box
                                val artModifier: Modifier
                                val artContentScale: ContentScale
                                when (artScale) {
                                    ArtScale.FIT -> { artModifier = Modifier.fillMaxSize(); artContentScale = ContentScale.Fit }
                                    ArtScale.ORIGINAL -> { artModifier = Modifier.wrapContentSize(); artContentScale = ContentScale.None }
                                    ArtScale.FIT_WIDTH -> { artModifier = Modifier.fillMaxWidth(); artContentScale = ContentScale.FillWidth }
                                    ArtScale.FIT_HEIGHT -> { artModifier = Modifier.fillMaxHeight(); artContentScale = ContentScale.FillHeight }
                                }
                                Image(
                                    bitmap = art,
                                    contentDescription = null,
                                    modifier = artModifier.artworkClip(artRadius, Size(art.width.toFloat(), art.height.toFloat()), artContentScale),
                                    contentScale = artContentScale,
                                    filterQuality = FilterQuality.High
                                )
                            }
                        }
                    }
                }
            }

            val isToolApp = (selected as? ListItem.AppItem)?.app?.type == AppType.TOOL
            val actionLabel = if (state.multiSelectMode) {
                stringResource(R.string.label_toggle)
            } else if (selected is ListItem.SubfolderItem || state.isCollectionsList) {
                stringResource(R.string.label_open)
            } else if (isToolApp) {
                stringResource(R.string.label_launch)
            } else {
                stringResource(R.string.label_play)
            }
            val resumeLabel = stringResource(R.string.label_resume)
            val showNewButton = state.isCollectionsList || (state.isCollection && !state.isFavorites)
            val leftItems = buildList {
                add(buttonStyle.back to stringResource(R.string.label_back))
                if (showNewButton) add(buttonStyle.west to stringResource(R.string.label_new))
            }
            val rightItems = if (state.items.isEmpty()) {
                emptyList()
            } else if (state.multiSelectMode) {
                listOf(buttonStyle.confirm to actionLabel, buttonStyle.start to stringResource(R.string.label_confirm))
            } else if (hasResumeState && swapPlayResume) {
                listOf(buttonStyle.north to actionLabel, buttonStyle.confirm to resumeLabel)
            } else {
                buildList {
                    if (hasResumeState) add(buttonStyle.north to resumeLabel)
                    add(buttonStyle.confirm to actionLabel)
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = leftItems,
                rightItems = rightItems
            )

        }
    }
}
