package dev.piannoli.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.R

@Composable
fun RALoggingInOverlay(message: String, buttonStyle: ButtonStyle = ButtonStyle(), title: String? = null) {
    LoadingOverlay(message = message, buttonStyle = buttonStyle, title = title ?: stringResource(R.string.ra_title))
}
