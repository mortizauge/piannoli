package dev.cannoli.scorza.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class DeferredScanRequestsTest {
    @Test fun `background file events coalesce and flush once on foreground return`() {
        val scans = mutableListOf<String>()
        val requests = DeferredScanRequests(scans::add)
        requests.request("GB")
        requests.request("GB")
        requests.request("GBA")
        assertEquals(emptyList<String>(), scans)

        requests.setActive(true)
        requests.setActive(true)
        assertEquals(listOf("GB", "GBA"), scans)
        requests.request("GB")
        assertEquals(listOf("GB", "GBA", "GB"), scans)
    }

    @Test fun `losing focus defers again and changing roots discards old requests`() {
        val scans = mutableListOf<String>()
        val requests = DeferredScanRequests(scans::add)
        requests.setActive(true)
        requests.request("GB")
        requests.setActive(false)
        requests.request("GBA")
        requests.clear()
        requests.request("NES")
        assertEquals(listOf("GB"), scans)
        requests.setActive(true)
        assertEquals(listOf("GB", "NES"), scans)
    }
}
