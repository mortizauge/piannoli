package dev.piannoli.scorza.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.model.ListItem
import dev.piannoli.scorza.util.ArcadeTitleLookup
import dev.piannoli.scorza.util.ArtworkLookup
import dev.piannoli.scorza.util.RomDirectoryWalker
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformFolderScanTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var romDir: File
    private lateinit var db: PiannoliDatabase
    private lateinit var scanner: RomScanner
    private lateinit var repository: RomsRepository
    private lateinit var config: PlatformConfig

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        romDir = File(temp.root, "Roms").apply { mkdirs() }
        val paths = mockk<PiannoliPathsProvider>()
        every { paths.root } returns temp.root
        every { paths.romDir } returns romDir
        every { paths.showHiddenFiles } returns false
        every { paths.filteredExtensions(any()) } returns emptySet()
        val artwork = ArtworkLookup(paths)
        db = PiannoliDatabase(paths)
        scanner = RomScanner(db, RomDirectoryWalker(paths, context.assets, ArcadeTitleLookup(paths)), artwork)
        repository = RomsRepository(paths, db, artwork)
        config = PlatformConfig(temp.root, context.assets)
    }

    @After fun cleanup() { db.close() }

    private fun write(path: String): File = File(romDir, path).apply {
        parentFile!!.mkdirs()
        writeText("ROM")
    }

    @Test fun `all folders combine into one platform with launchable paths stable ids and nested browsing`() {
        write("PS/Canonical.chd")
        write("Ps1/First.chd")
        write("psx/Second.chd")
        write("PlayStation/RPG/Nested.chd")
        write("Ps1/RPG/Another.chd")
        write("psx/Wrong.gba")
        assertEquals(5, scanner.scanPlatform("PS").inserted)
        val roms = repository.allRomsForPlatform("PS")
        assertEquals(mapOf("PS" to 5), repository.platformCounts())
        assertTrue(roms.all { it.path.isFile && it.platformTag == "PS" })
        assertEquals(config.getCoreName("PS"), config.resolvePlatform("psx", romDir, 5).coreName)
        assertEquals(3, repository.gamesForPlatform("PS").filterIsInstance<ListItem.RomItem>().size)
        assertEquals(listOf("RPG"), repository.gamesForPlatform("PS").filterIsInstance<ListItem.SubfolderItem>().map { it.name })
        assertEquals(2, repository.gamesForPlatform("PS", "RPG").filterIsInstance<ListItem.RomItem>().size)
        assertEquals(RomScanner.SyncCounts(0, 0, 0), scanner.scanPlatform("playstation"))
        assertEquals(roms.map { it.id }, repository.allRomsForPlatform("PS").map { it.id })
        File(romDir, "psx/Second.chd").delete()
        assertEquals(1, scanner.scanPlatform("PS1").removed)
        assertEquals(4, repository.allRomsForPlatform("PS").size)
    }

    @Test fun `moving legacy alias rows to canonical system preserves ids and game metadata`() {
        val file = write("psx/Game.chd")
        scanner.scanPlatform("PS")
        val original = repository.gameByPath(file.absolutePath)!!
        repository.setRaGameId(original.id, 123)
        db.execute("INSERT INTO platforms (tag, display_name) VALUES (?, ?)", "PSX", "PlayStation")
        db.execute("UPDATE roms SET platform_tag = ?, path = ? WHERE id = ?",
            "PSX", "PSX${File.separator}Game.chd", original.id)
        scanner.scanPlatform("PS")
        val restored = repository.gameByPath(file.absolutePath)!!
        assertEquals(original.id, restored.id)
        assertEquals(123, restored.raGameId)
        assertEquals("PS", restored.platformTag)
    }
}
