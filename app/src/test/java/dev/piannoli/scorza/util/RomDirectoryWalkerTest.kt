package dev.piannoli.scorza.util

import android.content.res.AssetManager
import dev.piannoli.scorza.di.PiannoliPathsProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class RomDirectoryWalkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var romsDir: File
    private lateinit var walker: RomDirectoryWalker
    private val filters = mutableMapOf<String, Set<String>>()
    private var showHiddenFiles = false

    @Before
    fun setUp() {
        root = tempFolder.root
        romsDir = File(root, "Roms").apply { mkdirs() }

        val assets = mockk<AssetManager>()
        every { assets.open(any()) } throws FileNotFoundException()

        val paths = mockk<PiannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns romsDir
        every { paths.filteredExtensions(any()) } answers { filters[firstArg()] ?: emptySet() }
        every { paths.showHiddenFiles } answers { showHiddenFiles }

        val arcade = mockk<ArcadeTitleLookup>()
        every { arcade.mapFor(any(), any()) } returns emptyMap()
        every { arcade.invalidate(any()) } just Runs

        walker = RomDirectoryWalker(paths, assets, arcade)
    }

    private fun write(relativePath: String, content: String = "") {
        val f = File(romsDir, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun m3uName(base: String) = "PS${File.separator}$base${File.separator}$base.m3u"

    @Test fun `all PlayStation aliases are scanned even when canonical folder exists preserving real paths`() {
        write("PS/Canonical.chd")
        write("Ps1/One.chd")
        write("psx/Two.chd")
        write("PlayStation/Three.chd")
        write("Sony - PlayStation/Four.chd")
        write("psx/Wrong.nes")
        write("psx/.Hidden.chd")
        write("PS2/Other.chd")
        val result = walker.walk("PS", false)!!
        assertEquals(setOf("PS/Canonical.chd", "Ps1/One.chd", "psx/Two.chd", "PlayStation/Three.chd",
            "Sony - PlayStation/Four.chd").map { it.replace('/', File.separatorChar) }.toSet(),
            result.roms.map { it.relativePath }.toSet())
        assertTrue(result.roms.all { File(romsDir, it.relativePath).isFile })
        assertEquals(result.roms, walker.walk("psx", false)!!.roms)
        assertEquals(listOf("chd", "nes"), walker.presentExtensions("PS"))
        filters["PS"] = setOf("chd")
        assertTrue(walker.walk("PS", false)!!.roms.isEmpty())
    }

    @Test fun `category folders merge across aliases and renaming preserves each physical root`() {
        write("Ps1/RPG/First.chd")
        write("psx/RPG/Second.chd")
        write("PlayStation/Action/Third.chd")
        assertEquals(listOf("Action", "RPG"), walker.categoryFolders("PS", false))
        val moves = walker.renameCategory("PS", "RPG", "Adventure")!!
        assertEquals(2, moves.size)
        assertTrue(File(romsDir, "Ps1/Adventure/First.chd").isFile)
        assertTrue(File(romsDir, "psx/Adventure/Second.chd").isFile)
        assertTrue(File(romsDir, "PlayStation/Action/Third.chd").isFile)
        assertEquals(2, walker.categoryDirectories("PS", "Adventure").size)
        assertTrue(walker.categoryDirectories("PS", "..").isEmpty())
        assertEquals(null, walker.renameCategory("PS", "Adventure", "../bad"))
    }

    @Test fun `category rename refuses conflicts before changing any alias folder`() {
        write("Ps1/RPG/First.chd")
        write("psx/RPG/Second.chd")
        write("psx/Adventure/Existing.chd")
        assertEquals(null, walker.renameCategory("PS", "RPG", "Adventure"))
        assertTrue(File(romsDir, "Ps1/RPG/First.chd").isFile)
        assertTrue(File(romsDir, "psx/RPG/Second.chd").isFile)
    }

    @Test fun `hidden files and nested hidden folders obey toggle and category cache follows changes`() {
        write("GB/Visible.gb")
        write("GB/.Secret.gb")
        write("GB/.Hidden/Inside.gb")
        write("GB/.Hidden/.Deeper/More.gb")
        write("GB/.Hidden/Wrong.gba")
        assertEquals(1, walker.walk("GB", false)!!.roms.size)
        assertTrue(walker.categoryFolders("GB", false).isEmpty())
        assertEquals(listOf("gb"), walker.presentExtensions("GB"))
        showHiddenFiles = true
        assertEquals(4, walker.walk("GB", false)!!.roms.size)
        assertEquals(listOf(".Hidden", ".Hidden/.Deeper"), walker.categoryFolders("GB", false))
        assertEquals(listOf("gb", "gba"), walker.presentExtensions("GB"))
        filters["GB"] = setOf("gb")
        assertTrue(walker.walk("GB", false)!!.roms.isEmpty())
        filters.clear()
        showHiddenFiles = false
        assertEquals(1, walker.walk("GB", false)!!.roms.size)
        assertTrue(walker.categoryFolders("GB", false).isEmpty())
        assertTrue(File(romsDir, "GB/.Secret.gb").exists())
    }

    @Test fun `extension discovery includes nested unsupported files and filtering is reversible without deleting files`() {
        write("GB/Game.GB")
        write("GB/Other.zip")
        write("GB/Sub/Notes.txt")
        write("GB/WithoutExtension")
        write("GB/.hidden.pdf")
        assertEquals(listOf("", "gb", "txt", "zip"), walker.presentExtensions("GB"))
        filters["GB"] = setOf("zip")
        assertEquals(listOf("GB${File.separator}Game.GB"), walker.walk("GB", false)!!.roms.map { it.relativePath })
        assertTrue(File(romsDir, "GB/Other.zip").exists())
        filters.clear()
        assertEquals(2, walker.walk("GB", false)!!.roms.size)
    }

    @Test fun `excluded extension cannot bypass filtering through folder launch detection`() {
        write("GB/Folder/Folder.zip")
        filters["GB"] = setOf("zip")
        assertTrue(walker.walk("GB", false)!!.roms.isEmpty())
    }

    @Test fun `Game Boy scans only gb and zip files at every folder depth ignoring case`() {
        write("GB/Mario.GB")
        write("GB/Tetris.ZIP")
        write("GB/Color.gbc")
        write("GB/Wrong.gba")
        write("GB/manual.pdf")
        write("GB/cover.png")
        write("GB/game.sav")
        write("GB/unpacked")
        write("GB/Adventure/Metroid.gb")
        write("GB/Adventure/Metroid.txt")
        write("GB/Not a game/Not a game.txt")
        write("GB/Fake/Fake.cue")
        write("GB/Fake/Fake.bin")
        write("GB/Bundle/Bundle.m3u")
        write("GB/Bundle/Bundle.gb")
        val result = walker.walk("GB", false)!!
        assertEquals(
            setOf("Mario.GB", "Tetris.ZIP", "Metroid.gb", "Bundle.gb"),
            result.roms.map { File(it.relativePath).name }.toSet(),
        )
        assertEquals(4, result.roms.size)
        assertEquals(null, walker.gameDirectory(File(romsDir, "GB/Not a game/Not a game.txt")))
        assertEquals(File(romsDir, "GB/Bundle"), walker.gameDirectory(File(romsDir, "GB/Bundle/Bundle.gb")))
        assertTrue("Adventure" in walker.categoryFolders("GB", false))
    }

    @Test fun `GameCube accepts Dolphin disc formats and excludes Wii only and unrelated files`() {
        val extensions = listOf("iso", "RVZ", "gcm", "gcz", "ciso", "wia", "dol", "elf", "tgc")
        extensions.forEachIndexed { index, extension -> write("GC/Game $index.$extension") }
        write("GC/Wii.wbfs")
        write("GC/Channel.wad")
        write("GC/Archive.zip")
        write("GC/Readme.txt")
        write("GC/PlayStation.chd")
        write("GC/Extras/Other.rvz")
        assertEquals(
            (extensions.indices.map { "Game $it" } + "Other").toSet(),
            walker.walk("GC", false)!!.roms.map { it.displayName }.toSet(),
        )
        write("WII/Game.wbfs")
        write("WII/Channel.wad")
        assertEquals(2, walker.walk("WII", false)!!.roms.size)
    }

    @Test fun `unsupported disc named files are not moved or turned into playlists`() {
        write("GB/Notes (Disc 1).txt")
        write("GB/Notes (Disc 2).txt")
        write("GB/Game (Disc 1).gb")
        write("GB/Game (Disc 2).gb")
        val result = walker.walk("GB", false)!!
        assertEquals(2, result.roms.size)
        assertTrue(result.rekeys.isEmpty())
        assertTrue(File(romsDir, "GB/Notes (Disc 1).txt").exists())
        assertTrue(File(romsDir, "GB/Game (Disc 1).gb").exists())
        assertFalse(File(romsDir, "GB/Game").exists())
    }

    @Test fun `cue track companions remain available when excluded as standalone game formats`() {
        write("SEGACD/Game.cue", """FILE "Track 01.bin" BINARY""")
        write("SEGACD/Track 01.bin", "disc data")
        write("SEGACD/Readme.txt")
        val result = walker.walk("SEGACD", false)!!
        assertEquals(1, result.roms.size)
        assertEquals("Game.cue", File(result.roms.single().relativePath).name)
        assertEquals("disc data", File(romsDir, "SEGACD/Game/Track 01.bin").readText())
    }

    @Test fun `PICO8 accepts embedded cartridges but excludes ordinary images`() {
        write("PICO8/Game.p8")
        write("PICO8/Other.P8.PNG")
        write("PICO8/Artwork.png")
        write("PICO8/Screenshot.jpg")
        assertEquals(setOf("Game", "Other"), walker.walk("PICO8", false)!!.roms.map { it.displayName }.toSet())
    }

    @Test
    fun `bundles three loose discs into one game`() {
        write("PS/Cool Game (Disc 1).chd")
        write("PS/Cool Game (Disc 2).chd")
        write("PS/Cool Game (Disc 3).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 3).chd").exists())
    }

    @Test
    fun `merges a late loose disc into an already-organized bundle`() {
        // State after a mid-upload scan already bundled discs 1 and 2.
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game/Cool Game (Disc 2).chd")
        write("PS/Cool Game/Cool Game.m3u", "Cool Game (Disc 1).chd\nCool Game (Disc 2).chd\n")
        // Disc 3 arrives loose afterwards.
        write("PS/Cool Game (Disc 3).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 3).chd").exists())
        assertFalse(File(romsDir, "PS/Cool Game (Disc 3).chd").exists())

        val discs = File(romsDir, "PS/Cool Game/Cool Game.m3u").readLines().filter { it.isNotBlank() }
        assertEquals(
            listOf(
                "Cool Game (Disc 1).chd",
                "Cool Game (Disc 2).chd",
                "Cool Game (Disc 3).chd",
            ),
            discs,
        )
    }

    @Test
    fun `relocates a loose m3u set into its own folder`() {
        write("PS/Cool Game.m3u", "Cool Game (Disc 1).chd\nCool Game (Disc 2).chd\n")
        write("PS/Cool Game (Disc 1).chd")
        write("PS/Cool Game (Disc 2).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game.m3u").exists())
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 1).chd").exists())
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 2).chd").exists())
        assertFalse(File(romsDir, "PS/Cool Game.m3u").exists())
        assertFalse(File(romsDir, "PS/Cool Game (Disc 1).chd").exists())
        assertFalse(File(romsDir, "PS/Cool Game (Disc 2).chd").exists())
    }

    @Test
    fun `writes an in-place m3u for discs in a game-named folder`() {
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game/Cool Game (Disc 2).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        val m3u = File(romsDir, "PS/Cool Game/Cool Game.m3u")
        assertTrue(m3u.exists())
        assertEquals(
            listOf("Cool Game (Disc 1).chd", "Cool Game (Disc 2).chd"),
            m3u.readLines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun `gameFiles and gameDirectory resolve an m3u folder`() {
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game/Cool Game (Disc 2).chd")
        write("PS/Cool Game/Cool Game.m3u", "Cool Game (Disc 1).chd\nCool Game (Disc 2).chd\n")

        val m3u = File(romsDir, "PS/Cool Game/Cool Game.m3u")
        assertEquals(File(romsDir, "PS/Cool Game"), walker.gameDirectory(m3u))
        assertEquals(
            listOf("Cool Game (Disc 1).chd", "Cool Game (Disc 2).chd", "Cool Game.m3u"),
            walker.gameFiles(m3u).map { it.name }.sorted(),
        )
        assertEquals(null, walker.gameDirectory(File(romsDir, "PS/Cool Game/Cool Game (Disc 1).chd")))
    }

    @Test
    fun `gameDirectory resolves a cue folder game`() {
        write("PS/Cool Game/Cool Game.cue", "cue")
        write("PS/Cool Game/Cool Game.bin", "bin")

        val cue = File(romsDir, "PS/Cool Game/Cool Game.cue")
        assertEquals(File(romsDir, "PS/Cool Game"), walker.gameDirectory(cue))
    }

    @Test
    fun `gameDirectory is null for a loose m3u directly under the platform dir`() {
        write("PS/Loose.m3u", "Loose (Disc 1).chd\n")
        val m3u = File(romsDir, "PS/Loose.m3u")
        assertEquals(null, walker.gameDirectory(m3u))
    }

    @Test
    fun `gameFiles for a loose single file returns just that file`() {
        write("NES/Mega Game.nes")
        val rom = File(romsDir, "NES/Mega Game.nes")
        assertEquals(null, walker.gameDirectory(rom))
        assertEquals(listOf(rom), walker.gameFiles(rom))
    }

    @Test
    fun `gameDirectory is null for a loose file in a category folder`() {
        write("PS/RPGs/Some Game.iso")
        val rom = File(romsDir, "PS/RPGs/Some Game.iso")
        assertEquals(null, walker.gameDirectory(rom))
        assertEquals(listOf(rom), walker.gameFiles(rom))
    }

    @Test
    fun `categoryFolders lists non-game directories only`() {
        write("PS/RPGs/Some Game.iso")
        write("PS/Racing/Game (Disc 1).chd")
        write("PS/Racing/Game (Disc 2).chd")
        write("PS/Racing/Game.m3u", "Game (Disc 1).chd\nGame (Disc 2).chd\n")
        walker.walk("PS", isArcade = false)
        assertEquals(listOf("Racing", "RPGs"), walker.categoryFolders("PS", isArcade = false))
    }

    @Test
    fun `renaming a single-file game renames just the file`() {
        write("NES/Mega Game.nes", "rom")
        val rom = File(romsDir, "NES/Mega Game.nes")

        val result = walker.renameGame(rom, "Super Game")

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, result.outcome)
        assertFalse(rom.exists())
        val renamed = File(romsDir, "NES/Super Game.nes")
        assertTrue(renamed.exists())
        assertEquals("rom", renamed.readText())
        assertEquals(renamed, result.newPrimary)
    }

    @Test
    fun `renaming a multi-disc folder game cascades to all files`() {
        write("PS/Cool Game (Disc 1).chd")
        write("PS/Cool Game (Disc 2).chd")
        write("PS/Cool Game (Disc 3).chd")
        walker.walk("PS", isArcade = false)

        val m3u = File(romsDir, "PS/Cool Game/Cool Game.m3u")
        val result = walker.renameGame(m3u, "Rad Game")

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, result.outcome)
        assertFalse(File(romsDir, "PS/Cool Game").exists())
        val newDir = File(romsDir, "PS/Rad Game")
        assertTrue(newDir.exists())
        assertTrue(File(newDir, "Rad Game.m3u").exists())
        assertTrue(File(newDir, "Rad Game (Disc 1).chd").exists())
        assertTrue(File(newDir, "Rad Game (Disc 2).chd").exists())
        assertTrue(File(newDir, "Rad Game (Disc 3).chd").exists())
        assertEquals(
            listOf(
                "Rad Game (Disc 1).chd",
                "Rad Game (Disc 2).chd",
                "Rad Game (Disc 3).chd",
            ),
            File(newDir, "Rad Game.m3u").readLines().filter { it.isNotBlank() },
        )
        assertEquals(File(newDir, "Rad Game.m3u"), result.newPrimary)
    }

    @Test
    fun `renaming a cue-based folder game cascades to all files`() {
        write("PS/Cool Game/Cool Game.cue", "cue")
        write("PS/Cool Game/Cool Game.bin", "bin")

        val cue = File(romsDir, "PS/Cool Game/Cool Game.cue")
        val result = walker.renameGame(cue, "Rad Game")

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, result.outcome)
        assertFalse(File(romsDir, "PS/Cool Game").exists())
        val newDir = File(romsDir, "PS/Rad Game")
        assertTrue(newDir.exists())
        assertTrue(File(newDir, "Rad Game.cue").exists())
        assertTrue(File(newDir, "Rad Game.bin").exists())
        assertEquals(File(newDir, "Rad Game.cue"), result.newPrimary)
    }

    @Test
    fun `renaming a single-file game to its current name returns RENAMED`() {
        write("NES/Mega Game.nes", "rom")
        val rom = File(romsDir, "NES/Mega Game.nes")

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, walker.renameGame(rom, "Mega Game").outcome)
        assertTrue(rom.exists())

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, walker.renameGame(rom, "mega game").outcome)
        assertTrue(rom.exists())
    }

    @Test
    fun `renaming to an existing name returns NAME_TAKEN`() {
        write("NES/Mega Game.nes")
        write("NES/Super Game.nes")
        val rom = File(romsDir, "NES/Mega Game.nes")

        assertEquals(
            RomDirectoryWalker.RenameOutcome.NAME_TAKEN,
            walker.renameGame(rom, "Super Game").outcome,
        )
        assertTrue(rom.exists())
    }

    @Test
    fun `merges a loose disc into a folder that holds discs but no m3u`() {
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game (Disc 2).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 1).chd").exists())
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 2).chd").exists())
        assertFalse(File(romsDir, "PS/Cool Game (Disc 2).chd").exists())
        assertEquals(
            listOf("Cool Game (Disc 1).chd", "Cool Game (Disc 2).chd"),
            File(romsDir, "PS/Cool Game/Cool Game.m3u").readLines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun `folder with rom named after it is one game launching that file`() {
        write("NSW/Cool Game/Cool Game.nsp", "base")
        write("NSW/Cool Game/update/Cool Game upd.nsp", "upd")
        write("NSW/Cool Game/dlc/Cool Game dlc.nsp", "dlc")
        val result = walker.walk("NSW", isArcade = false)!!
        assertEquals(1, result.roms.size)
        assertEquals("NSW${File.separator}Cool Game${File.separator}Cool Game.nsp", result.roms.single().relativePath)
        assertEquals("Cool Game", result.roms.single().displayName)
    }

    @Test
    fun `largest stem-matching file wins over sidecars`() {
        write("NSW/Cool Game/Cool Game.nsp", "big rom content")
        write("NSW/Cool Game/Cool Game.txt", "x")
        val result = walker.walk("NSW", isArcade = false)!!
        assertEquals(1, result.roms.size)
        assertEquals("NSW${File.separator}Cool Game${File.separator}Cool Game.nsp", result.roms.single().relativePath)
    }

    @Test
    fun `dirname m3u still wins over a stem-matching rom`() {
        write("PS/Cool Game/Cool Game.m3u", "Cool Game (Disc 1).chd\n")
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game/Cool Game.chd")
        val result = walker.walk("PS", isArcade = false)!!
        assertEquals("PS${File.separator}Cool Game${File.separator}Cool Game.m3u", result.roms.single().relativePath)
    }

    @Test
    fun `folder without a stem-matching rom keeps per-file entries`() {
        write("NSW/Two Games/Game A.nsp")
        write("NSW/Two Games/Game B.nsp")
        val result = walker.walk("NSW", isArcade = false)!!
        assertEquals(2, result.roms.size)
    }

    @Test
    fun `matching-rom folder is not a category folder and resolves gameDirectory`() {
        write("NSW/Cool Game/Cool Game.nsp")
        write("NSW/Cool Game/update/u.nsp")
        assertEquals(emptyList<String>(), walker.categoryFolders("NSW", isArcade = false).filter { it == "Cool Game" })
        val primary = File(romsDir, "NSW/Cool Game/Cool Game.nsp")
        assertEquals(File(romsDir, "NSW/Cool Game"), walker.gameDirectory(primary))
    }

    @Test fun `folder with a stem-matching rom and other roms keeps per-file entries`() {
        write("NES/Mario/Mario.nes")
        write("NES/Mario/Mario 2.nes")
        val result = walker.walk("NES", isArcade = false)!!
        assertEquals(2, result.roms.size)
    }

    @Test
    fun `arcade chd folder beside its romset is not a game`() {
        write("MAME/area51.zip")
        write("MAME/area51/area51.chd")
        val result = walker.walk("MAME", isArcade = true)!!
        assertEquals(listOf("MAME${File.separator}area51.zip"), result.roms.map { it.relativePath })
    }

    @Test
    fun `arcade chd folder holding several discs is not scanned`() {
        write("MAME/blitz99.zip")
        write("MAME/blitz99/blitz99.chd")
        write("MAME/blitz99/blitz99b.chd")
        val result = walker.walk("MAME", isArcade = true)!!
        assertEquals(listOf("MAME${File.separator}blitz99.zip"), result.roms.map { it.relativePath })
    }

    @Test
    fun `arcade chd folder named by disc id is not a game`() {
        write("FBN/ikaruga.zip")
        write("FBN/ikaruga/gdl-0010.chd")
        val result = walker.walk("FBN", isArcade = true)!!
        assertEquals(listOf("FBN${File.separator}ikaruga.zip"), result.roms.map { it.relativePath })
    }

    @Test
    fun `arcade chd folder beside a 7z romset is not a game`() {
        write("MAME/kinst.7z")
        write("MAME/kinst/kinst.chd")
        val result = walker.walk("MAME", isArcade = true)!!
        assertEquals(listOf("MAME${File.separator}kinst.7z"), result.roms.map { it.relativePath })
    }

    @Test
    fun `arcade support folder is not a category folder`() {
        write("MAME/blitz99.zip")
        write("MAME/blitz99/blitz99.chd")
        write("MAME/blitz99/blitz99b.chd")
        write("MAME/Fighting/sf2.zip")
        assertEquals(listOf("Fighting"), walker.categoryFolders("MAME", isArcade = true))
    }

    @Test
    fun `arcade folder without a matching romset still scans`() {
        write("MAME/Fighting/sf2.zip")
        val result = walker.walk("MAME", isArcade = true)!!
        assertEquals(
            listOf("MAME${File.separator}Fighting${File.separator}sf2.zip"),
            result.roms.map { it.relativePath },
        )
    }

    @Test
    fun `arcade support dir resolves for a romset zip`() {
        write("MAME/area51.zip")
        write("MAME/area51/area51.chd")
        val rom = File(romsDir, "MAME/area51.zip")
        assertEquals(File(romsDir, "MAME/area51"), walker.arcadeSupportDir(rom, isArcade = true))
    }

    @Test
    fun `arcade support dir resolves for a 7z romset`() {
        write("MAME/kinst.7z")
        write("MAME/kinst/kinst.chd")
        val rom = File(romsDir, "MAME/kinst.7z")
        assertEquals(File(romsDir, "MAME/kinst"), walker.arcadeSupportDir(rom, isArcade = true))
    }

    @Test
    fun `arcade support dir is null when the platform is not arcade`() {
        write("NSW/Cool Game.zip")
        write("NSW/Cool Game/Cool Game.nsp")
        val rom = File(romsDir, "NSW/Cool Game.zip")
        assertEquals(null, walker.arcadeSupportDir(rom, isArcade = false))
    }

    @Test
    fun `arcade support dir is null without a matching folder`() {
        write("MAME/sf2.zip")
        val rom = File(romsDir, "MAME/sf2.zip")
        assertEquals(null, walker.arcadeSupportDir(rom, isArcade = true))
    }

    @Test
    fun `arcade support dir is null for a non-archive rom`() {
        write("MAME/pacman.chd")
        write("MAME/pacman/pacman.chd")
        val rom = File(romsDir, "MAME/pacman.chd")
        assertEquals(null, walker.arcadeSupportDir(rom, isArcade = true))
    }

    @Test
    fun `unsupported Switch archive is skipped without hiding same-named game folder`() {
        write("NSW/Cool Game.zip")
        write("NSW/Cool Game/Cool Game.nsp")
        val result = walker.walk("NSW", isArcade = false)!!
        assertEquals(
            listOf("NSW${File.separator}Cool Game${File.separator}Cool Game.nsp"),
            result.roms.map { it.relativePath }.sorted(),
        )
    }
}
