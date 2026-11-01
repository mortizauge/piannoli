package dev.piannoli.scorza.config

import dev.piannoli.scorza.R

enum class EmulatorSource(val displayName: String) {
    RetroArch("RetroArch"),
    Standalone("Standalone");

    val emptyMessageRes: Int
        get() = when (this) {
            RetroArch -> R.string.value_no_cores_found
            Standalone -> R.string.value_none_installed
        }

    companion object {
        /**
         * Migration only. v1 cores.json stored the picker's display caption as the runner, so a
         * source has to be recovered from it. Nothing at runtime may parse a caption back into a
         * source: options and choices carry [EmulatorSource] directly.
         */
        fun fromRunnerLabel(label: String?): EmulatorSource? = when (label) {
            "Internal", "Built-in", "BUILTIN" -> null
            "Standalone", "App" -> Standalone
            null, "" -> null
            else -> RetroArch
        }

        fun fromStoredName(name: String?): EmulatorSource? =
            entries.firstOrNull { it.name == name }

        fun isLegacyBuiltIn(name: String?): Boolean =
            name in setOf("Internal", "Built-in", "BUILTIN", "Builtin", "BuiltIn")
    }
}
