package dev.cannoli.scorza.db

import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.util.ArtworkLookup
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScrapingRomsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `subfolder exclusion uses platform root and includes self contained game bundles`() {
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns tmp.root
        every { paths.romDir } returns File(tmp.root, "Roms")
        val art = mockk<ArtworkLookup>()
        every { art.find(any(), any()) } returns null
        val db = CannoliDatabase(paths)
        try {
            db.execute("INSERT INTO platforms (tag, display_name) VALUES (?, ?)", "SNES", "SNES")
            for (relative in listOf("root.sfc", "nested${File.separator}child.sfc", "bundle${File.separator}bundle.sfc")) {
                db.execute(
                    "INSERT INTO roms (path, platform_tag, display_name, sort_key, name_normalized) VALUES (?, ?, ?, ?, ?)",
                    "SNES${File.separator}$relative", "SNES", relative, relative, relative,
                )
            }
            val repo = RomsRepository(paths, db, art)
            assertEquals(setOf("root.sfc", "bundle.sfc"), repo.romsForScraping("snes", false).map { it.path.name }.toSet())
            assertEquals(setOf("root.sfc", "bundle.sfc", "child.sfc"), repo.romsForScraping("snes", true).map { it.path.name }.toSet())
        } finally {
            db.close()
        }
    }
}
