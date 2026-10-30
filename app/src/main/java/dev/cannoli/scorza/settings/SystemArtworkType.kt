package dev.cannoli.scorza.settings

enum class SystemArtworkType {
    LOGO, ICON, GRID;

    companion object {
        fun fromString(value: String?) = entries.firstOrNull { it.name == value } ?: LOGO
    }
}
