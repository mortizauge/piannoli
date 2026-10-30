package dev.piannoli.scorza.input.runtime

import dev.piannoli.scorza.input.AnalogRole
import dev.piannoli.scorza.input.CanonicalButton

sealed interface CanonicalEvent {
    data class Pressed(val button: CanonicalButton) : CanonicalEvent
    data class Released(val button: CanonicalButton) : CanonicalEvent
    data class AnalogChanged(val role: AnalogRole, val value: Float) : CanonicalEvent
}
