package dev.piannoli.scorza.model

import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.settings.LibraryOrder
import dev.piannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LibraryOrderingTest {
    private val choices = mutableMapOf<String, LibraryOrder>()
    private val manual = mutableMapOf<String, List<String>>()
    private var defaultOrder = LibraryOrder.ALPHABETICAL
    private val settings = mockk<SettingsRepository> {
        every { defaultLibraryOrder } answers { defaultOrder }
        every { listOrder(any()) } answers { choices[firstArg()] ?: LibraryOrder.DEFAULT }
        every { setListOrder(any(), any()) } answers { choices[firstArg()] = secondArg() }
        every { manualListOrder(any()) } answers { manual[firstArg()].orEmpty() }
        every { setManualListOrder(any(), any()) } answers { manual[firstArg()] = secondArg() }
    }
    private val paths = mockk<PiannoliPathsProvider> {
        every { romDir } returns File("Roms")
    }
    private val ordering = LibraryOrdering(settings, paths)
    private val alpha = ListItem.RomItem(Rom(1, File("a.gb"), "GB", "alpha"))
    private val beta = ListItem.RomItem(Rom(2, File("b.gb"), "GB", "Beta", lastPlayedAt = 20))
    private val app = ListItem.AppItem(App(3, AppType.PORT, "Game 2", "dev.game", lastPlayedAt = 10))
    private val ten = ListItem.RomItem(Rom(4, File("c.gb"), "GB", "Game 10"))

    @Test fun `last added is collection only and ranks mixed members while preserving pins`() {
        assertEquals(LibraryOrder.MANUAL, LibraryOrder.LAST_PLAYED.cycle(1))
        assertEquals(LibraryOrder.LAST_ADDED, LibraryOrder.LAST_PLAYED.cycle(1, allowLastAdded = true))
        assertEquals(LibraryOrder.LAST_PLAYED, LibraryOrder.LAST_ADDED.cycle(-1, allowLastAdded = true))
        val pinned = ListItem.AppItem(App(5, AppType.TOOL, "Pin", "dev.pin", isPinned = true))
        ordering.select("one", LibraryOrder.LAST_ADDED)
        assertEquals(listOf(pinned, app, beta, alpha), ordering.sort(
            listOf(alpha, beta, pinned, app), "one", emptySet(), emptySet(),
            listOf(dev.piannoli.scorza.db.LibraryRef.App(3), dev.piannoli.scorza.db.LibraryRef.Rom(2),
                dev.piannoli.scorza.db.LibraryRef.Rom(1))))
    }

    @Test fun `pinned apps retain incoming order across all explicit and inherited sorting styles`() {
        val zulu = ListItem.AppItem(App(5, AppType.TOOL, "Zulu", "dev.zulu", isPinned = true))
        val aardvark = ListItem.AppItem(App(6, AppType.TOOL, "Aardvark", "dev.aardvark",
            lastPlayedAt = 100, isPinned = true))
        val folder = ListItem.SubfolderItem("Folder", "Folder")
        val items = listOf(app, zulu, beta, aardvark, alpha, folder)
        ordering.rememberManual("one", listOf(aardvark, zulu, alpha, beta, app))
        for (order in LibraryOrder.entries.filter { it != LibraryOrder.DEFAULT }) {
            ordering.select("one", order)
            val sorted = ordering.sort(items, "one", emptySet(), setOf(aardvark.app.id))
            assertEquals(listOf(folder, zulu, aardvark), sorted.take(3))
            defaultOrder = order
            ordering.select("one", LibraryOrder.DEFAULT)
            assertEquals(sorted, ordering.sort(items, "one", emptySet(), setOf(aardvark.app.id)))
        }
    }

    @Test fun `platform option is conditional and groups alphabetically with natural title ordering`() {
        val nes = alpha.copy(rom = alpha.rom.copy(id = 5, platformTag = "NES"))
        val folder = ListItem.SubfolderItem("Folder", "Folder")
        val items = listOf(app, nes, ten, beta, alpha, folder)
        assertEquals(false, ordering.hasMultiplePlatforms(listOf(alpha, beta, folder)))
        assertEquals(true, ordering.hasMultiplePlatforms(items))
        assertEquals(LibraryOrder.DEFAULT, LibraryOrder.MANUAL.cycle(1))
        assertEquals(LibraryOrder.PLATFORM, LibraryOrder.MANUAL.cycle(1, allowPlatform = true))
        assertEquals(LibraryOrder.DEFAULT, LibraryOrder.PLATFORM.cycle(1, allowPlatform = true))
        assertEquals(LibraryOrder.PLATFORM, LibraryOrder.DEFAULT.cycle(-1, allowPlatform = true))
        assertEquals(LibraryOrder.ALPHABETICAL, LibraryOrder.MANUAL.cycle(1, allowDefault = false))
        ordering.select("one", LibraryOrder.PLATFORM)
        assertEquals(listOf(folder, alpha, beta, ten, nes, app),
            ordering.sort(items, "one", emptySet(), emptySet()))
        assertEquals(listOf(folder, nes, alpha, beta, ten, app),
            ordering.sort(items, "one", setOf(5), emptySet()))
    }

    @Test fun `natural alphabetical and most recent orders mix ROMs and apps and keep unplayed last`() {
        val members = listOf(ten, app, beta, alpha)
        assertEquals(listOf(alpha, beta, app, ten), ordering.sort(members, "one", emptySet(), emptySet()))
        ordering.select("one", LibraryOrder.LAST_PLAYED)
        assertEquals(listOf(beta, app, alpha, ten), ordering.sort(members, "one", emptySet(), emptySet()))
    }

    @Test fun `global default affects inherited lists but not explicit overrides`() {
        ordering.select("one", LibraryOrder.ALPHABETICAL)
        defaultOrder = LibraryOrder.LAST_PLAYED
        val items = listOf(alpha, beta)
        assertEquals(listOf(alpha, beta), ordering.sort(items, "one", emptySet(), emptySet()))
        assertEquals(listOf(beta, alpha), ordering.sort(items, "two", emptySet(), emptySet()))
        ordering.select("one", LibraryOrder.DEFAULT)
        assertEquals(listOf(beta, alpha), ordering.sort(items, "one", emptySet(), emptySet()))
    }

    @Test fun `manual order survives other modes and keeps folders pins and favorites first`() {
        val folder = ListItem.SubfolderItem("Folder", "Folder")
        val pinned = ListItem.AppItem(App(5, AppType.TOOL, "Zulu", "dev.pinned", isPinned = true))
        val items = listOf(ten, alpha, beta, app)
        ordering.rememberManual("one", items)
        ordering.select("one", LibraryOrder.ALPHABETICAL)
        ordering.sort(items, "one", emptySet(), emptySet())
        ordering.select("one", LibraryOrder.MANUAL)
        assertEquals(items, ordering.sort(listOf(alpha, app, beta, ten), "one", emptySet(), emptySet()))
        assertEquals(listOf(folder, pinned, beta, ten, alpha, app),
            ordering.sort(items + pinned + folder, "one", setOf(2), emptySet()))
    }
}
