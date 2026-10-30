package dev.cannoli.scorza.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrapeTitleMatcherTest {
    @Test fun `cleaning separates words and preserves unicode letters and numbers`() {
        assertEquals("Pokémon Title 2 USA", ScrapeTitleMatcher.cleanedQuery("  Pokémon:Title-2 (USA)™  "))
        assertEquals("", ScrapeTitleMatcher.cleanedQuery("!@#$"))
    }

    @Test fun `similarity favors the title over unrelated provider first result`() {
        val closest = SteamGridGame(20, "Super Mario World Deluxe")
        val candidates = listOf(SteamGridGame(10, "Super Metroid"), closest)
        assertEquals(closest, ScrapeTitleMatcher.closest("Super Mario Wurld", candidates))
        assertEquals(closest, ScrapeTitleMatcher.closest("Super Mario Wurld", candidates.reversed()))
    }

    @Test fun `sequel numbers include roman numerals and outweigh a shorter wrong sequel`() {
        val closest = SteamGridGame(20, "Final Fantasy VII Remake")
        assertEquals(closest, ScrapeTitleMatcher.closest("Final Fantasy 7", listOf(
            SteamGridGame(10, "Final Fantasy VIII"), closest,
        )))
    }

    @Test fun `ties are deterministic and blank titles never force a match`() {
        val first = SteamGridGame(10, "Game")
        assertEquals(first, ScrapeTitleMatcher.closest("Game!", listOf(SteamGridGame(20, "Game"), first)))
        assertNull(ScrapeTitleMatcher.closest("!!!", listOf(first)))
        assertNull(ScrapeTitleMatcher.closest("Game", emptyList()))
    }
}
