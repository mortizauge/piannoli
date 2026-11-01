package dev.piannoli.scorza.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.piannoli.scorza.R
import dev.piannoli.scorza.settings.ArtScale
import dev.piannoli.ui.components.LocalUiActive
import dev.piannoli.ui.components.StatusBar
import dev.piannoli.ui.theme.LocalScaleFactor
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp")
class ArtworkStatusBarInsetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `measured status height shrinks only artwork and recalculates custom and fallback caps`() {
        val height = mutableStateOf(0.dp)
        val barSize = mutableStateOf(0.dp)
        val visible = mutableStateOf(true)
        val fallback = mutableStateOf(false)
        val system = mutableStateOf(false)
        val bitmap = Bitmap.createBitmap(256, 512, Bitmap.Config.ARGB_8888).asImageBitmap()
        compose.setContent {
            CompositionLocalProvider(LocalArtworkStatusBarHeight provides height) {
                Box(Modifier.requiredSize(620.dp, 440.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(50.dp).testTag("title"))
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(0.6f).testTag("list"))
                            Box(Modifier.fillMaxSize().artworkStatusBarPadding().testTag("slot")) {
                                if (system.value) {
                                    SystemArtworkPanel(
                                        painter = if (fallback.value) painterResource(R.drawable.category_apps)
                                            else BitmapPainter(bitmap),
                                        contentDescription = "art",
                                        scale = ArtScale.FIT,
                                        radius = 0,
                                        isFallback = fallback.value,
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize().testTag("game"))
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(70.dp).testTag("footer"))
                    }
                    if (visible.value) {
                        MeasuredArtworkStatusBar(
                            height,
                            Modifier.align(Alignment.TopEnd).padding(top = 10.dp, bottom = 12.dp),
                        ) {
                            Box(Modifier.fillMaxWidth(0.2f).height(barSize.value).testTag("bar"))
                        }
                    }
                }
            }
        }
        val list = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("title").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("footer").getUnclippedBoundsInRoot()
        for (isSystem in listOf(false, true)) {
            for (isFallback in listOf(false, true)) {
                compose.runOnIdle { system.value = isSystem; fallback.value = isFallback; barSize.value = 0.dp }
                val before = if (isSystem) compose.onNodeWithContentDescription("art").getUnclippedBoundsInRoot() else null
                for (size in listOf(36.dp, 64.dp, 0.dp)) {
                    compose.runOnIdle { barSize.value = size }
                    val slot = compose.onNodeWithTag("slot").getUnclippedBoundsInRoot()
                    assertEquals(size, height.value)
                    assertEquals(title.bottom + size, slot.top)
                    assertEquals(footer.top, slot.bottom)
                    assertEquals(list, compose.onNodeWithTag("list").getUnclippedBoundsInRoot())
                    assertEquals(title, compose.onNodeWithTag("title").getUnclippedBoundsInRoot())
                    assertEquals(footer, compose.onNodeWithTag("footer").getUnclippedBoundsInRoot())
                    if (isSystem) {
                        val art = compose.onNodeWithContentDescription("art").getUnclippedBoundsInRoot()
                        val remainingFraction = (320.dp - size) / 320.dp
                        assertEquals((before!!.bottom - before.top).value * remainingFraction, (art.bottom - art.top).value, 1f)
                        assertEquals(((slot.top + slot.bottom) / 2).value, ((art.top + art.bottom) / 2).value, 1f)
                    } else {
                        assertEquals(slot, compose.onNodeWithTag("game").getUnclippedBoundsInRoot())
                    }
                }
            }
        }
        compose.runOnIdle { barSize.value = 64.dp }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertEquals(0.dp, height.value) }
        assertEquals(title.bottom, compose.onNodeWithTag("slot").getUnclippedBoundsInRoot().top)
    }

    @Test fun `actual status bar responds to font scaling and empty indicators reserve no height`() {
        val height = mutableStateOf(0.dp)
        val clock = mutableStateOf(true)
        val textSize = mutableStateOf(16)
        val scale = mutableStateOf(1f)
        val inactive = MutableStateFlow(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalArtworkStatusBarHeight provides height,
                LocalUiActive provides inactive,
                LocalScaleFactor provides scale.value,
            ) {
                Box(Modifier.requiredSize(620.dp, 440.dp)) {
                    Box(Modifier.fillMaxSize().artworkStatusBarPadding().testTag("slot"))
                    MeasuredArtworkStatusBar(height, Modifier.align(Alignment.TopEnd)) {
                        Box(Modifier.testTag("bar")) {
                            StatusBar(
                                showWifi = false, showBluetooth = false, showVpn = false,
                                showClock = clock.value, showBattery = false, showUpdate = false,
                                textSizeSp = textSize.value,
                            )
                        }
                    }
                }
            }
        }
        val initial = height.value
        assertTrue(initial > 0.dp)
        compose.runOnIdle { textSize.value = 60; scale.value = 1.5f }
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        assertEquals(bar.bottom - bar.top, height.value)
        assertTrue(height.value > initial)
        assertEquals(height.value, compose.onNodeWithTag("slot").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { clock.value = false }
        compose.runOnIdle { assertEquals(0.dp, height.value) }
        assertEquals(0.dp, compose.onNodeWithTag("slot").getUnclippedBoundsInRoot().top)
    }

    @Test fun `existing artwork title padding is not added to the status reservation twice`() {
        val height = mutableStateOf(36.dp)
        compose.setContent {
            CompositionLocalProvider(LocalArtworkStatusBarHeight provides height) {
                Box(Modifier.requiredSize(620.dp, 440.dp).artworkStatusBarPadding(48.dp)) {
                    Box(Modifier.fillMaxSize().testTag("slot"))
                }
            }
        }
        assertEquals(48.dp, compose.onNodeWithTag("slot").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { height.value = 64.dp }
        assertEquals(64.dp, compose.onNodeWithTag("slot").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { height.value = 0.dp }
        assertEquals(48.dp, compose.onNodeWithTag("slot").getUnclippedBoundsInRoot().top)
    }
}
