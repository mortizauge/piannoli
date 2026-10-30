package dev.piannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.DPAD_HORIZONTAL
import dev.piannoli.ui.R
import dev.piannoli.ui.theme.PiannoliColors
import dev.piannoli.ui.theme.PiannoliTypography
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.ui.theme.LocalPiannoliTypography
import dev.piannoli.ui.theme.Spacing

@Composable
fun QuickInfoOverlay(
    addresses: List<String>,
    selectedIndex: Int,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val typo = LocalPiannoliTypography.current
    val colors = LocalPiannoliColors.current
    val shape = RoundedCornerShape(12.dp)
    val safeIndex = selectedIndex.coerceIn(0, (addresses.size - 1).coerceAtLeast(0))
    val ip = addresses.getOrNull(safeIndex) ?: stringResource(R.string.quick_info_offline)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenBackground(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.quick_info_title),
                style = typo.titleLarge.copy(color = colors.text)
            )

            Spacer(modifier = Modifier.height(Spacing.Lg))

            Column(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .background(colors.text.copy(alpha = 0.05f), shape)
                    .border(1.dp, colors.text.copy(alpha = 0.20f), shape)
            ) {
                InfoRow(stringResource(R.string.quick_info_ip), ip, typo, colors)
            }
        }

        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = buildList {
                add(buttonStyle.back to stringResource(R.string.label_back))
                if (addresses.size > 1) add(DPAD_HORIZONTAL to stringResource(R.string.label_interface))
            },
            rightItems = emptyList()
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, typo: PiannoliTypography, colors: PiannoliColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.uppercase(),
            style = typo.labelSmall.copy(color = colors.text.copy(alpha = 0.55f), letterSpacing = 1.sp)
        )
        Spacer(modifier = Modifier.width(Spacing.Md))
        Text(
            text = value,
            style = typo.bodyMedium.copy(color = colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
