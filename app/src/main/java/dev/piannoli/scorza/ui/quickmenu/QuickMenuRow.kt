package dev.piannoli.scorza.ui.quickmenu

enum class QuickMenuRow {
    ROMM, DOWNLOADS, SYNC_HISTORY, CONFLICTS, ERRORS, SCRAPING, SCRAPE_SYSTEMS, RESCAN, INFO;

    companion object {
        fun visibleRows(rommPaired: Boolean, saveSyncEnabled: Boolean = false, pendingConflicts: Int = 0, syncErrors: Int = 0, downloadCount: Int = 0): List<QuickMenuRow> =
            buildList {
                if (rommPaired) add(ROMM)
                if (downloadCount > 0) add(DOWNLOADS)
                if (rommPaired && saveSyncEnabled) add(SYNC_HISTORY)
                if (rommPaired && pendingConflicts > 0) add(CONFLICTS)
                if (rommPaired && saveSyncEnabled && syncErrors > 0) add(ERRORS)
                add(SCRAPING)
                add(SCRAPE_SYSTEMS)
                add(RESCAN)
                add(INFO)
            }
    }
}
