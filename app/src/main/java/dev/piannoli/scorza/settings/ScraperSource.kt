package dev.piannoli.scorza.settings

enum class ScraperSource(val label: String) {
    STEAMGRIDDB("SteamGridDB"),
    SCREENSCRAPER("ScreenScraper");

    companion object {
        fun fromString(value: String?): ScraperSource = entries.firstOrNull { it.name == value } ?: STEAMGRIDDB
    }
}

enum class ScreenScraperMedia(val apiType: String, val labelRes: Int) {
    LOGO("wheel", dev.piannoli.scorza.R.string.screenscraper_media_logo),
    COVER("box-2D", dev.piannoli.scorza.R.string.screenscraper_media_cover),
    SCREENSHOT("ss", dev.piannoli.scorza.R.string.screenscraper_media_screenshot),
    TITLE_SCREEN("sstitle", dev.piannoli.scorza.R.string.screenscraper_media_title_screen);

    companion object {
        fun fromString(value: String?): ScreenScraperMedia = entries.firstOrNull { it.name == value } ?: COVER
    }
}
