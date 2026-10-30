package dev.piannoli.scorza.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

data class ArtworkShape(val radius: Int) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val fraction = radius.coerceIn(0, 100) / 100f
        val diameter = size.minDimension
        // Inset the longer axis too, so portrait covers reach a circle rather than a capsule.
        val insetX = (size.width - diameter) * fraction / 2f
        val insetY = (size.height - diameter) * fraction / 2f
        return Outline.Rounded(
            RoundRect(
                left = insetX,
                top = insetY,
                right = size.width - insetX,
                bottom = size.height - insetY,
                cornerRadius = CornerRadius(diameter * fraction / 2f),
            )
        )
    }

}

fun Modifier.artworkClip(radius: Int, sourceSize: Size, contentScale: ContentScale): Modifier = drawWithCache {
    val renderedSize = if (sourceSize != Size.Unspecified &&
        sourceSize.width > 0 && sourceSize.height > 0 && size.width > 0 && size.height > 0
    ) {
        val scale = contentScale.computeScaleFactor(sourceSize, size)
        Size(sourceSize.width * scale.scaleX, sourceSize.height * scale.scaleY)
    } else size
    val path = Path().apply {
        addOutline(ArtworkShape(radius).createOutline(renderedSize, layoutDirection, this@drawWithCache))
        translate(Offset((size.width - renderedSize.width) / 2f, (size.height - renderedSize.height) / 2f))
    }
    onDrawWithContent {
        clipRect { clipPath(path) { this@onDrawWithContent.drawContent() } }
    }
}
