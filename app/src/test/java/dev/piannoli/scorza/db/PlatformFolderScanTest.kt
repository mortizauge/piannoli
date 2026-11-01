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
    private lateinit var paths: PiannoliPathsProvider

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        romDir = File(temp.root, "Roms").apply { mkdirs() }
        paths = mockk<PiannoliPathsProvider>()
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

    @Test fun `Xbox compound extensions are hidden in titles without changing file identities`() {
        val files = listOf(
            write("Xbox/Halo.xiso.iso"),
            write("Microsoft Xbox/Fable (USA).XISO.ISO"),
            write("Xbox/Forza.xiso"),
            write("Xbox/Game.xiso.edition.iso"),
        )
        scanner.scanPlatform("XBOX")
        val roms = repository.allRomsForPlatform("XBOX").associateBy { it.path.name }
        assertEquals("Halo", roms.getValue("Halo.xiso.iso").displayName)
        assertEquals("Fable", roms.getValue("Fable (USA).XISO.ISO").displayName)
        assertEquals("Forza", roms.getValue("Forza.xiso").displayName)
        assertEquals("Game.xiso.edition", roms.getValue("Game.xiso.edition.iso").displayName)
        assertEquals(files.toSet(), roms.values.map { it.path }.toSet())
        assertTrue(files.all { it.isFile })
    }

    @Test fun `Xbox aliases merge supported formats without including Xbox 360`() {
        write("xbox/Halo.ISO")
        write("Microsoft Xbox/Fable.xiso")
        write("oRiGiNaL xBoX/Game.XBOE")
        write("XBOX/Homebrew.dotno")
        write("Microsoft Xbox/Other.chd")
        write("Xbox360/Not Original.iso")
        assertEquals(4, scanner.scanPlatform("Original Xbox").inserted)
        val roms = repository.allRomsForPlatform("XBOX")
        assertEquals(setOf("Halo.ISO", "Fable.xiso", "Game.XBOE", "Homebrew.dotno"),
            roms.map { it.path.name }.toSet())
        assertTrue(roms.all { it.platformTag == "XBOX" && it.path.isFile })
        assertEquals(mapOf("XBOX" to 4), repository.platformCounts())
        assertEquals(RomScanner.SyncCounts(0, 0, 0), scanner.scanPlatform("Microsoft Xbox"))
        assertEquals("Microsoft Xbox", config.resolvePlatform("original xbox", romDir, 4).displayName)
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

    @Test fun `hidden game stays out of normal lists after scan and restart with membership intact`() {
        val file = write("PS/Hidden.chd")
        scanner.scanPlatform("PS")
        val game = repository.gameByPath(file.absolutePath)!!
        val collections = CollectionsRepository(db)
        val favorites = collections.favoritesId() ?: collections.create(
            "Favorites", dev.piannoli.scorza.model.CollectionType.FAVORITES,
        )
        val shelf = collections.create("Shelf")
        collections.addMember(favorites, LibraryRef.Rom(game.id))
        collections.addMember(shelf, LibraryRef.Rom(game.id))
        RecentlyPlayedRepository(db).record(LibraryRef.Rom(game.id), 10)

        repository.setHidden(game.id, true)
        assertTrue(repository.gamesForPlatform("PS").isEmpty())
        assertTrue(repository.allRomsForPlatform("PS").isEmpty())
        assertTrue(repository.searchAllGames(dev.piannoli.scorza.model.GameSearchQuery("Hidden")).isEmpty())
        assertEquals(1, repository.platformCounts()["PS"] ?: 0)
        assertEquals(0, repository.visiblePlatformCounts()["PS"] ?: 0)
        assertEquals(0, collections.romIdsIn(favorites).size)
        assertTrue(collections.isMember(favorites, LibraryRef.Rom(game.id)))
        assertTrue(collections.isMember(shelf, LibraryRef.Rom(game.id)))
        assertTrue(RecentlyPlayedRepository(db).recent().isEmpty())
        assertEquals(game.id, repository.hiddenGames().single().id)

        db.close()
        db = PiannoliDatabase(paths)
        val artwork = ArtworkLookup(paths)
        repository = RomsRepository(paths, db, artwork)
        scanner = RomScanner(db, RomDirectoryWalker(
            paths, ApplicationProvider.getApplicationContext<Context>().assets, ArcadeTitleLookup(paths),
        ), artwork)
        scanner.scanPlatform("PS")
        assertTrue(repository.gamesForPlatform("PS").isEmpty())
        assertTrue(repository.hiddenGames().single().isHidden)
        assertTrue(CollectionsRepository(db).isMember(favorites, LibraryRef.Rom(game.id)))

        repository.setHidden(game.id, false)
        assertEquals(listOf(game.id), repository.gamesForPlatform("PS")
            .filterIsInstance<ListItem.RomItem>().map { it.rom.id })
        assertEquals(listOf(game.id), CollectionsRepository(db).romIdsIn(favorites))
    }
}
