package dev.piannoli.scorza.settings

enum class ScrapeFailBehavior(val retry: Boolean, val bestMatch: Boolean) {
    SKIP(false, false),
    BEST_MATCH(false, true),
    RETRY_SKIP(true, false),
    RETRY_BEST_MATCH(true, true);

    companion object {
        val DEFAULT = RETRY_SKIP
        fun fromString(value: String?): ScrapeFailBehavior =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
