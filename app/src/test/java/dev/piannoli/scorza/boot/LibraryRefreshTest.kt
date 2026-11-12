package dev.piannoli.scorza.boot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.RomScanner
import dev.piannoli.scorza.db.ScanScheduler
import dev.piannoli.scorza.di.PiannoliPathsProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRefreshTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val paths = mockk<PiannoliPathsProvider>()
    private val platformConfig = mockk<PlatformConfig>()
    private val scheduler = mockk<ScanScheduler>()

    @Before fun clearPendingNotifications() {
        context.getSharedPreferences("library_refresh", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun romDirectory(vararg names: String): File {
        val directory = temporaryFolder.newFolder()
        names.forEach { File(directory, it).mkdirs() }
        every { paths.root } returns temporaryFolder.root
        every { paths.romDir } returns directory
        every { platformConfig.isKnownTag(any()) } answers { firstArg<String>() in setOf("GB", "NES") }
        return directory
    }

    @Test fun `refresh reports additions and removals on separate lines with aliases deduplicated`() = runTest {
        val directory = romDirectory("GB", "Game Boy", ".NES", "Unsupported")
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(2, 5, 3)
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        assertNull("starting the background task must not wait for its disk scan", refresh.result.value)
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertEquals(2, result.newGames)
        assertTrue(result.changed)
        assertEquals(3, result.removedGames)
        assertEquals("2 New Games Found\n3 Games Removed", result.message(context))
        assertTrue(refresh.claimNotification(result.id))
        assertFalse("the same notice must not repeat after an Activity recreation", refresh.claimNotification(result.id))
        verify(exactly = 1) { scheduler.runNowForRefresh("GB") }
        verify(exactly = 1) { scheduler.runNowForRefresh(any()) }
    }

    @Test fun `unchanged and metadata-only refreshes remain silent`() = runTest {
        val directory = romDirectory("GB", "NES")
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(0, 0, 0)
        every { scheduler.runNowForRefresh("NES") } returns RomScanner.SyncCounts(0, 1, 0)
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertEquals(0, result.newGames)
        assertTrue("metadata changes must still update the cached lists", result.changed)
        assertNull(result.message(context))
    }

    @Test fun `removed games notify even without additions`() = runTest {
        val directory = romDirectory("GB")
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(0, 0, 1)
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertEquals(1, result.removedGames)
        assertTrue(result.changed)
        assertEquals("1 Game Removed", result.message(context))
    }

    @Test fun `failed refresh retries on return without overlapping or repeating successful refreshes`() = runTest {
        val directory = romDirectory("GB")
        every { scheduler.runNowForRefresh("GB") } throws IOException("Card read failed")
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        refresh.retryIfFailed()
        runCurrent()
        assertTrue(requireNotNull(refresh.result.value).issue != null)
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(1, 0, 0)
        refresh.retryIfFailed()
        refresh.retryIfFailed()
        refresh.start(paths.root, directory)
        runCurrent()
        assertNull(requireNotNull(refresh.result.value).issue)
        refresh.retryIfFailed()
        runCurrent()
        verify(exactly = 2) { scheduler.runNowForRefresh("GB") }
    }

    @Test fun `completed platform counts survive interruption and are acknowledged across processes`() = runTest {
        val directory = romDirectory("GB", "NES")
        var scanned = 0
        every { scheduler.runNowForRefresh(any()) } answers {
            if (scanned++ == 0) RomScanner.SyncCounts(2, 0, 3)
            else throw CancellationException("Process stopped")
        }
        val interrupted = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        interrupted.start(paths.root, directory)
        runCurrent()
        assertNull(interrupted.result.value)

        every { scheduler.runNowForRefresh(any()) } returns RomScanner.SyncCounts(0, 0, 0)
        val restarted = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        restarted.start(paths.root, directory)
        runCurrent()
        val result = requireNotNull(restarted.result.value)
        assertEquals(2, result.newGames)
        assertEquals(3, result.removedGames)
        assertTrue(restarted.claimNotification(result.id))

        val acknowledged = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        acknowledged.start(paths.root, directory)
        runCurrent()
        assertNull(requireNotNull(acknowledged.result.value).message(context))
    }

    @Test fun `unseen partial failure counts accumulate on retry without double counting`() = runTest {
        val directory = romDirectory("GB", "NES")
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(2, 0, 1)
        every { scheduler.runNowForRefresh("NES") } throws IOException("Card read failed")
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        runCurrent()
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(0, 0, 0)
        every { scheduler.runNowForRefresh("NES") } returns RomScanner.SyncCounts(1, 0, 0)
        refresh.retryIfFailed()
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertEquals(3, result.newGames)
        assertEquals(1, result.removedGames)
        assertNull(result.issue)
    }

    @Test fun `pending counts stay scoped to their library`() = runTest {
        val directory = romDirectory("GB")
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(2, 0, 1)
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        runCurrent()
        val other = temporaryFolder.newFolder()
        every { paths.romDir } returns other
        val restarted = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        restarted.start(paths.root, other)
        runCurrent()
        assertNull(requireNotNull(restarted.result.value).message(context))
        every { paths.romDir } returns directory
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(0, 0, 0)
        restarted.start(paths.root, directory)
        runCurrent()
        assertEquals(2, requireNotNull(restarted.result.value).newGames)
        assertEquals(1, requireNotNull(restarted.result.value).removedGames)
    }

    @Test fun `retry does not repeat counts already notified before a partial failure`() = runTest {
        val directory = romDirectory("GB", "NES")
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(2, 0, 1)
        every { scheduler.runNowForRefresh("NES") } throws IOException("Card read failed")
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        runCurrent()
        assertTrue(refresh.claimNotification(requireNotNull(refresh.result.value).id))
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(0, 0, 0)
        every { scheduler.runNowForRefresh("NES") } returns RomScanner.SyncCounts(1, 0, 0)
        refresh.retryIfFailed()
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertEquals(1, result.newGames)
        assertEquals(0, result.removedGames)
        assertNull(result.issue)
    }

    @Test fun `retry retains unseen metadata changes for the foreground list reload`() = runTest {
        val directory = romDirectory("GB", "NES")
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(0, 1, 0)
        every { scheduler.runNowForRefresh("NES") } throws IOException("Card read failed")
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        runCurrent()
        every { scheduler.runNowForRefresh(any()) } returns RomScanner.SyncCounts(0, 0, 0)
        refresh.retryIfFailed()
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertTrue(result.changed)
        assertNull(result.message(context))
    }

    @Test fun `partial failures report the issue and games found on other platforms`() = runTest {
        val directory = romDirectory("GB", "NES")
        every { scheduler.runNowForRefresh("GB") } throws IOException("Card read failed")
        every { scheduler.runNowForRefresh("NES") } returns RomScanner.SyncCounts(1, 0, 0)
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertEquals(1, result.newGames)
        assertTrue(result.changed)
        val message = requireNotNull(result.message(context))
        assertTrue(message.contains("1 New Game Found"))
        assertTrue(message.contains("Could Not Refresh GB"))
    }

    @Test fun `an unreadable library reports an issue instead of a successful empty refresh`() = runTest {
        val missing = File(romDirectory(), "Missing")
        every { paths.romDir } returns missing
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, missing)
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertEquals(0, result.newGames)
        assertFalse(result.changed)
        assertTrue(requireNotNull(result.message(context)).contains("Cannot Read the ROM Directory"))
        verify(exactly = 0) { scheduler.runNowForRefresh(any()) }
    }

    @Test fun `warm starts reuse the process task without another scan or notification`() = runTest {
        val directory = romDirectory("GB")
        every { scheduler.runNowForRefresh("GB") } returns RomScanner.SyncCounts(1, 0, 0)
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        refresh.start(paths.root, directory)
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertTrue(refresh.claimNotification(result.id))
        refresh.start(paths.root, directory)
        runCurrent()
        assertEquals(result, refresh.result.value)
        assertFalse(refresh.claimNotification(result.id))
        verify(exactly = 1) { scheduler.runNowForRefresh("GB") }
    }

    @Test fun `a library switch discards the old scan result before it can notify`() = runTest {
        val directory = romDirectory("GB")
        val other = temporaryFolder.newFolder()
        every { scheduler.runNowForRefresh("GB") } answers {
            every { paths.romDir } returns other
            RomScanner.SyncCounts(1, 0, 0)
        }
        val refresh = LibraryRefresh(context, paths, platformConfig, scheduler, this)
        refresh.start(paths.root, directory)
        runCurrent()
        assertNull(refresh.result.value)
        refresh.start(paths.root, other)
        runCurrent()
        val result = requireNotNull(refresh.result.value)
        assertEquals(other.absolutePath, result.romDirectory)
        assertNull(result.message(context))
    }
}
