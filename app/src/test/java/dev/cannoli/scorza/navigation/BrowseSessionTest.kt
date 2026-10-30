package dev.cannoli.scorza.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val paths = mockk<CannoliPathsProvider>()
    private val games = mockk<GameListViewModel>()
    private val systems = mockk<SystemListViewModel>(relaxed = true)
    private val nav = NavigationController()
    private val position = BrowsePosition("platform:GB", 4, 2)
    private val game = GameListBookmark(
        platformTag = "GB", platformTags = listOf("GB"), search = "Mario",
        position = BrowsePosition("rom:12", 3, 1),
        folders = listOf("RPGs", "English"),
        folderPositions = listOf(BrowsePosition(index = 2), BrowsePosition(index = 5, scroll = 3)),
        collectionParents = listOf(CollectionBrowseParent(15, 2, 1)),
    )

    @Before fun setup() {
        context.getSharedPreferences("browse_session", Context.MODE_PRIVATE).edit().clear().commit()
        every { paths.root } returns File(context.cacheDir, "library")
        every { paths.romDir } returns File(context.cacheDir, "library\\Roms")
        every { systems.browsePosition() } returns position
        every { games.bookmark() } returns game
    }

    @Test fun `game browsing survives new controller and navigation instances without saving game lists`() {
        nav.push(LauncherScreen.GameList)
        BrowseSession(context, paths, nav, games, systems).save()
        val restoredNav = NavigationController()
        val session = BrowseSession(context, paths, restoredNav, games, systems)
        val saved = session.pending()!!
        assertEquals(game, saved.game)
        assertEquals(position, saved.system)
        var completed = false
        every { games.restoreBookmark(game, any()) } answers {
            assertEquals(listOf(LauncherScreen.SystemList), restoredNav.screenStack.toList())
            secondArg<() -> Unit>().invoke()
        }
        session.restore(saved) { completed = true }
        assertEquals(true, completed)
        assertEquals(listOf(LauncherScreen.SystemList, LauncherScreen.GameList), restoredNav.screenStack.toList())
        verify { systems.restoreBrowsePosition(position) }
        assertNull(session.pending())
    }

    @Test fun `system list game launch preserves selection without creating a game list`() {
        val session = BrowseSession(context, paths, nav, games, systems)
        session.save()
        val saved = session.pending()!!
        assertNull(saved.game)
        session.restore(saved) {}
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
        verify(exactly = 0) { games.restoreBookmark(any(), any()) }
        verify { systems.restoreBrowsePosition(position) }
    }

    @Test fun `position from another ROM directory is discarded`() {
        val session = BrowseSession(context, paths, nav, games, systems)
        session.save()
        assertNotNull(session.pending())
        every { paths.romDir } returns File(context.cacheDir, "other")
        assertNull(session.pending())
        every { paths.romDir } returns File(context.cacheDir, "library\\Roms")
        assertNull(session.pending())
    }

    @Test fun `corrupt bookmark is discarded rather than blocking startup`() {
        context.getSharedPreferences("browse_session", Context.MODE_PRIVATE)
            .edit().putString("bookmark", "{invalid").commit()
        val session = BrowseSession(context, paths, nav, games, systems)
        assertNull(session.pending())
        assertNull(session.pending())
    }
}
