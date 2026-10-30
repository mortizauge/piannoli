package dev.piannoli.scorza.input.runtime

import dev.piannoli.scorza.input.AnalogRole
import dev.piannoli.scorza.input.CanonicalButton

data class PortSnapshot(
    val pressed: Set<CanonicalButton>,
    val analog: Map<AnalogRole, Float>,
)
