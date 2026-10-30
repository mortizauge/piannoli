package dev.piannoli.scorza.input.runtime

import dev.piannoli.scorza.input.DeviceMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveMappingHolder {

    private val _active = MutableStateFlow<DeviceMapping?>(null)
    val active: StateFlow<DeviceMapping?> = _active.asStateFlow()

    fun set(mapping: DeviceMapping) {
        _active.value = mapping
    }

    fun clear() {
        _active.value = null
    }
}

fun DeviceMapping?.confirmButton(): dev.piannoli.ui.ConfirmButton = when {
    this == null -> dev.piannoli.ui.ConfirmButton.EAST
    this.menuConfirm == dev.piannoli.scorza.input.CanonicalButton.BTN_EAST -> dev.piannoli.ui.ConfirmButton.EAST
    else -> dev.piannoli.ui.ConfirmButton.SOUTH
}

fun DeviceMapping?.labelSet(fallback: dev.piannoli.ui.ButtonLabelSet): dev.piannoli.ui.ButtonLabelSet =
    when (this?.glyphStyle) {
        dev.piannoli.scorza.input.GlyphStyle.PLUMBER -> dev.piannoli.ui.ButtonLabelSet.PLUMBER
        dev.piannoli.scorza.input.GlyphStyle.REDMOND -> dev.piannoli.ui.ButtonLabelSet.REDMOND
        dev.piannoli.scorza.input.GlyphStyle.SHAPES -> dev.piannoli.ui.ButtonLabelSet.SHAPES
        null -> fallback
    }
