package dev.piannoli.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier

enum class AnimationMode {
    FULL, TOUCH_INPUT, OFF;

    fun enabled(touchInput: Boolean): Boolean = this == FULL || (this == TOUCH_INPUT && touchInput)

    companion object {
        fun fromString(value: String?): AnimationMode = entries.firstOrNull { it.name == value } ?: FULL
    }
}

val LocalAnimationsEnabled = staticCompositionLocalOf { true }
val LocalAnimationMode = staticCompositionLocalOf { AnimationMode.FULL }

@Composable
fun animationsEnabledWhenActive(enabled: Boolean = LocalAnimationsEnabled.current): Boolean =
    enabled && LocalUiActive.current.collectAsState().value

object NoAnimationIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): Modifier.Node = object : Modifier.Node() {}
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
