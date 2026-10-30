package dev.cannoli.scorza.boot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.navigation.BrowsePosition
import dev.cannoli.scorza.navigation.BrowseSession
import dev.cannoli.scorza.navigation.GameListBookmark
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootInitializerReturnTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nav = NavigationController()
    private val games = mockk<GameListViewModel>(relaxed = true)
    private val systems = mockk<SystemListViewModel>(relaxed = true)
    private val actions = mockk<LauncherActions>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var db: CannoliDatabase
    private lateinit var session: BrowseSession
    private lateinit var initializer: BootInitializer
    private val bookmark = GameListBookmark(platformTag = "GB", platformTags = listOf("GB"), position = BrowsePosition("rom:1"))

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context.getSharedPreferences("browse_session", Context.MODE_PRIVATE).edit().clear().commit()
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns temporaryFolder.root
        every { paths.romDir } returns File(temporaryFolder.root, "Roms").apply { mkdirs() }
        db = CannoliDatabase(paths)
        db.execute("INSERT INTO platforms (tag, display_name) VALUES (?, ?)", "GB", "Game Boy")
        db.execute(
            "INSERT INTO roms (path, platform_tag, display_name, sort_key, name_normalized) VALUES (?, ?, ?, ?, ?)",
            "GB/Game.gb", "GB", "Game", "game", "game",
        )
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.scanLibraryAutomatically } returns true
        every { systems.browsePosition() } returns BrowsePosition("platform:GB")
        every { games.bookmark() } returns bookmark
        every { games.restoreBookmark(bookmark, any()) } answers { secondArg<() -> Unit>().invoke() }
        every { actions.rescanSystemList(any(), any(), any(), any()) } answers {
            arg<(() -> Unit)?>(3)?.invoke()
        }
        session = BrowseSession(context, paths, nav, games, systems)
        initializer = BootInitializer(
            context, settings, mockk<PlatformConfig>(relaxed = true), db, mockk(relaxed = true),
            paths, scope, mockk(relaxed = true), games, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), nav, mockk(relaxed = true), actions,
            mockk(relaxed = true), session,
        )
    }

    @After fun cleanup() {
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    @Test fun `recreated launcher rescans disk then restores game browser before boot completes`() = runBlocking {
        nav.push(LauncherScreen.GameList)
        session.save()
        nav.screenStack.clear()
        nav.push(LauncherScreen.SystemList)
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        assertEquals(LauncherScreen.GameList, nav.currentScreen)
        verify(exactly = 1) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
        verify(exactly = 1) { games.restoreBookmark(bookmark, any()) }
        assertNull(session.pending())
    }

    @Test fun `ordinary cold startup still honors automatic library scanning`() = runBlocking {
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        verify(exactly = 1) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
        verify(exactly = 0) { games.restoreBookmark(any(), any()) }
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
    }
}
