package dev.piannoli.scorza.db

import dev.piannoli.scorza.config.EmulatorChoice
import dev.piannoli.scorza.config.EmulatorSource
import dev.piannoli.scorza.di.PiannoliPathsProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameOverrideStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun db(name: String): PiannoliDatabase {
        val root = File(tmp.root, name).apply { mkdirs() }
        val paths = mockk<PiannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns File(root, "Roms").apply { mkdirs() }
        return PiannoliDatabase(paths)
    }

    private fun insertRom(db: PiannoliDatabase, tag: String, path: String): Long {
        db.execute("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)", tag, tag)
        return db.executeReturningId(
            "INSERT INTO roms (path, platform_tag, display_name, sort_key, name_normalized) VALUES (?, ?, ?, ?, ?)",
            path, tag, path.substringAfterLast('/'), path.lowercase(), path.lowercase(),
        )
    }

    @Test fun `a core override round trips`() {
        val db = db("ov-core")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "NES", "NES/game.nes")
        store.put(id, EmulatorChoice(EmulatorSource.RetroArch, "nestopia_libretro"))
        assertEquals(EmulatorChoice(EmulatorSource.RetroArch, "nestopia_libretro"), store.get(id))
    }

    @Test fun `v14 built-in overrides migrate to external auto without touching other choices or roms`() {
        val database = db("ov-legacy")
        val old = insertRom(database, "NES", "NES/old.nes")
        val external = insertRom(database, "NES", "NES/external.nes")
        database.execute("INSERT INTO game_overrides (rom_id, source, core_id) VALUES (?, ?, ?)", old, "Internal", "nestopia_libretro")
        GameOverrideStore(database).put(external, EmulatorChoice(EmulatorSource.RetroArch, "nestopia_libretro"))
        database.execute("PRAGMA user_version = 14")
        database.close()

        val migrated = db("ov-legacy")
        val store = GameOverrideStore(migrated)
        assertNull(store.get(old))
        assertEquals(EmulatorSource.RetroArch, store.get(external)?.source)
        assertEquals(1, store.countForPlatform("NES"))
        assertEquals(2, migrated.queryOne("SELECT COUNT(*) FROM roms") { it.getInt(0) })
        migrated.close()
    }

    @Test fun `a standalone override round trips`() {
        val db = db("ov-app")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "GBA", "GBA/game.gba")
        store.put(id, EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.fastemulator.gba"))
        assertEquals(
            EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.fastemulator.gba"),
            store.get(id),
        )
    }

    @Test fun `put replaces rather than duplicating`() {
        val db = db("ov-replace")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "NES", "NES/game.nes")
        store.put(id, EmulatorChoice(EmulatorSource.RetroArch, "a_libretro"))
        store.put(id, EmulatorChoice(EmulatorSource.RetroArch, "b_libretro"))
        assertEquals(EmulatorChoice(EmulatorSource.RetroArch, "b_libretro"), store.get(id))
        assertEquals(1, store.countForPlatform("NES"))
    }

    @Test fun `clear removes the override`() {
        val db = db("ov-clear")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "NES", "NES/game.nes")
        store.put(id, EmulatorChoice(EmulatorSource.RetroArch, "a_libretro"))
        store.clear(id)
        assertNull(store.get(id))
    }

    // The whole reason per-game overrides moved into the database.
    @Test fun `an override follows its rom through a path change`() {
        val db = db("ov-move")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "PS", "PS/FF7 (Disc 1).chd")
        store.put(id, EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.epsxe.ePSXe"))
        db.execute("UPDATE roms SET path = ? WHERE id = ?", "PS/FF7/FF7 (Disc 1).chd", id)
        assertEquals(
            EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.epsxe.ePSXe"),
            store.get(id),
        )
    }

    @Test fun `deleting the rom cascades the override away`() {
        val db = db("ov-cascade")
        val store = GameOverrideStore(db)
        val id = insertRom(db, "NES", "NES/game.nes")
        store.put(id, EmulatorChoice(EmulatorSource.RetroArch, "a_libretro"))
        db.execute("DELETE FROM roms WHERE id = ?", id)
        assertNull(store.get(id))
    }

    // path.startsWith(tagDir) used to make Game Boy list and clear GBA overrides.
    @Test fun `forPlatform does not leak across a tag prefix`() {
        val db = db("ov-prefix")
        val store = GameOverrideStore(db)
        val gb = insertRom(db, "GB", "GB/tetris.gb")
        val gba = insertRom(db, "GBA", "GBA/metroid.gba")
        store.put(gb, EmulatorChoice(EmulatorSource.RetroArch, "gambatte_libretro"))
        store.put(gba, EmulatorChoice(EmulatorSource.RetroArch, "mgba_libretro"))
        assertEquals(1, store.forPlatform("GB").size)
        assertEquals(gb, store.forPlatform("GB").single().romId)
        assertEquals(1, store.countForPlatform("GBA"))
    }

}
