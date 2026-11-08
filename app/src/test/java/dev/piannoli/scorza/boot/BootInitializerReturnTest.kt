package dev.piannoli.scorza.boot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.PiannoliDatabase
import dev.piannoli.scorza.db.execute
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.navigation.BrowsePosition
import dev.piannoli.scorza.navigation.BrowseSession
import dev.piannoli.scorza.navigation.GameListBookmark
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.navigation.ProcessSession
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
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
    private lateinit var db: PiannoliDatabase
    private lateinit var session: BrowseSession
    private lateinit var initializer: BootInitializer
    private val processSession = ProcessSession()
    private val paths = mockk<PiannoliPathsProvider>()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val bookmark = GameListBookmark(platformTag = "GB", platformTags = listOf("GB"), position = BrowsePosition("rom:1"))

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context.getSharedPreferences("browse_session", Context.MODE_PRIVATE).edit().clear().commit()
        every { paths.root } returns temporaryFolder.root
        every { paths.romDir } returns File(temporaryFolder.root, "Roms").apply { mkdirs() }
        db = PiannoliDatabase(paths)
        db.execute("INSERT INTO platforms (tag, display_name) VALUES (?, ?)", "GB", "Game Boy")
        db.execute(
            "INSERT INTO roms (path, platform_tag, display_name, sort_key, name_normalized) VALUES (?, ?, ?, ?, ?)",
            "GB/Game.gb", "GB", "Game", "game", "game",
        )
        every { settings.scanLibraryAutomatically } returns true
        every { systems.browsePosition() } returns BrowsePosition("platform:GB")
        every { games.bookmark() } returns bookmark
        every { games.restoreBookmark(bookmark, any()) } answers { secondArg<() -> Unit>().invoke() }
        every { actions.rescanSystemList(any(), any(), any(), any()) } answers {
            arg<(() -> Unit)?>(3)?.invoke()
        }
        session = BrowseSession(processSession, paths, nav, games, systems)
        initializer = newInitializer(processSession, session)
    }

    private fun newInitializer(process: ProcessSession, browse: BrowseSession) =
        BootInitializer(
            context, settings, mockk<PlatformConfig>(relaxed = true), db, mockk(relaxed = true),
            paths, scope, mockk(relaxed = true), games, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), nav, mockk(relaxed = true), actions,
            mockk(relaxed = true), browse, process,
        )

    @After fun cleanup() {
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    @Test fun `same process recreation restores game browser without another disk scan`() = runBlocking {
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        nav.push(LauncherScreen.GameList)
        session.save()
        nav.screenStack.clear()
        nav.push(LauncherScreen.SystemList)
        val recreated = newInitializer(processSession, BrowseSession(processSession, paths, nav, games, systems))
        assertEquals(BootResult.Success, recreated.run { _, _, _ -> })
        assertEquals(LauncherScreen.GameList, nav.currentScreen)
        verify(exactly = 1) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
        verify(exactly = 1) { actions.rescanSystemList(scanDisk = false, onProgress = any(), onComplete = any()) }
        verify(exactly = 1) { games.restoreBookmark(bookmark, any()) }
        assertNull(session.pending())
    }

    @Test fun `ordinary cold startup still honors automatic library scanning`() = runBlocking {
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        verify(exactly = 1) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
        verify(exactly = 0) { games.restoreBookmark(any(), any()) }
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
    }

    @Test fun `process death triggers a new automatic scan and discards the previous game location`() = runBlocking {
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        nav.push(LauncherScreen.GameList)
        session.save()
        val newProcess = ProcessSession()
        val cold = newInitializer(newProcess, BrowseSession(newProcess, paths, nav, games, systems))
        assertEquals(BootResult.Success, cold.run { _, _, _ -> })
        verify(exactly = 2) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
        verify(exactly = 0) { games.restoreBookmark(any(), any()) }
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
    }

    @Test fun `manual scan mode remains manual across cold and warm initialization`() = runBlocking {
        every { settings.scanLibraryAutomatically } returns false
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        verify(exactly = 0) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
        verify(exactly = 2) { actions.rescanSystemList(scanDisk = false, onProgress = any(), onComplete = any()) }
    }

    @Test fun `changing ROM directories invalidates both scan and navigation reuse`() = runBlocking {
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        nav.push(LauncherScreen.GameList)
        session.save()
        every { paths.romDir } returns File(temporaryFolder.root, "OtherRoms").apply { mkdirs() }
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        verify(exactly = 2) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
        verify(exactly = 0) { games.restoreBookmark(any(), any()) }
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
        assertNull(session.pending())
    }

    @Test fun `overlapping activity initialization scans only once after successful completion`() = runBlocking {
        val completions = Channel<() -> Unit>(Channel.UNLIMITED)
        every { actions.rescanSystemList(any(), any(), any(), any()) } answers {
            check(completions.trySend(requireNotNull(arg<(() -> Unit)?>(3))).isSuccess)
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { initializer.run { _, _, _ -> } }
        val finishFirst = completions.receive()
        val recreated = newInitializer(processSession, session)
        val second = async(start = CoroutineStart.UNDISPATCHED) { recreated.run { _, _, _ -> } }
        verify(exactly = 1) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
        verify(exactly = 0) { actions.rescanSystemList(scanDisk = false, onProgress = any(), onComplete = any()) }
        finishFirst()
        assertEquals(BootResult.Success, first.await())
        completions.receive().invoke()
        assertEquals(BootResult.Success, second.await())
        completions.close()
        verify(exactly = 1) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
        verify(exactly = 1) { actions.rescanSystemList(scanDisk = false, onProgress = any(), onComplete = any()) }
    }

    @Test fun `cancelled initialization does not suppress scanning on retry`() = runBlocking {
        val completion = CompletableDeferred<() -> Unit>()
        every { actions.rescanSystemList(any(), any(), any(), any()) } answers {
            completion.complete(requireNotNull(arg<(() -> Unit)?>(3)))
        }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { initializer.run { _, _, _ -> } }
        val finishCancelled = completion.await()
        cancelled.cancel()
        cancelled.join()
        finishCancelled()
        org.junit.Assert.assertFalse(processSession.isInitialized(paths.root.absolutePath, paths.romDir.absolutePath))
        every { actions.rescanSystemList(any(), any(), any(), any()) } answers {
            arg<(() -> Unit)?>(3)?.invoke()
        }
        assertEquals(BootResult.Success, initializer.run { _, _, _ -> })
        verify(exactly = 2) { actions.rescanSystemList(scanDisk = true, onProgress = any(), onComplete = any()) }
    }
}
