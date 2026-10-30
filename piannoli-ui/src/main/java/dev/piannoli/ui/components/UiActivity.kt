package dev.piannoli.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

// Hosts can pause presentation work without disposing navigation or cancelling user operations.
// A flow also cancels work when a stopped window no longer has frames for recomposition.
val LocalUiActive = staticCompositionLocalOf<StateFlow<Boolean>> { MutableStateFlow(true) }

@Composable
fun <T> StateFlow<T>.collectAsStateWhileActive(): State<T> {
    val activity = LocalUiActive.current
    return produceState(value, this, activity) {
        activity.collectLatest { active ->
            if (active) collect { value = it }
        }
    }
}
