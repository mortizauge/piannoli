package dev.cannoli.scorza.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkShapeTest {
    private fun outline(radius: Int, size: Size) =
        (ArtworkShape(radius).createOutline(size, LayoutDirection.Ltr, Density(1f)) as Outline.Rounded).roundRect

    @Test fun `zero keeps square corners and full artwork bounds`() {
        val shape = outline(0, Size(200f, 300f))
        assertEquals(Rect(0f, 0f, 200f, 300f), Rect(shape.left, shape.top, shape.right, shape.bottom))
        assertEquals(CornerRadius.Zero, shape.topLeftCornerRadius)
    }

    @Test fun `maximum is a centered perfect circle for portrait landscape and square art`() {
        for (size in listOf(Size(200f, 300f), Size(300f, 200f), Size(200f, 200f))) {
            val shape = outline(100, size)
            assertEquals(200f, shape.width, 0f)
            assertEquals(200f, shape.height, 0f)
            assertEquals(CornerRadius(100f), shape.topLeftCornerRadius)
            assertEquals(size.width / 2f, (shape.left + shape.right) / 2f, 0f)
            assertEquals(size.height / 2f, (shape.top + shape.bottom) / 2f, 0f)
        }
    }

    @Test fun `intermediate radius rounds proportionally toward circle`() {
        val shape = outline(50, Size(200f, 300f))
        assertEquals(Rect(0f, 25f, 200f, 275f), Rect(shape.left, shape.top, shape.right, shape.bottom))
        assertEquals(CornerRadius(50f), shape.topLeftCornerRadius)
    }
}
