package dev.piannoli.scorza.libretro

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.piannoli.igm.BatteryDisplayMode
import dev.piannoli.igm.PiannoliIGM
import dev.piannoli.igm.GuideFile
import dev.piannoli.igm.IGMHostConfig
import dev.piannoli.igm.IGMScreen
import dev.piannoli.igm.IGMSettingsItem
import dev.piannoli.igm.IgmGameInfo
import dev.piannoli.igm.InGameMenuOptions
import dev.piannoli.igm.PlayerSlotInfo
import dev.piannoli.igm.TimeFormatMode
import dev.piannoli.scorza.input.runtime.confirmButton
import dev.piannoli.scorza.input.runtime.labelSet
import dev.piannoli.ui.components.OsdHost
import dev.piannoli.ui.components.pillLineHeightSp
import dev.piannoli.ui.components.pillScaleFor
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.ui.theme.Radius
import kotlinx.coroutines.delay

data class GameInfo(
    val coreName: String,
    val romPath: String,
    val savePath: String?,
    val rootPrefix: String = "",
    val originalRomPath: String? = null,
    val rendererName: String = "",
    val raStatus: String? = null,
    val raGameId: String? = null,
    val raDetection: String? = null
)

@Composable
fun LibretroScreen(
    glSurfaceView: android.view.View,
    gameTitle: String,
    screen: IGMScreen?,
    menuOptions: InGameMenuOptions,
    selectedSlot: Slot,
    slotThumbnail: Bitmap?,
    slotExists: Boolean,
    slotOccupied: List<Boolean>,
    undoLabel: String?,
    settingsItems: List<IGMSettingsItem>,
    coreInfo: String,
    debugHud: Boolean,
    showFps: Boolean,
    renderer: LibretroRenderer,
    runner: LibretroRunner,
    audioSampleRate: Int,
    osdController: dev.piannoli.ui.components.OsdController,
    fastForwarding: Boolean,
    settings: dev.piannoli.scorza.settings.SettingsRepository,
    guideFiles: List<GuideFile> = emptyList(),
    cheatSections: List<dev.piannoli.ui.components.ListSection<dev.piannoli.igm.CheatRowUi>> = emptyList(),
    cheatHasRemembered: Boolean = false,
    guidePageCount: Int = 0,
    guideScrollDir: Int = 0,
    guideScrollXDir: Int = 0,
    guidePageJump: Int = 0,
    guidePageJumpDir: Int = 0,
    guideInitialScroll: Int = 0,
    guideInitialScrollX: Int = 0,
    onGuideScrollChanged: (y: Int, x: Int) -> Unit = { _, _ -> },
    infoScrollDir: Int = 0,
    gameInfo: GameInfo = GameInfo("", "", null),
    activeMapping: dev.piannoli.scorza.input.DeviceMapping? = null,
    controllersViewModel: dev.piannoli.scorza.ui.viewmodel.ControllersViewModel? = null,
    inputRemapHasChanges: Boolean = false,
) {
    val overlayVisible = screen != null

    val batteryDisplay = when (settings.batteryDisplay) {
        dev.piannoli.scorza.settings.BatteryDisplay.HIDE -> BatteryDisplayMode.HIDE
        dev.piannoli.scorza.settings.BatteryDisplay.PERCENT -> BatteryDisplayMode.PERCENT
        dev.piannoli.scorza.settings.BatteryDisplay.ICON -> BatteryDisplayMode.ICON
    }
    val timeFormat = when (settings.timeFormat) {
        dev.piannoli.scorza.settings.TimeFormat.TWELVE_HOUR -> TimeFormatMode.TWELVE_HOUR
        dev.piannoli.scorza.settings.TimeFormat.TWENTY_FOUR_HOUR -> TimeFormatMode.TWENTY_FOUR_HOUR
    }

    val labelContext = androidx.compose.ui.platform.LocalContext.current

    val config = IGMHostConfig(
        fontSizeSp = settings.textSize.sp,
        lineHeightSp = pillLineHeightSp(settings.textSize.sp),
        pillScale = pillScaleFor(settings.textSize.sp),
        scaleFactor = settings.textSize.sp / 22f,
        portraitMarginPx = settings.portraitMarginPx,
        geometryWidthPct = settings.screenGeometryWidth,
        geometryHeightPct = settings.screenGeometryHeight,
        geometryXPct = settings.screenGeometryX,
        geometryYPct = settings.screenGeometryY,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        batteryDisplay = batteryDisplay,
        timeFormat = timeFormat,
        buttonLabelSet = activeMapping.labelSet(dev.piannoli.ui.ButtonLabelSet.PLUMBER),
        confirmButton = activeMapping.confirmButton(),
        keyCodeName = { keyCode ->
            dev.piannoli.scorza.util.buttonLabel(
                labelContext,
                keyCode,
                activeMapping,
                activeMapping?.glyphStyle ?: dev.piannoli.scorza.input.GlyphStyle.PLUMBER,
            )
        },
    )

    val igmGameInfo = IgmGameInfo(
        coreName = gameInfo.coreName,
        romPath = gameInfo.originalRomPath ?: gameInfo.romPath,
        extractedPath = if (gameInfo.originalRomPath != null) gameInfo.romPath else "",
        savePath = gameInfo.savePath ?: "",
        rendererName = gameInfo.rendererName,
        raStatus = gameInfo.raStatus ?: "",
        raGameId = gameInfo.raGameId ?: "",
        raDetection = gameInfo.raDetection ?: "",
        rootPrefix = gameInfo.rootPrefix,
    )

    val controllersState = controllersViewModel?.state?.collectAsState()?.value
    val players = (controllersState?.connected.orEmpty())
        .map { PlayerSlotInfo(port = it.port ?: -1, displayName = it.mapping.displayName) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        PiannoliIGM(
            screen = screen,
            config = config,
            gameTitle = gameTitle,
            menuOptions = menuOptions,
            selectedSlot = selectedSlot,
            slotThumbnail = slotThumbnail,
            slotExists = slotExists,
            slotOccupied = slotOccupied,
            undoLabel = undoLabel,
            settingsItems = settingsItems,
            coreInfo = coreInfo,
            gameInfo = igmGameInfo,
            infoScrollDir = infoScrollDir,
            guideFiles = guideFiles,
            cheatSections = cheatSections,
            cheatHasRemembered = cheatHasRemembered,
            guidePageCount = guidePageCount,
            guideScrollDir = guideScrollDir,
            guideScrollXDir = guideScrollXDir,
            guidePageJump = guidePageJump,
            guidePageJumpDir = guidePageJumpDir,
            guideInitialScroll = guideInitialScroll,
            guideInitialScrollX = guideInitialScrollX,
            onGuideScrollChanged = onGuideScrollChanged,
            players = players,
            inputRemapHasChanges = inputRemapHasChanges,
        )

        val osdRequest = osdController.request.value
        if (debugHud && !overlayVisible && osdRequest == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                DebugHud(
                    renderer = renderer,
                    runner = runner,
                    coreName = coreInfo,
                    audioSampleRate = audioSampleRate
                )
            }
        }

        if ((showFps || fastForwarding) && !overlayVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
            ) {
                StatusPill(showFps = showFps, fastForwarding = fastForwarding, renderer = renderer)
            }
        }

        val osdConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
        val osdDensity = androidx.compose.ui.platform.LocalDensity.current
        val osdPortrait = osdConfiguration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val osdRect = dev.piannoli.ui.computeScreenGeometryRect(
            osdConfiguration.screenWidthDp, osdConfiguration.screenHeightDp,
            settings.screenGeometryWidth, settings.screenGeometryHeight, settings.screenGeometryX, settings.screenGeometryY,
        )
        val osdBottomMarginPx = if (osdPortrait) settings.portraitMarginPx else 0
        val osdPadding = with(osdDensity) {
            androidx.compose.foundation.layout.PaddingValues(
                start = osdRect.x.dp,
                top = osdRect.y.dp,
                end = (osdConfiguration.screenWidthDp - osdRect.x - osdRect.w).coerceAtLeast(0).dp,
                bottom = (osdConfiguration.screenHeightDp - osdRect.y - osdRect.h).coerceAtLeast(0).dp + osdBottomMarginPx.toDp(),
            )
        }
        Box(modifier = Modifier.fillMaxSize().padding(osdPadding)) {
            OsdHost(controller = osdController)
        }
    }
}

@Composable
private fun StatusPill(showFps: Boolean, fastForwarding: Boolean, renderer: LibretroRenderer) {
    val colors = LocalPiannoliColors.current
    val fpsValue = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(showFps) {
        while (showFps) {
            fpsValue.floatValue = renderer.fps
            delay(500)
        }
    }
    val text = buildString {
        if (fastForwarding) append("▶▶")
        if (fastForwarding && showFps) append("  ")
        if (showFps) append("%.2f".format(fpsValue.floatValue))
    }
    Box(
        modifier = Modifier
            .clip(Radius.Pill)
            .background(colors.highlight)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = colors.highlightText
        )
    }
}
