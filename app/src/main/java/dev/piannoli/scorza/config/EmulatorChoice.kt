package dev.piannoli.scorza.config

data class EmulatorChoice(
    val source: EmulatorSource,
    val coreId: String = "",
    val appPackage: String? = null,
)
