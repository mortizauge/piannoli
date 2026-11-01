package dev.piannoli.scorza.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalArtworkStatusBarHeight = staticCompositionLocalOf<State<Dp>> { mutableStateOf(0.dp) }

@Composable
internal fun Modifier.artworkStatusBarPadding(existingTopPadding: Dp = 0.dp): Modifier =
    padding(vertical = maxOf(existingTopPadding, LocalArtworkStatusBarHeight.current.value))

@Composable
internal fun MeasuredArtworkStatusBar(
    height: MutableState<Dp>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    DisposableEffect(height) {
        onDispose { height.value = 0.dp }
    }
    // Screen margins are shared with the artwork already; measure only the bar's content.
    Box(modifier.onSizeChanged { height.value = with(density) { it.height.toDp() } }) {
        content()
    }
}
