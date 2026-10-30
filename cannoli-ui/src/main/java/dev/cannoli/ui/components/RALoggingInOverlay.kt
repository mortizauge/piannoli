package dev.cannoli.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R

@Composable
fun RALoggingInOverlay(message: String, buttonStyle: ButtonStyle = ButtonStyle(), title: String? = null) {
    LoadingOverlay(message = message, buttonStyle = buttonStyle, title = title ?: stringResource(R.string.ra_title))
}
