package dev.cannoli.scorza.ui.screens

import android.graphics.BitmapFactory
import android.util.LruCache
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
import dev.cannoli.scorza.ui.components.artworkClip
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.cannoli.ui.components.collectAsStateWhileActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.settings.ArtScale
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel.ListItem
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ConfirmOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.WithoutScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.listTitleSpacing
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

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
    systemArtworkType: dev.cannoli.scorza.settings.SystemArtworkType = dev.cannoli.scorza.settings.SystemArtworkType.LOGO,
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
    val artworkRevision by dev.cannoli.scorza.scraper.SystemArtwork.revision.collectAsStateWhileActive()
    val artworkCache = remember(artworkRevision) {
        object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
        }
    }
    val artPath = when (selectedItem) {
        is ListItem.GameItem -> selectedItem.artFile?.absolutePath
        else -> selectedItem?.artworkTag?.let { viewModel.systemArtworkFile(it, systemArtworkType)?.absolutePath }
    }
    val uiActive = dev.cannoli.ui.components.LocalUiActive.current
    val selectedArt by key(artPath, artworkRevision) {
        produceState<ImageBitmap?>(artPath?.let { artworkCache.get(it) }, uiActive) {
            var loaded = false
            uiActive.collectLatest { focused ->
                if (!focused || loaded) return@collectLatest
                value = if (artPath != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            artworkCache.get(artPath)?.let { return@withContext it }
                            val opts = BitmapFactory.Options()
                            opts.inJustDecodeBounds = true
                            BitmapFactory.decodeFile(artPath, opts)
                            val maxDim = 1024
                            var sampleSize = 1
                            while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) sampleSize *= 2
                            opts.inJustDecodeBounds = false
                            opts.inSampleSize = sampleSize
                            BitmapFactory.decodeFile(artPath, opts)?.asImageBitmap()?.also { artworkCache.put(artPath, it) }
                        } catch (_: Exception) { null }
                    }
                } else null
                loaded = true
            }
        }
    }
    val showArt = selectedArtWidth > 0 && (hasPlatforms || state.hasGameItems || selectedArt != null)
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
                        color = LocalCannoliColors.current.text
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
                            val platformTag = (gi.item as? dev.cannoli.scorza.model.ListItem.RomItem)?.rom?.platformTag
                            val platformPart = platformTag?.let { "(${it.uppercase()})" }
                            listOfNotNull(platformPart, gi.tags).joinToString(" ").ifEmpty { null }
                        }
                    PillRowText(
                        label = label,
                        showPinIcon = ((item as? ListItem.GameItem)?.item as? dev.cannoli.scorza.model.ListItem.AppItem)
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
                            .padding(start = 16.dp, top = artTopPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        val art = selectedArt ?: return@Box
                        val artModifier: Modifier
                        val artContentScale: ContentScale
                        when (selectedArtScale) {
                            ArtScale.FIT -> { artModifier = Modifier.fillMaxSize(); artContentScale = ContentScale.Fit }
                            ArtScale.ORIGINAL -> { artModifier = Modifier.wrapContentSize(); artContentScale = ContentScale.None }
                            ArtScale.FIT_WIDTH -> { artModifier = Modifier.fillMaxWidth(); artContentScale = ContentScale.FillWidth }
                            ArtScale.FIT_HEIGHT -> { artModifier = Modifier.fillMaxHeight(); artContentScale = ContentScale.FillHeight }
                        }
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
                listOf(buttonStyle.west to stringResource(R.string.label_kitchen))
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
