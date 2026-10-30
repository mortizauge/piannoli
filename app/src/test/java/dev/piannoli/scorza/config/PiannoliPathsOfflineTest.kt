package dev.piannoli.scorza.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PiannoliPathsOfflineTest {
    private fun File.endsWithPath(vararg components: String): Boolean =
        path.endsWith(components.joinToString(File.separator, prefix = File.separator))

    @Test
    fun offlineDir_isUnderConfigRetroAchievements() {
        val paths = PiannoliPaths("/sd")
        assertTrue(paths.configRaOffline.endsWithPath("Config", "RetroAchievements", "Offline"))
    }

    @Test
    fun renamedProject_keepsExistingDatabaseAndFontPaths() {
        val paths = PiannoliPaths("/sd")
        assertTrue(paths.database.endsWithPath("Config", "cannoli.db"))
        assertTrue(paths.piannoliFont.endsWithPath("Config", "Assets", "cannoli", "font.ttf"))
    }
}
