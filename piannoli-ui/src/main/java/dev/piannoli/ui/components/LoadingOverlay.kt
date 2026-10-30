package dev.piannoli.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.R
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.ui.theme.LocalPiannoliTypography

@Composable
fun LoadingOverlay(
    message: String = "",
    buttonStyle: ButtonStyle = ButtonStyle(),
    title: String = stringResource(R.string.loading_title),
    showBack: Boolean = true,
) {
    val colors = LocalPiannoliColors.current
    val typography = LocalPiannoliTypography.current
    Box(
        modifier = Modifier.fillMaxSize().screenBackground(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = title, style = typography.titleLarge.copy(color = colors.text))
            if (message.isNotEmpty()) {
                Text(text = message, style = typography.bodyMedium.copy(color = colors.text.copy(alpha = 0.6f)))
            }
        }
        if (showBack) {
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(screenPadding),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = emptyList(),
            )
        }
    }
}
