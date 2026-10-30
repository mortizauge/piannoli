package dev.piannoli.scorza.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.ui.components.LocalScreenBackground
import dev.piannoli.ui.components.ScreenBackgroundStyle
import dev.piannoli.ui.components.screenBackground
import dev.piannoli.ui.theme.PiannoliColors
import dev.piannoli.ui.theme.LocalPiannoliColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenBackgroundTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun backgroundPixel(): Color = compose.runOnIdle {
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(bitmap))
            Color(bitmap.getPixel(1, 1))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun `overwrite confirmation inherits theme background color`() {
        compose.setContent {
            CompositionLocalProvider(LocalPiannoliColors provides PiannoliColors(background = Color.Red)) {
                DialogOverlay(
                    dialogState = DialogState.ScraperOverwriteConfirm,
                    backgroundImagePath = null,
                    backgroundTint = 0,
                    listFontSize = 16.sp,
                    listLineHeight = 24.sp,
                    listVerticalPadding = 4.dp,
                )
            }
        }
        assertEquals(Color.Red, backgroundPixel())
    }

    @Test fun `shared screen background inherits image and tint instead of solid color`() {
        val image = temporaryFolder.newFile("background.png")
        val bitmap = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0..1) for (y in 0..1) bitmap.setRGB(x, y, 0xFFFFFF)
        ImageIO.write(bitmap, "png", image)
        compose.setContent {
            CompositionLocalProvider(
                LocalPiannoliColors provides PiannoliColors(background = Color.Red),
                LocalScreenBackground provides ScreenBackgroundStyle(image.absolutePath, 50),
            ) {
                Box(Modifier.fillMaxSize().screenBackground())
            }
        }
        compose.waitUntil(5_000) {
            val pixel = backgroundPixel()
            pixel.red in 0.49f..0.51f && pixel.green in 0.49f..0.51f && pixel.blue in 0.49f..0.51f
        }
    }
}
