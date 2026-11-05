package dev.piannoli.scorza.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.input.ListNavigation
import dev.piannoli.scorza.db.AppsRepository
import dev.piannoli.scorza.db.CollectionsRepository
import dev.piannoli.scorza.db.RecentlyPlayedRepository
import dev.piannoli.scorza.db.LibraryRef
import dev.piannoli.scorza.db.RomsRepository
import dev.piannoli.scorza.db.ScanScheduler
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.model.LibraryOrdering
import dev.piannoli.scorza.model.ListItem
import dev.piannoli.scorza.model.CollectionType
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.navigation.BrowsePosition
import dev.piannoli.scorza.navigation.CollectionBrowseParent
import dev.piannoli.scorza.navigation.GameListBookmark
import dev.piannoli.scorza.settings.ContentMode
import dev.piannoli.scorza.settings.LibraryOrder
import dev.piannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameListBookmarkTest {
    private val roms = mockk<RomsRepository>(relaxed = true)
    private val apps = mockk<AppsRepository>(relaxed = true)
    private val collections = mockk<CollectionsRepository>(relaxed = true)
    private val recent = mockk<RecentlyPlayedRepository>(relaxed = true)
    private val platforms = mockk<PlatformConfig>()
    private val scans = mockk<ScanScheduler>()
    private lateinit var model: GameListViewModel
    private val mario = ListItem.RomItem(Rom(2, File("Mario.gb"), "GB", "Mario"))
    private val metroid = ListItem.RomItem(Rom(3, File("Metroid.gb"), "GB", "Metroid"))
    private val folderRevision = MutableStateFlow(0L)
    private val scanResults = MutableSharedFlow<ScanScheduler.ScanResult>()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { platforms.getDisplayName("GB") } returns "Game Boy"
        every { scans.results } returns scanResults
        every { roms.folderArtworkRevision } returns folderRevision
        every { roms.refreshFolderArtwork(any(), any()) } answers { secondArg() }
        every { roms.gamesForPlatform("GB", any()) } returns listOf(mario, metroid)
        model = GameListViewModel(
            roms, apps, collections, recent, platforms, scans, mockk(), mockk(relaxed = true),
            ApplicationProvider.getApplicationContext<Context>(),
            mockk<LibraryOrdering> {
                every { key(any(), any(), any()) } returns "list"
                every { sort(any(), any(), any(), any()) } answers { firstArg() }
            },
        )
    }

    @After fun cleanup() {
        model.close()
        Dispatchers.resetMain()
    }

    private suspend fun restore(bookmark: GameListBookmark) = suspendCancellableCoroutine { continuation ->
        model.restoreBookmark(bookmark) { continuation.resume(Unit) }
    }

    private suspend fun cancelSearch() = suspendCancellableCoroutine { continuation ->
        model.cancelSearch { continuation.resume(Unit) }
    }

    private fun useRealOrdering(defaultOrder: LibraryOrder = LibraryOrder.ALPHABETICAL): LibraryOrdering {
        val choices = mutableMapOf<String, LibraryOrder>()
        val manual = mutableMapOf<String, List<String>>()
        val settings = mockk<SettingsRepository> {
            every { defaultLibraryOrder } returns defaultOrder
            every { listOrder(any()) } answers { choices[firstArg()] ?: LibraryOrder.DEFAULT }
            every { setListOrder(any(), any()) } answers { choices[firstArg()] = secondArg() }
            every { manualListOrder(any()) } answers { manual[firstArg()].orEmpty() }
            every { setManualListOrder(any(), any()) } answers { manual[firstArg()] = secondArg() }
        }
        val paths = mockk<PiannoliPathsProvider> {
            every { romDir } returns File("Roms")
        }
        val ordering = LibraryOrdering(settings, paths)
        model.close()
        model = GameListViewModel(roms, apps, collections, recent, platforms, scans, paths,
            mockk(relaxed = true), ApplicationProvider.getApplicationContext<Context>(), ordering)
        return ordering
    }

    @Test fun `recently played stays newest first without order controls regardless of preferences or favorites`() = runBlocking {
        every { recent.recent(limit = 15) } returns listOf(
            RecentlyPlayedRepository.Entry(LibraryRef.Rom(metroid.rom.id), 200),
            RecentlyPlayedRepository.Entry(LibraryRef.Rom(mario.rom.id), 100),
        )
        every { roms.gameById(mario.rom.id) } returns mario.rom
        every { roms.gameById(metroid.rom.id) } returns metroid.rom
        every { collections.favoriteRomIds() } returns setOf(mario.rom.id)
        for (default in listOf(LibraryOrder.ALPHABETICAL, LibraryOrder.MANUAL, LibraryOrder.LAST_PLAYED)) {
            val ordering = useRealOrdering(default)
            val key = ordering.key("platform", "recently_played")
            ordering.select(key, LibraryOrder.ALPHABETICAL)
            ordering.rememberManual(key, listOf(mario, metroid))
            suspendCancellableCoroutine<Unit> { done -> model.loadRecentlyPlayed { done.resume(Unit) } }
            assertEquals(listOf(metroid, mario), model.state.value.items)
            assertEquals(false, model.canOrderContent())
            assertEquals(false, dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.ORDER_BY in
                dev.piannoli.scorza.ui.quickmenu.QuickMenuRow.visibleRows(
                    rommPaired = false, contentList = model.canOrderContent()))
            model.enterReorderMode()
            assertEquals(false, model.isReorderMode())
            model.cycleOrder(1)
            assertEquals(LibraryOrder.ALPHABETICAL, ordering.selection(key))
            suspendCancellableCoroutine<Unit> { done -> model.reload { done.resume(Unit) } }
            assertEquals(listOf(metroid, mario), model.state.value.items)
            model.setSearch("No matches")
            assertTrue(model.state.value.items.isEmpty())
            assertEquals(false, model.canOrderContent())
        }
        every { recent.recent(limit = 15) } returns emptyList()
        suspendCancellableCoroutine<Unit> { done -> model.loadRecentlyPlayed { done.resume(Unit) } }
        assertEquals(false, model.canOrderContent())
    }

    @Test fun `list overrides isolate folders preserve selection and manual order across reloads`() = runBlocking {
        val ordering = useRealOrdering()
        val played = metroid.copy(rom = metroid.rom.copy(lastPlayedAt = 100))
        every { roms.gamesForPlatform("GB", any()) } returns listOf(mario, played)
        restore(GameListBookmark(platformTag = "GB", platformTags = listOf("GB")))
        model.setSelectedIndex(1)
        assertTrue(model.canOrderContent())
        assertEquals(LibraryOrder.ALPHABETICAL, model.cycleOrder(1))
        assertEquals(LibraryOrder.LAST_PLAYED, model.cycleOrder(1))
        withTimeout(5_000) { model.state.first { it.items.firstOrNull() == played } }
        assertEquals(played, model.getSelectedItem())

        model.enterSubfolder("RPG")
        withTimeout(5_000) { model.state.first { it.subfolderPath == "RPG" } }
        assertEquals(LibraryOrder.DEFAULT, model.selectedOrder())
        assertEquals(listOf(mario, played), model.state.value.items)
        ordering.select(ordering.key("platform", "GB", "RPG"), LibraryOrder.MANUAL)
        model.enterReorderMode()
        model.reorderMoveDown()
        model.confirmReorder()
        suspendCancellableCoroutine<Unit> { done -> model.reload { done.resume(Unit) } }
        assertEquals(listOf(played, mario), model.state.value.items)
        ordering.select(ordering.key("platform", "GB", "RPG"), LibraryOrder.ALPHABETICAL)
        suspendCancellableCoroutine<Unit> { done -> model.reload { done.resume(Unit) } }
        assertEquals(listOf(mario, played), model.state.value.items)
        ordering.select(ordering.key("platform", "GB", "RPG"), LibraryOrder.MANUAL)
        suspendCancellableCoroutine<Unit> { done -> model.reload { done.resume(Unit) } }
        assertEquals(listOf(played, mario), model.state.value.items)
    }

    @Test fun `mixed platform lists offer platform ordering even while search shows one platform`() = runBlocking {
        val ordering = useRealOrdering()
        val nes = mario.copy(rom = mario.rom.copy(platformTag = "NES"))
        every { roms.gamesForPlatform("GB", any()) } returns listOf(nes, metroid)
        restore(GameListBookmark(platformTag = "GB", platformTags = listOf("GB")))
        ordering.select(ordering.key("platform", "GB"), LibraryOrder.MANUAL)
        model.setSearch("Mario")
        assertEquals(LibraryOrder.PLATFORM, model.cycleOrder(1))
        suspendCancellableCoroutine<Unit> { done -> model.reload { done.resume(Unit) } }
        assertEquals(listOf(metroid, nes), model.state.value.allItems)
        assertEquals(listOf(nes), model.state.value.items)
        assertEquals(LibraryOrder.PLATFORM, model.selectedOrder())
    }

    @Test fun `handheld home inherits global order and shares collection overrides and manual sequence`() = runBlocking {
        val ordering = useRealOrdering(LibraryOrder.LAST_PLAYED)
        val played = metroid.copy(rom = metroid.rom.copy(lastPlayedAt = 100))
        every { collections.byId(7) } returns
            CollectionsRepository.CollectionRow(7, "Games", null, 0, CollectionType.STANDARD)
        every { collections.membersIn(7) } returns listOf(LibraryRef.Rom(2), LibraryRef.Rom(3))
        every { roms.gameById(2) } returns mario.rom
        every { roms.gameById(3) } returns played.rom
        val paths = mockk<PiannoliPathsProvider>(relaxed = true) {
            every { romDir } returns File("Roms")
        }
        val home = SystemListViewModel(roms, mockk(relaxed = true), apps, collections, recent,
            mockk(relaxed = true), paths, mockk(relaxed = true), scans, ordering)
        suspend fun loadHome() = suspendCancellableCoroutine<Unit> { done ->
            home.scan(contentMode = ContentMode.FIVE_GAME_HANDHELD, fghCollectionId = 7,
                scanDisk = false, onReady = { done.resume(Unit) })
        }
        fun members() = home.state.value.items.filterIsInstance<SystemListViewModel.ListItem.GameItem>().map { it.item }
        try {
            loadHome()
            assertTrue(home.canOrderContent())
            assertEquals(listOf(played, mario), members())
            ordering.select(ordering.key("collection", "7"), LibraryOrder.ALPHABETICAL)
            loadHome()
            assertEquals(listOf(mario, played), members())
            home.setSelectedIndex(0)
            home.enterReorderMode()
            home.reorderMoveDown()
            home.confirmReorder()
            loadHome()
            assertEquals(LibraryOrder.MANUAL, home.selectedOrder())
            assertEquals(listOf(played, mario), members())
            suspendCancellableCoroutine<Unit> { done -> model.loadCollectionById(7) { done.resume(Unit) } }
            assertEquals(members(), model.state.value.items)
        } finally { home.close() }
    }

    @Test fun `grouped platform games merge alphabetically with folders and favorites first after live scan`() = runBlocking {
        val alpha = ListItem.RomItem(Rom(4, File("alpha.gbc"), "GBC", "alpha"))
        val zulu = ListItem.RomItem(Rom(5, File("Zulu.gbc"), "GBC", "Zulu"))
        val beta = ListItem.RomItem(Rom(6, File("beta.gbc"), "GBC", "beta"))
        val adventure = ListItem.SubfolderItem("Adventure", "Adventure")
        val rpg = ListItem.SubfolderItem("RPG", "RPG")
        every { roms.gamesForPlatform("GB", any()) } returns listOf(rpg, mario, metroid)
        every { roms.gamesForPlatform("GBC", any()) } returns listOf(adventure, alpha, zulu)
        every { collections.favoriteRomIds() } returns setOf(metroid.rom.id)
        restore(GameListBookmark(platformTag = "GB", platformTags = listOf("GB", "GBC")))
        assertEquals(listOf(adventure, rpg, metroid, alpha, mario, zulu), model.state.value.items)

        every { roms.gamesForPlatform("GBC", any()) } returns listOf(adventure, alpha, beta, zulu)
        scanResults.emit(ScanScheduler.ScanResult("GBC", dev.piannoli.scorza.db.RomScanner.SyncCounts(1, 0, 0)))
        val refreshed = withTimeout(5_000) { model.state.first { beta in it.items } }
        assertEquals(listOf(adventure, rpg, metroid, alpha, beta, mario, zulu), refreshed.items)
    }

    @Test fun `folder artwork revisions refresh cached filtered and complete lists without losing position`() = runBlocking {
        val folder = ListItem.SubfolderItem("Adventure", "RPGs/Adventure")
        every { roms.gamesForPlatform("GB", any()) } returns listOf(folder, mario, metroid)
        restore(GameListBookmark(platformTag = "GB", platformTags = listOf("GB"), search = "Adventure"))
        val shared = File("Art/.folders/shared.png")
        every { roms.refreshFolderArtwork(any(), any()) } answers {
            secondArg<List<ListItem>>().map {
                if (it is ListItem.SubfolderItem) it.copy(artFile = shared) else it
            }
        }
        val before = model.state.value
        folderRevision.value++
        val refreshed = withTimeout(5_000) {
            model.state.first { (it.items.firstOrNull() as? ListItem.SubfolderItem)?.artFile == shared }
        }
        assertEquals(listOf(folder.copy(artFile = shared)), refreshed.items)
        assertEquals(listOf(folder.copy(artFile = shared), mario, metroid), refreshed.allItems)
        assertEquals(before.selectedIndex, refreshed.selectedIndex)
        assertEquals(before.searchTerm, refreshed.searchTerm)
        assertTrue(refreshed.artworkRevision > before.artworkRevision)
        val revision = refreshed.artworkRevision
        folderRevision.value++
        withTimeout(5_000) { model.state.first { it.artworkRevision > revision } }
        Unit
    }

    @Test fun `repeated reloads advance bitmap revision even when the artwork path stays unchanged`() = runBlocking {
        restore(GameListBookmark(platformTag = "GB", platformTags = listOf("GB")))
        repeat(2) {
            val revision = model.state.value.artworkRevision
            suspendCancellableCoroutine<Unit> { continuation -> model.reload { continuation.resume(Unit) } }
            assertTrue(model.state.value.artworkRevision > revision)
        }
    }

    @Test fun `local and global game results clamp repeated and accelerated moves but fresh presses wrap`() = runBlocking {
        every { roms.searchAllGames(any()) } returns listOf(mario.rom, metroid.rom)
        for (global in listOf(false, true)) {
            if (global) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    model.loadGlobalSearch(dev.piannoli.scorza.model.GameSearchQuery("m")) {
                        continuation.resume(Unit)
                    }
                }
            } else {
                restore(GameListBookmark(platformTag = "GB", platformTags = listOf("GB"), search = "m"))
            }
            assertEquals(2, model.state.value.items.size)
            ListNavigation.withRepeat(true) { repeat(5) { model.moveSelection(1) } }
            assertEquals(1, model.state.value.selectedIndex)
            model.moveSelection(1)
            assertEquals(0, model.state.value.selectedIndex)
            ListNavigation.withRepeat(true) { repeat(5) { model.moveSelection(-1) } }
            assertEquals(0, model.state.value.selectedIndex)
            model.moveSelection(-1)
            assertEquals(1, model.state.value.selectedIndex)
            model.moveSelection(-4)
            assertEquals(0, model.state.value.selectedIndex)
        }
    }

    @Test fun `cancel local search restores original folder selection scroll and parents after edits`() = runBlocking {
        val origin = GameListBookmark(
            platformTag = "GB", platformTags = listOf("GB"), folders = listOf("RPGs"),
            folderPositions = listOf(BrowsePosition(index = 1, scroll = 1)),
            position = BrowsePosition("rom:3", 1, 1),
        )
        restore(origin)
        val before = model.bookmark()
        model.beginSearch()
        val keyboard = dev.piannoli.ui.components.KeyboardState(text = "Mario", cursorPos = 2)
        model.setSearch("Mario", keyboard)
        assertEquals(keyboard, model.searchKeyboard())
        model.setSearch("Metroid")
        cancelSearch()
        assertEquals(before, model.bookmark())
        assertEquals(listOf(mario, metroid), model.state.value.items)
        assertEquals("", model.searchKeyboard().text)
        assertTrue(model.exitSubfolder())
        withTimeout(5_000) { model.state.first { it.subfolderPath == null && it.selectedIndex == 1 } }
        Unit
    }

    @Test fun `cancel initial keyboard preserves list position without applying text`() = runBlocking {
        val origin = GameListBookmark(
            platformTag = "GB", platformTags = listOf("GB"), position = BrowsePosition("rom:3", 1, 1),
        )
        restore(origin)
        val before = model.bookmark()
        model.beginSearch()
        cancelSearch()
        assertEquals(before, model.bookmark())
    }

    @Test fun `cancel local Favorites search restores Favorites selection and scroll`() = runBlocking {
        every { collections.byId(1) } returns CollectionsRepository.CollectionRow(
            1, "Favorites", null, 0, CollectionType.FAVORITES,
        )
        every { collections.membersIn(1) } returns listOf(dev.piannoli.scorza.db.LibraryRef.Rom(2), dev.piannoli.scorza.db.LibraryRef.Rom(3))
        every { roms.gameById(2) } returns mario.rom
        every { roms.gameById(3) } returns metroid.rom
        restore(GameListBookmark(collectionId = 1, position = BrowsePosition("rom:3", 1, 1)))
        val before = model.bookmark()
        model.beginSearch()
        model.setSearch("Mario")
        cancelSearch()
        assertEquals(before, model.bookmark())
        assertTrue(model.state.value.isFavorites)
        assertEquals(metroid, model.getSelectedItem())
    }

    @Test fun `editing restored local results does not capture applied filter as origin`() = runBlocking {
        restore(GameListBookmark(platformTag = "GB", platformTags = listOf("GB"), search = "Mario"))
        model.beginSearch()
        cancelSearch()
        assertEquals(null, model.state.value.searchTerm)
        assertEquals(listOf(mario, metroid), model.state.value.items)
    }

    @Test fun `cancel global search restores nested collection and original position across query changes`() = runBlocking {
        every { collections.byId(10) } returns CollectionsRepository.CollectionRow(10, "All games", null, 0, CollectionType.STANDARD)
        every { collections.byId(20) } returns CollectionsRepository.CollectionRow(20, "Handhelds", 10, 0, CollectionType.STANDARD)
        every { collections.membersIn(any()) } returns listOf(dev.piannoli.scorza.db.LibraryRef.Rom(2), dev.piannoli.scorza.db.LibraryRef.Rom(3))
        every { roms.gameById(2) } returns mario.rom
        every { roms.gameById(3) } returns metroid.rom
        every { roms.searchAllGames(any()) } returns listOf(mario.rom)
        val origin = GameListBookmark(
            collectionId = 20, position = BrowsePosition("rom:3", 1, 1),
            collectionParents = listOf(CollectionBrowseParent(10, 1, 1)),
        )
        restore(origin)
        assertEquals("Handhelds", model.state.value.breadcrumb)
        val before = model.bookmark()
        model.beginSearch()
        repeat(2) {
            val keyboard = dev.piannoli.ui.components.KeyboardState(
                text = "  Mario$it ", cursorPos = 3, keyRow = 1, keyCol = 2,
            )
            model.rememberSearchKeyboard(keyboard)
            suspendCancellableCoroutine<Unit> { continuation ->
                model.loadGlobalSearch(dev.piannoli.scorza.model.GameSearchQuery("Mario$it")) {
                    continuation.resume(Unit)
                }
            }
            assertEquals(keyboard, model.searchKeyboard())
        }
        cancelSearch()
        assertEquals(before, model.bookmark())
        assertEquals(metroid, model.getSelectedItem())
        assertTrue(model.exitChildCollection())
        withTimeout(5_000) { model.state.first { it.collectionId == 10L && it.selectedIndex == 1 } }
        Unit
    }

    @Test fun `cancel pending global search prevents late results and completion callback`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        var completed = false
        every { roms.searchAllGames(any()) } answers {
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            finished.countDown()
            listOf(mario.rom)
        }
        restore(GameListBookmark(platformTag = "GB", platformTags = listOf("GB")))
        val before = model.bookmark()
        model.beginSearch()
        val request = model.loadGlobalSearch(dev.piannoli.scorza.model.GameSearchQuery("Mario")) { completed = true }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            cancelSearch()
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        request.join()
        assertEquals(before, model.bookmark())
        assertEquals(false, completed)
    }

    @Test fun `tools preserve pins and favorites groups without displaying favorite stars`() = runBlocking {
        useRealOrdering()
        val pinned = ListItem.AppItem(dev.piannoli.scorza.model.App(
            1, dev.piannoli.scorza.model.AppType.TOOL, "Pinned", "dev.pinned", isPinned = true))
        val other = ListItem.AppItem(dev.piannoli.scorza.model.App(
            2, dev.piannoli.scorza.model.AppType.TOOL, "Favorite", "dev.favorite"))
        val ordinary = ListItem.AppItem(dev.piannoli.scorza.model.App(
            3, dev.piannoli.scorza.model.AppType.TOOL, "Alpha", "dev.alpha"))
        every { apps.all(dev.piannoli.scorza.model.AppType.TOOL) } returns listOf(pinned.app, ordinary.app, other.app)
        every { collections.favoriteAppIds() } returns setOf(2)
        suspendCancellableCoroutine<Unit> { continuation ->
            model.loadApkList("tools", "Apps") { continuation.resume(Unit) }
        }
        assertEquals(listOf(pinned, other, ordinary), model.state.value.items)
        assertTrue(model.state.value.favoriteAppIds.isEmpty())
        model.enterReorderMode()
        model.reorderMoveDown()
        assertEquals(listOf(pinned, other, ordinary), model.state.value.items)
        assertEquals(0, model.state.value.selectedIndex)
        model.setSelectedIndex(1)
        model.reorderMoveDown()
        assertEquals(listOf(pinned, other, ordinary), model.state.value.items)
        assertEquals(1, model.state.value.selectedIndex)
    }

    @Test fun `search preserves exact submitted keyboard text caret and selection`() {
        val keyboard = dev.piannoli.ui.components.KeyboardState(
            text = "  Mario ", cursorPos = 3, keyRow = 1, keyCol = 2, caps = true,
        )
        model.setSearch("Mario", keyboard)
        assertEquals("Mario", model.state.value.searchTerm)
        assertEquals(keyboard, model.searchKeyboard())
        model.clearSearch()
        assertEquals("", model.searchKeyboard().text)
        model.setSearch("Metroid")
        assertEquals("Metroid", model.searchKeyboard().text)
        assertEquals(7, model.searchKeyboard().cursorPos)
    }

    @Test fun `nested folder and parent positions survive recreation with stable game selection`() = runBlocking {
        val path = listOf("RPGs", "English").joinToString(File.separator)
        val bookmark = GameListBookmark(
            platformTag = "GB", platformTags = listOf("GB"), folders = listOf("RPGs", "English"),
            folderPositions = listOf(BrowsePosition(index = 2), BrowsePosition(index = 1, scroll = 1)),
            position = BrowsePosition("rom:3", 0, 1),
        )
        restore(bookmark)
        assertEquals(path, model.state.value.subfolderPath)
        assertEquals("English", model.state.value.breadcrumb)
        assertEquals("Metroid", (model.getSelectedItem() as ListItem.RomItem).rom.displayName)
        assertEquals(1, model.firstVisibleIndex)
        assertEquals(bookmark.folders, model.bookmark().folders)
        assertTrue(model.exitSubfolder())
        withTimeout(5_000) { model.state.first { it.subfolderPath == "RPGs" } }
        assertEquals("RPGs", model.state.value.breadcrumb)
        assertEquals(1, model.state.value.selectedIndex)
        assertTrue(model.exitSubfolder())
        withTimeout(5_000) { model.state.first { it.subfolderPath == null } }
        assertEquals("Game Boy", model.state.value.breadcrumb)
    }

    @Test fun `search and scroll restore from database while excluding transient editing modes`() = runBlocking {
        restore(GameListBookmark(
            platformTag = "GB", platformTags = listOf("GB"), search = "met",
            position = BrowsePosition("rom:3", 99, 99),
        ))
        assertEquals("met", model.state.value.searchTerm)
        assertEquals(listOf(metroid), model.state.value.items)
        assertEquals(0, model.state.value.selectedIndex)
        assertEquals(0, model.state.value.scrollTarget)
        assertEquals(false, model.state.value.multiSelectMode)
        assertEquals(false, model.state.value.reorderMode)
    }

    @Test fun `removed selected game falls back to safe position without blocking return`() = runBlocking {
        restore(GameListBookmark(
            platformTag = "GB", platformTags = listOf("GB"),
            position = BrowsePosition("rom:999", 20, 20),
        ))
        assertEquals(1, model.state.value.selectedIndex)
        assertEquals(1, model.state.value.scrollTarget)
        assertEquals(false, model.state.value.isLoading)
    }

    @Test fun `child collection restores its selected game and Back returns to its parent collection`() = runBlocking {
        every { collections.byId(10) } returns CollectionsRepository.CollectionRow(10, "All games", null, 0, CollectionType.STANDARD)
        every { collections.byId(20) } returns CollectionsRepository.CollectionRow(20, "Handhelds", 10, 0, CollectionType.STANDARD)
        every { collections.membersIn(any()) } returns listOf(dev.piannoli.scorza.db.LibraryRef.Rom(2), dev.piannoli.scorza.db.LibraryRef.Rom(3))
        every { roms.gameById(2) } returns mario.rom
        every { roms.gameById(3) } returns metroid.rom
        restore(GameListBookmark(
            collectionId = 20, position = BrowsePosition("rom:3"),
            collectionParents = listOf(CollectionBrowseParent(10, 1, 1)),
        ))
        assertEquals(20L, model.state.value.collectionId)
        assertEquals(metroid, model.getSelectedItem())
        assertTrue(model.exitChildCollection())
        withTimeout(5_000) { model.state.first { it.collectionId == 10L && it.selectedIndex == 1 } }
        assertEquals("All games", model.state.value.breadcrumb)
    }

    @Test fun `recently played preserves game identity through a reorder both after restart and live reload`() = runBlocking {
        every { roms.gameById(2) } returns mario.rom
        every { roms.gameById(3) } returns metroid.rom
        every { recent.recent(any()) } returns listOf(
            RecentlyPlayedRepository.Entry(LibraryRef.Rom(3), 2),
            RecentlyPlayedRepository.Entry(LibraryRef.Rom(2), 1),
        )
        restore(GameListBookmark(platformTag = "recently_played", position = BrowsePosition("rom:2", 0)))
        assertEquals(mario, model.getSelectedItem())
        every { recent.recent(any()) } returns listOf(
            RecentlyPlayedRepository.Entry(LibraryRef.Rom(2), 3),
            RecentlyPlayedRepository.Entry(LibraryRef.Rom(3), 2),
        )
        suspendCancellableCoroutine<Unit> { continuation -> model.reload { continuation.resume(Unit) } }
        assertEquals(mario, model.getSelectedItem())
        assertEquals(0, model.state.value.selectedIndex)
    }
}
