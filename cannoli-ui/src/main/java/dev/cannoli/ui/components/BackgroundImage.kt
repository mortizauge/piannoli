package dev.cannoli.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import dev.cannoli.ui.theme.LocalCannoliColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class BgCacheEntry(val path: String, val lastModified: Long, val bitmap: ImageBitmap)
private val cache = java.util.concurrent.atomic.AtomicReference<BgCacheEntry?>(null)

data class ScreenBackgroundStyle(val imagePath: String? = null, val tint: Int = 0)
val LocalScreenBackground = staticCompositionLocalOf { ScreenBackgroundStyle() }

@Composable
fun ScreenBackground(
    backgroundImagePath: String? = LocalScreenBackground.current.imagePath,
    backgroundTint: Int = LocalScreenBackground.current.tint,
    backgroundAlpha: Float = 1f,
    backgroundColor: Color? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize().screenBackground(
            backgroundImagePath, backgroundTint, backgroundAlpha, backgroundColor
        )
    ) { content() }
}

fun Modifier.screenBackground(
    imagePath: String? = null,
    tint: Int? = null,
    alpha: Float = 1f,
    color: Color? = null,
): Modifier = composed {
    val style = LocalScreenBackground.current
    val backgroundImagePath = imagePath ?: style.imagePath
    val backgroundTint = tint ?: style.tint
    val resolvedColor = color ?: LocalCannoliColors.current.background
    var result = this.background(resolvedColor.copy(alpha = alpha))
    if (backgroundImagePath != null) {
        val lastModified = File(backgroundImagePath).lastModified()
        val cachedInitial = cache.get()?.takeIf {
            it.path == backgroundImagePath && it.lastModified == lastModified
        }?.bitmap
        val bitmap by produceState(
            initialValue = cachedInitial,
            backgroundImagePath, lastModified
        ) {
            value = withContext(Dispatchers.IO) {
                try {
                    val file = File(backgroundImagePath)
                    if (!file.exists()) return@withContext null
                    val mtime = file.lastModified()
                    val existing = cache.get()
                    if (existing != null && existing.path == backgroundImagePath && existing.lastModified == mtime) {
                        return@withContext existing.bitmap
                    }
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()?.also {
                        cache.set(BgCacheEntry(backgroundImagePath, mtime, it))
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

        val image = bitmap
        if (image != null) {
            val painter = remember(image) { BitmapPainter(image) }
            result = result.paint(painter, sizeToIntrinsics = false, contentScale = ContentScale.Crop)
                .drawBehind {
                    if (backgroundTint > 0) drawRect(Color.Black.copy(alpha = backgroundTint.coerceIn(0, 100) / 100f))
                }
        }
    }
    result
}
