package dev.piannoli.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily

private val PiannoliColorScheme = darkColorScheme(
    primary = White,
    onPrimary = Black,
    background = Black,
    surface = Black,
    onBackground = White,
    onSurface = White,
    surfaceVariant = DarkGray,
    onSurfaceVariant = GrayText
)

@Composable
fun PiannoliTheme(
    fontFamily: FontFamily = FontFamily.Default,
    iconFontFamily: FontFamily = fontFamily,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPiannoliFont provides fontFamily,
        LocalPiannoliIconFont provides iconFontFamily,
    ) {
        MaterialTheme(
            colorScheme = PiannoliColorScheme,
            typography = buildTypography(fontFamily),
            content = content
        )
    }
}
