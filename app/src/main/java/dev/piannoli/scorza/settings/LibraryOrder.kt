package dev.piannoli.scorza.settings

import dev.piannoli.scorza.R

enum class LibraryOrder(val labelRes: Int) {
    ALPHABETICAL(R.string.order_alphabetical),
    LAST_PLAYED(R.string.order_last_played),
    MANUAL(R.string.order_manual),
    PLATFORM(R.string.order_platform),
    DEFAULT(R.string.order_default);

    fun cycle(direction: Int, allowDefault: Boolean = true, allowPlatform: Boolean = false): LibraryOrder {
        val options = entries.filter { (allowDefault || it != DEFAULT) && (allowPlatform || it != PLATFORM) }
        return options[Math.floorMod(options.indexOf(this) + direction, options.size)]
    }

    companion object {
        fun fromStored(value: String, fallback: LibraryOrder): LibraryOrder =
            entries.firstOrNull { it.name == value } ?: fallback
    }
}
