package dev.piannoli.scorza.ui.quickmenu

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickMenuRowsTest {
    @Test fun `apps lists hide bulk scraping but keep order and other menu actions`() {
        assertEquals(
            listOf(QuickMenuRow.ORDER_BY, QuickMenuRow.RESCAN, QuickMenuRow.INFO),
            QuickMenuRow.visibleRows(rommPaired = false, contentList = true, gameList = true, appsList = true),
        )
    }

    @Test fun `game lists hide system scraping independently of order controls`() {
        for (canOrder in listOf(false, true)) {
            val rows = QuickMenuRow.visibleRows(rommPaired = false, contentList = canOrder, gameList = true)
            assertEquals(true, QuickMenuRow.SCRAPING in rows)
            assertEquals(false, QuickMenuRow.SCRAPE_SYSTEMS in rows)
        }
    }

    @Test fun `order setting is first only for local content lists`() {
        assertEquals(QuickMenuRow.ORDER_BY, QuickMenuRow.visibleRows(rommPaired = true,
            contentList = true, downloadCount = 2).first())
        assertEquals(false, QuickMenuRow.ORDER_BY in QuickMenuRow.visibleRows(rommPaired = true))
    }

    @Test fun `romm row only present when paired`() {
        val withRomm = QuickMenuRow.visibleRows(rommPaired = true)
        val withoutRomm = QuickMenuRow.visibleRows(rommPaired = false)
        assertEquals(true, withRomm.contains(QuickMenuRow.ROMM))
        assertEquals(false, withoutRomm.contains(QuickMenuRow.ROMM))
    }

    @Test fun `order is romm, scraping, systems, rescan, info`() {
        assertEquals(
            listOf(QuickMenuRow.ROMM, QuickMenuRow.SCRAPING, QuickMenuRow.SCRAPE_SYSTEMS, QuickMenuRow.RESCAN, QuickMenuRow.INFO),
            QuickMenuRow.visibleRows(rommPaired = true)
        )
    }

    @Test fun `without romm the rest still present in order`() {
        assertEquals(
            listOf(QuickMenuRow.SCRAPING, QuickMenuRow.SCRAPE_SYSTEMS, QuickMenuRow.RESCAN, QuickMenuRow.INFO),
            QuickMenuRow.visibleRows(rommPaired = false)
        )
    }

    @Test fun `errors row only when sync enabled and errors present`() {
        val withErrors = QuickMenuRow.visibleRows(rommPaired = true, saveSyncEnabled = true, syncErrors = 2)
        val noErrors = QuickMenuRow.visibleRows(rommPaired = true, saveSyncEnabled = true, syncErrors = 0)
        val syncDisabled = QuickMenuRow.visibleRows(rommPaired = true, saveSyncEnabled = false, syncErrors = 2)
        assertEquals(true, withErrors.contains(QuickMenuRow.ERRORS))
        assertEquals(false, noErrors.contains(QuickMenuRow.ERRORS))
        assertEquals(false, syncDisabled.contains(QuickMenuRow.ERRORS))
    }

    @Test fun `errors row follows conflicts`() {
        val rows = QuickMenuRow.visibleRows(
            rommPaired = true, saveSyncEnabled = true, pendingConflicts = 1, syncErrors = 1,
        )
        assertEquals(rows.indexOf(QuickMenuRow.CONFLICTS) + 1, rows.indexOf(QuickMenuRow.ERRORS))
    }

    @Test fun `downloads row present immediately after romm when paired`() {
        val rows = QuickMenuRow.visibleRows(rommPaired = true, downloadCount = 3)
        assertEquals(true, rows.contains(QuickMenuRow.DOWNLOADS))
        assertEquals(rows.indexOf(QuickMenuRow.ROMM) + 1, rows.indexOf(QuickMenuRow.DOWNLOADS))
    }

    @Test fun `downloads row present and first when not paired`() {
        val rows = QuickMenuRow.visibleRows(rommPaired = false, downloadCount = 3)
        assertEquals(true, rows.contains(QuickMenuRow.DOWNLOADS))
        assertEquals(false, rows.contains(QuickMenuRow.ROMM))
        assertEquals(QuickMenuRow.DOWNLOADS, rows.first())
    }

    @Test fun `downloads row absent when queue empty`() {
        val rows = QuickMenuRow.visibleRows(rommPaired = true, downloadCount = 0)
        assertEquals(false, rows.contains(QuickMenuRow.DOWNLOADS))
    }
}
