package dev.piannoli.scorza.input.screen.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.piannoli.scorza.input.ScreenInputHandler
import dev.piannoli.scorza.input.runtime.ScreenInputRegistry

val LocalScreenInputRegistry = staticCompositionLocalOf<ScreenInputRegistry> {
    error("ScreenInputRegistry not provided. Wrap setContent in CompositionLocalProvider.")
}

/**
 * Registers [handler] as the active screen handler while this composable is in composition AND
 * the containing lifecycle is at least RESUMED. The lifecycle gate is critical: when an activity
 * goes to the background to launch an external emulator, its composables stay in composition
 * but must stop receiving controller events until the frontend resumes.
 */
@Composable
fun ScreenInput(handler: ScreenInputHandler) {
    val registry = LocalScreenInputRegistry.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(handler, lifecycle) {
        var pushed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (!pushed) {
                        registry.push(handler)
                        pushed = true
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (pushed) {
                        registry.pop(handler)
                        pushed = false
                    }
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (pushed) {
                registry.pop(handler)
                pushed = false
            }
        }
    }
}
