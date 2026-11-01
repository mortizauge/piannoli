package dev.piannoli.scorza.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import dev.piannoli.scorza.settings.ArtScale

@Composable
internal fun SystemArtworkPanel(
    painter: Painter,
    contentDescription: String?,
    scale: ArtScale,
    radius: Int,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
    isFallback: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Measure after the screen's title, footer, insets and artwork padding have taken their space.
        Box(
            modifier = Modifier.fillMaxWidth().height(maxHeight * 0.4f).clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val sourceSize = painter.intrinsicSize
            val fit = if (sourceSize != Size.Unspecified && sourceSize.width > 0 && sourceSize.height > 0) {
                Modifier.aspectRatio(sourceSize.width / sourceSize.height)
            } else {
                Modifier.fillMaxSize()
            }
            // Transparent category symbols keep their full silhouette even in the fill/crop modes.
            val (imageModifier, contentScale) = when (scale) {
                ArtScale.FIT -> fit to ContentScale.Fit
                ArtScale.ORIGINAL -> if (isFallback) fit to ContentScale.Inside else Modifier.wrapContentSize() to ContentScale.None
                ArtScale.FIT_WIDTH -> (if (isFallback) fit else Modifier.fillMaxWidth()) to ContentScale.FillWidth
                ArtScale.FIT_HEIGHT -> (if (isFallback) fit else Modifier.fillMaxHeight()) to ContentScale.FillHeight
            }
            Image(
                painter = painter,
                contentDescription = contentDescription,
                // Keep a circular crop circular even when the source is scaled beyond this viewport.
                modifier = imageModifier.clip(ArtworkShape(radius)).artworkClip(radius, sourceSize, contentScale),
                contentScale = contentScale,
                colorFilter = colorFilter,
            )
        }
    }
}
