package dev.piannoli.scorza.ui.quickmenu

enum class QuickMenuRow {
    ORDER_BY, ROMM, DOWNLOADS, SYNC_HISTORY, CONFLICTS, ERRORS, SCRAPING, SCRAPE_SYSTEMS, RESCAN, INFO;

    companion object {
        fun visibleRows(rommPaired: Boolean, saveSyncEnabled: Boolean = false, pendingConflicts: Int = 0, syncErrors: Int = 0, downloadCount: Int = 0, contentList: Boolean = false, gameList: Boolean = false, appsList: Boolean = false): List<QuickMenuRow> =
            buildList {
                if (contentList) add(ORDER_BY)
                if (rommPaired) add(ROMM)
                if (downloadCount > 0) add(DOWNLOADS)
                if (rommPaired && saveSyncEnabled) add(SYNC_HISTORY)
                if (rommPaired && pendingConflicts > 0) add(CONFLICTS)
                if (rommPaired && saveSyncEnabled && syncErrors > 0) add(ERRORS)
                if (!appsList) add(SCRAPING)
                if (!gameList) add(SCRAPE_SYSTEMS)
                add(RESCAN)
                add(INFO)
            }
    }
}
