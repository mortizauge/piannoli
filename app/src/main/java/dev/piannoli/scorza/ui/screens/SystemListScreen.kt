package dev.piannoli.scorza.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.piannoli.scorza.ui.components.artworkClip
import dev.piannoli.scorza.ui.components.artworkStatusBarPadding
import dev.piannoli.scorza.ui.components.rememberSelectedArtwork
import dev.piannoli.scorza.ui.components.SystemArtworkPanel
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.piannoli.ui.components.collectAsStateWhileActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.scorza.R
import dev.piannoli.scorza.settings.ArtScale
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel.ListItem
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.ConfirmOverlay
import dev.piannoli.ui.components.List
import dev.piannoli.ui.components.PillRowText
import dev.piannoli.ui.components.ScreenBackground
import dev.piannoli.ui.components.ScreenTitle
import dev.piannoli.ui.components.WithoutScreenTitle
import dev.piannoli.ui.components.footerReservation
import dev.piannoli.ui.components.listTitleSpacing
import dev.piannoli.ui.components.pillItemHeight
import dev.piannoli.ui.components.screenInsets
import dev.piannoli.ui.theme.LocalPiannoliColors

@Composable
fun SystemListScreen(
    viewModel: SystemListViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    title: String = "",
    mainMenuQuit: Boolean = false,
    artWidth: Int = 40,
    artRadius: Int = 25,
    artScale: ArtScale = ArtScale.FIT,
    systemArtScale: ArtScale = ArtScale.FIT,
    systemArtWidth: Int = 40,
    systemArtRadius: Int = 0,
    systemArtworkType: dev.piannoli.scorza.settings.SystemArtworkType = dev.piannoli.scorza.settings.SystemArtworkType.LOGO,
    resumableGames: Set<String> = emptySet(),
    swapPlayResume: Boolean = false,
    fiveGameHandheld: Boolean = false,
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

    val recentlyPlayedLabel = stringResource(R.string.label_recently_played)
    val favoritesLabel = stringResource(R.string.label_favorites)
    val collectionsLabel = stringResource(R.string.label_collections)

    val selectedItem = state.items.getOrNull(state.selectedIndex)
    val hasPlatforms = remember(state.items) {
        state.items.any { it is ListItem.PlatformItem || it is ListItem.PortsFolder }
    }
    val isSystemArtwork = selectedItem != null && selectedItem !is ListItem.GameItem
    val selectedArtWidth = if (isSystemArtwork) systemArtWidth else artWidth
    val selectedArtScale = if (isSystemArtwork) systemArtScale else artScale
    val selectedArtRadius = if (isSystemArtwork) systemArtRadius else artRadius
    val artworkRevision by dev.piannoli.scorza.scraper.SystemArtwork.revision.collectAsStateWhileActive()
    val defaultCategoryArtwork = if (selectedArtWidth <= 0) null else when (selectedItem) {
        ListItem.FavoritesItem -> R.drawable.category_favorites
        ListItem.RecentlyPlayedItem -> R.drawable.category_recently_played
        is ListItem.ToolsFolder -> R.drawable.category_apps
        else -> null
    }
    val artPath = if (selectedArtWidth <= 0) null else when (selectedItem) {
        is ListItem.GameItem -> selectedItem.artFile?.absolutePath
        else -> selectedItem?.artworkTag?.let {
            viewModel.systemArtworkFile(it, systemArtworkType)
                ?.takeIf { file -> defaultCategoryArtwork == null || file.isFile }?.absolutePath
        }
    }
    // An assigned image keeps priority even while decoding or if corrupt; only absent files use defaults.
    val categoryArtwork = defaultCategoryArtwork.takeIf { artPath == null }
    val selectedArt by rememberSelectedArtwork(
        artPath, artworkRevision, selectedArtWidth > 0,
        if (isSystemArtwork) dev.piannoli.scorza.ui.components.ArtworkKind.SYSTEM
        else dev.piannoli.scorza.ui.components.ArtworkKind.GAME,
    )
    val showArt = selectedArtWidth > 0 &&
        (hasPlatforms || state.hasGameItems || artPath != null || categoryArtwork != null)
    val duplicateGameNames = remember(state.items) {
        state.items.asSequence()
            .filterIsInstance<ListItem.GameItem>()
            .groupingBy { it.displayName }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(288.dp)
                )
            }
        } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenInsets())
        ) {
            WithoutScreenTitle(active = title.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
            if (title.isNotEmpty()) {
                ScreenTitle(
                    text = title,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
            }
            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(if (fiveGameHandheld) R.string.empty_fgh_collection else R.string.empty_content),
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
                        .then(if (showArt) Modifier.fillMaxWidth(1f - selectedArtWidth / 100f) else Modifier.fillMaxWidth())
                ) {
                List(
                    items = state.items,
                    selectedIndex = state.selectedIndex,
                    itemHeight = itemHeight,
                    scrollTarget = state.scrollTarget,
                    listState = listState,
                    reorderMode = state.reorderMode,
                    onListStateChanged = onListStateChanged,
                    key = if (state.reorderMode) null else { _, item ->
                        when (item) {
                            is ListItem.RecentlyPlayedItem -> "recently_played"
                            is ListItem.FavoritesItem -> "favorites"
                            is ListItem.CollectionsFolder -> "collections"
                            is ListItem.PlatformItem -> item.platform.tag
                            is ListItem.CollectionItem -> "col:${item.id}"
                            is ListItem.GameItem -> "game:${item.recentKey}"
                            is ListItem.ToolsFolder -> "tools"
                            is ListItem.PortsFolder -> "ports"
                        }
                    }
                ) { _, item, isSelected ->
                    val label = when (item) {
                        is ListItem.RecentlyPlayedItem -> recentlyPlayedLabel
                        is ListItem.FavoritesItem -> favoritesLabel
                        is ListItem.CollectionsFolder -> collectionsLabel
                        is ListItem.PlatformItem -> item.platform.displayName
                        is ListItem.CollectionItem -> item.name
                        is ListItem.GameItem -> item.displayName
                        is ListItem.ToolsFolder -> item.name
                        is ListItem.PortsFolder -> item.name
                    }
                    val showReorder = state.reorderMode && isSelected && (item is ListItem.PlatformItem || item is ListItem.ToolsFolder || item is ListItem.PortsFolder || item is ListItem.CollectionItem || item is ListItem.GameItem)
                    val tagSuffix = (item as? ListItem.GameItem)
                        ?.takeIf { it.displayName in duplicateGameNames }
                        ?.let { gi ->
                            val platformTag = (gi.item as? dev.piannoli.scorza.model.ListItem.RomItem)?.rom?.platformTag
                            val platformPart = platformTag?.let { "(${it.uppercase()})" }
                            listOfNotNull(platformPart, gi.tags).joinToString(" ").ifEmpty { null }
                        }
                    PillRowText(
                        label = label,
                        showPinIcon = ((item as? ListItem.GameItem)?.item as? dev.piannoli.scorza.model.ListItem.AppItem)
                            ?.app?.isPinned == true,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        showReorderIcon = showReorder,
                        tagSuffix = tagSuffix,
                    )
                }
                }
                if (showArt) {
                    val artTopPadding = if (fiveGameHandheld && title.isEmpty()) {
                        pillItemHeight(listLineHeight, 4.dp) + 8.dp
                    } else {
                        0.dp
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .artworkStatusBarPadding(artTopPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        val art = selectedArt
                        val categoryPainter = categoryArtwork?.let { painterResource(it) }
                        if (art == null && categoryPainter == null) return@Box
                        if (isSystemArtwork) {
                            val painter = art?.let { BitmapPainter(it, filterQuality = FilterQuality.High) }
                                ?: categoryPainter ?: return@Box
                            val label = if (art == null) {
                                when (selectedItem) {
                                    ListItem.FavoritesItem -> favoritesLabel
                                    ListItem.RecentlyPlayedItem -> recentlyPlayedLabel
                                    is ListItem.ToolsFolder -> selectedItem.name
                                    else -> null
                                }
                            } else {
                                null
                            }
                            SystemArtworkPanel(
                                painter = painter,
                                contentDescription = label,
                                scale = selectedArtScale,
                                radius = selectedArtRadius,
                                colorFilter = if (art == null) ColorFilter.tint(LocalPiannoliColors.current.text) else null,
                                isFallback = art == null,
                            )
                            return@Box
                        }
                        val artModifier: Modifier
                        val artContentScale: ContentScale
                        when (selectedArtScale) {
                            ArtScale.FIT -> { artModifier = Modifier.fillMaxSize(); artContentScale = ContentScale.Fit }
                            ArtScale.ORIGINAL -> { artModifier = Modifier.wrapContentSize(); artContentScale = ContentScale.None }
                            ArtScale.FIT_WIDTH -> { artModifier = Modifier.fillMaxWidth(); artContentScale = ContentScale.FillWidth }
                            ArtScale.FIT_HEIGHT -> { artModifier = Modifier.fillMaxHeight(); artContentScale = ContentScale.FillHeight }
                        }
                        if (art != null) {
                            Image(
                                bitmap = art,
                                contentDescription = null,
                                modifier = artModifier.artworkClip(selectedArtRadius, Size(art.width.toFloat(), art.height.toFloat()), artContentScale),
                                contentScale = artContentScale,
                                filterQuality = FilterQuality.High
                            )
                        }
                    }
                }
            }
            }
            }
            }

            val selectedGameItem = selectedItem as? ListItem.GameItem
            val hasResumeState = selectedGameItem != null && resumableGames.contains(selectedGameItem.recentKey)
            val playLabel = if (selectedGameItem != null) stringResource(R.string.label_play) else stringResource(R.string.label_select)
            val resumeLabel = stringResource(R.string.label_resume)
            val rightItems = if (fiveGameHandheld) {
                if (state.items.isEmpty()) {
                    emptyList()
                } else {
                    buildList {
                        if (hasResumeState && swapPlayResume) {
                            add(buttonStyle.north to playLabel)
                            add(buttonStyle.confirm to resumeLabel)
                        } else {
                            if (hasResumeState) add(buttonStyle.north to resumeLabel)
                            add(buttonStyle.confirm to playLabel)
                        }
                    }
                }
            } else if (state.items.isEmpty()) {
                emptyList()
            } else {
                listOf(buttonStyle.confirm to stringResource(R.string.label_select))
            }
            val leftItems = buildList {
                if (mainMenuQuit) add(buttonStyle.back to stringResource(R.string.label_quit))
                if (fiveGameHandheld) {
                    add(buttonStyle.west to stringResource(R.string.label_settings))
                } else {
                    add(buttonStyle.north to stringResource(R.string.label_settings))
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

}
