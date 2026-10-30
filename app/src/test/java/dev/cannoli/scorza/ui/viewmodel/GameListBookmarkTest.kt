package dev.cannoli.scorza.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.LibraryRef
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.BrowsePosition
import dev.cannoli.scorza.navigation.CollectionBrowseParent
import dev.cannoli.scorza.navigation.GameListBookmark
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { platforms.getDisplayName("GB") } returns "Game Boy"
        every { scans.results } returns MutableSharedFlow()
        every { roms.gamesForPlatform("GB", any()) } returns listOf(mario, metroid)
        model = GameListViewModel(
            roms, apps, collections, recent, platforms, scans, mockk(), mockk(relaxed = true),
            ApplicationProvider.getApplicationContext<Context>(),
        )
    }

    @After fun cleanup() {
        model.close()
        Dispatchers.resetMain()
    }

    private suspend fun restore(bookmark: GameListBookmark) = suspendCancellableCoroutine { continuation ->
        model.restoreBookmark(bookmark) { continuation.resume(Unit) }
    }


    @Test fun `search preserves exact submitted keyboard text caret and selection`() {
        val keyboard = dev.cannoli.ui.components.KeyboardState(
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
        assertEquals("Metroid", (model.getSelectedItem() as ListItem.RomItem).rom.displayName)
        assertEquals(1, model.firstVisibleIndex)
        assertEquals(bookmark.folders, model.bookmark().folders)
        assertTrue(model.exitSubfolder())
        withTimeout(5_000) { model.state.first { it.subfolderPath == "RPGs" } }
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
        every { collections.romIdsIn(any()) } returns listOf(2, 3)
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
