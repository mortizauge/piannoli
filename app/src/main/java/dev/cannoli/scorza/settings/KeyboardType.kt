package dev.cannoli.scorza.settings

enum class KeyboardType {
    PIANNOLI, SYSTEM;

    companion object {
        fun fromString(value: String?): KeyboardType =
            entries.firstOrNull { it.name == value } ?: PIANNOLI
    }
}
