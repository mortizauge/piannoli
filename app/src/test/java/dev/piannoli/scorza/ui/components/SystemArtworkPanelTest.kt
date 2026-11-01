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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.piannoli.scorza.R
import dev.piannoli.scorza.settings.ArtScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp")
class SystemArtworkPanelTest {
    @get:Rule val compose = createComposeRule()
    private val portraitBitmap = Bitmap.createBitmap(256, 512, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val DpRect.width get() = right - left
    private val DpRect.height get() = bottom - top
    private val DpRect.centerX get() = (left + right) / 2
    private val DpRect.centerY get() = (top + bottom) / 2

    @Composable
    private fun Screen(
        width: Dp,
        height: Dp,
        artWidth: Int,
        vector: Boolean,
        scale: ArtScale = ArtScale.FIT,
        radius: Int = 0,
        titleHeight: Dp = 50.dp,
        footerHeight: Dp = 70.dp,
    ) {
        Column(Modifier.requiredSize(width, height)) {
            Box(Modifier.fillMaxWidth().height(titleHeight).testTag("title"))
            Row(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp, bottom = 10.dp)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(1f - artWidth / 100f).testTag("list"))
                SystemArtworkPanel(
                    painter = if (vector) painterResource(R.drawable.category_apps) else BitmapPainter(portraitBitmap),
                    contentDescription = "art",
                    scale = scale,
                    radius = radius,
                    modifier = Modifier.fillMaxSize().testTag("panel"),
                    isFallback = vector,
                )
            }
            Box(Modifier.fillMaxWidth().height(footerHeight).testTag("footer"))
        }
    }

    @Test fun `fit caps vectors and bitmaps in landscape and portrait without changing list allocation`() {
        val geometry = mutableStateOf(620.dp to 440.dp)
        val artWidth = mutableStateOf(80)
        val vector = mutableStateOf(true)
        compose.setContent {
            Screen(geometry.value.first, geometry.value.second, artWidth.value, vector.value)
        }
        for (size in listOf(620.dp to 440.dp, 360.dp to 440.dp, 620.dp to 280.dp)) {
            for (width in listOf(40, 80)) {
                for (isVector in listOf(true, false)) {
                    compose.runOnIdle { geometry.value = size; artWidth.value = width; vector.value = isVector }
                    val panel = compose.onNodeWithTag("panel").getUnclippedBoundsInRoot()
                    val list = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
                    val art = compose.onNodeWithContentDescription("art").getUnclippedBoundsInRoot()
                    assertEquals(size.first.value * (1 - width / 100f), list.width.value, 1f)
                    assertEquals(size.first.value * width / 100f, panel.width.value, 1f)
                    assertEquals((size.second - 140.dp).value, panel.height.value, 1f)
                    assertTrue(art.height.value <= panel.height.value * 0.6f + 1f)
                    assertEquals(panel.centerY.value, art.centerY.value, 1f)
                    assertEquals(panel.centerX.value, art.centerX.value, 1f)
                    assertEquals(if (isVector) 1f else 0.5f, art.width / art.height, 0.01f)
                }
            }
        }
    }

    @Test fun `all scales including overflowing fill width and circular crops stay in capped bounds`() {
        val scale = mutableStateOf(ArtScale.FIT_WIDTH)
        val radius = mutableStateOf(100)
        val vector = mutableStateOf(false)
        compose.setContent { Screen(620.dp, 280.dp, 80, vector.value, scale.value, radius.value) }
        for (isVector in listOf(true, false)) {
            for (artScale in ArtScale.entries) {
                for (artRadius in listOf(0, 100)) {
                    compose.runOnIdle { scale.value = artScale; radius.value = artRadius; vector.value = isVector }
                    val panel = compose.onNodeWithTag("panel").getUnclippedBoundsInRoot()
                    val art = compose.onNodeWithContentDescription("art").getUnclippedBoundsInRoot()
                    assertTrue(art.height.value <= panel.height.value * 0.6f + 1f)
                    assertTrue(art.left >= panel.left && art.right <= panel.right)
                    assertEquals(panel.centerY.value, art.centerY.value, 1f)
                    if (isVector) assertEquals(1f, art.width / art.height, 0.01f)
                }
            }
        }
    }

    @Test fun `increasing title and footer reservations reduces actual cap not reserved width`() {
        val title = mutableStateOf(20.dp)
        val footer = mutableStateOf(40.dp)
        compose.setContent {
            Screen(620.dp, 440.dp, 80, true, titleHeight = title.value, footerHeight = footer.value)
        }
        val before = compose.onNodeWithContentDescription("art").getUnclippedBoundsInRoot()
        val listBefore = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        compose.runOnIdle { title.value = 80.dp; footer.value = 100.dp }
        val after = compose.onNodeWithContentDescription("art").getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("panel").getUnclippedBoundsInRoot()
        val listAfter = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        val titleBounds = compose.onNodeWithTag("title").getUnclippedBoundsInRoot()
        val footerBounds = compose.onNodeWithTag("footer").getUnclippedBoundsInRoot()
        assertTrue(after.height < before.height)
        assertEquals(panel.height.value * 0.6f, after.height.value, 1f)
        assertEquals(listBefore.width, listAfter.width)
        assertTrue(after.top > titleBounds.bottom && after.bottom < footerBounds.top)
    }
}
