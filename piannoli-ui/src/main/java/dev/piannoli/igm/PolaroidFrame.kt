package dev.piannoli.igm

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.ui.theme.GrayText
import dev.piannoli.ui.theme.PolaroidDark
import dev.piannoli.ui.theme.PolaroidInactive
import dev.piannoli.ui.theme.PolaroidSelect
import dev.piannoli.ui.theme.Radius
import dev.piannoli.ui.theme.Spacing

@Composable
fun PolaroidFrame(
    thumbnail: Bitmap?,
    selectedSlotIndex: Int,
    slotOccupied: kotlin.collections.List<Boolean>,
    showIndicators: Boolean = true
) {
    val selectedColor = PolaroidSelect

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.Sm))
            .background(Color.White)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (showIndicators) 12.dp else 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(10f / 9f)
                .clip(RoundedCornerShape(Radius.Sm))
                .background(PolaroidDark),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = "Empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GrayText
                )
            }
        }

        if (showIndicators) {
        Spacer(modifier = Modifier.height(Spacing.Sm))

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val autoSelected = selectedSlotIndex == 0
            val autoOccupied = slotOccupied.getOrElse(0) { false }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.Sm))
                    .then(if (autoSelected) Modifier.background(selectedColor) else Modifier)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "AUTO",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = when {
                            autoSelected -> Color.White
                            autoOccupied -> Color.Black
                            else -> PolaroidInactive
                        }
                    )
                )
            }
            Spacer(modifier = Modifier.width(6.dp))

            for (i in 1..10) {
                val occupied = slotOccupied.getOrElse(i) { false }
                val selected = selectedSlotIndex == i
                val dotSize = if (selected) 10.dp else 8.dp

                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(dotSize)
                        .then(
                            if (occupied) {
                                Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) selectedColor else Color.Black)
                            } else {
                                Modifier
                                    .clip(CircleShape)
                                    .border(1.dp, Color.Black, CircleShape)
                            }
                        )
                )
            }
        }
        }
    }
}
