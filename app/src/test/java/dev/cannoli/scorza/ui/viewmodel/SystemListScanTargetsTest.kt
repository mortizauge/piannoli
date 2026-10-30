package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.scanner.RomDirectoryWatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Which platforms a disk scan visits. A platform that still holds rows but has lost its folder is
 *  only reachable through the reconcile, and only [RomScanner.scanPlatform] clears it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemListScanTargetsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() {
        created.forEach { it.close() }
        created.clear()
        Dispatchers.resetMain()
    }

    private lateinit var romScanner: RomScanner
    private lateinit var romsRepository: RomsRepository
    private val created = mutableListOf<SystemListViewModel>()

    private fun viewModel(romDir: File, indexed: Map<String, Int>, filters: Map<String, Set<String>> = emptyMap()): SystemListViewModel {
        romScanner = mockk(relaxed = true)
        romsRepository = mockk(relaxed = true)
        every { romsRepository.platformCounts() } returns indexed
        every { romsRepository.knownPlatformTags() } returns indexed.keys.toList()

        val paths = mockk<CannoliPathsProvider>()
        every { paths.filteredExtensions(any()) } answers { filters[firstArg()] ?: emptySet() }
        every { paths.romDir } returns romDir

        val platformConfig = mockk<PlatformConfig>(relaxed = true)
        every { platformConfig.isKnownTag(any()) } answers { firstArg<String>() in KNOWN }
        every { platformConfig.isArcade(any()) } returns false
        every { platformConfig.resolvePlatform(any(), any(), any()) } answers {
            val tag = firstArg<String>()
            Platform(tag = tag, displayName = tag, coreName = null, gameCount = thirdArg())
        }

        val scheduler = mockk<ScanScheduler>(relaxed = true)
        every { scheduler.results } returns MutableSharedFlow()

        return SystemListViewModel(
            romsRepository = romsRepository,
            romScanner = romScanner,
            appsRepository = mockk(relaxed = true),
            collectionsRepository = mockk(relaxed = true),
            recentlyPlayedRepository = mockk(relaxed = true),
            platformConfig = platformConfig,
            cannoliPaths = paths,
            romDirectoryWatcher = mockk<RomDirectoryWatcher>(relaxed = true),
            scanScheduler = scheduler,
        ).also { created.add(it) }
    }

    /** The tags handed to the scanner, in call order. */
    private fun scannedTags(vm: SystemListViewModel, reconcileOrphans: Boolean): List<String> {
        val tags = mutableListOf<String>()
        val tag = slot<String>()
        every { romScanner.scanPlatform(capture(tag), any()) } answers {
            tags.add(tag.captured)
            RomScanner.SyncCounts(0, 0, 0)
        }
        val done = CountDownLatch(1)
        vm.scan(reconcileOrphans = reconcileOrphans, onReady = { done.countDown() })
        assertTrue("scan did not finish", done.await(10, TimeUnit.SECONDS))
        return tags
    }

    @Test fun `reconcile visits a platform that lost its folder`() {
        val romDir = tmp.newFolder("Roms")
        File(romDir, "GB").mkdirs()

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1, "SNES" to 4))
        val scanned = scannedTags(vm, reconcileOrphans = true)

        assertEquals(listOf("GB", "SNES"), scanned.sorted())
    }

    @Test fun `scan discovers alias folders and visits canonical system once`() {
        val romDir = tmp.newFolder("Roms")
        listOf("PS", "Ps1", "psx", "playstation", "PlayStation backup").forEach { File(romDir, it).mkdirs() }
        val vm = viewModel(romDir, indexed = mapOf("PS" to 4))
        assertEquals(listOf("PS"), scannedTags(vm, reconcileOrphans = false))
    }

    @Test fun `a fully filtered platform stays visible so exclusions can be undone`() {
        val romDir = tmp.newFolder("Roms")
        File(romDir, "GB").mkdirs()
        val vm = viewModel(romDir, indexed = mapOf("GB" to 0), filters = mapOf("GB" to setOf("gb", "zip")))
        val done = CountDownLatch(1)
        vm.scan(scanDisk = false, onReady = { done.countDown() })
        assertTrue(done.await(10, TimeUnit.SECONDS))
        val platforms = vm.state.value.items.filterIsInstance<SystemListViewModel.ListItem.PlatformItem>()
        assertEquals(listOf("GB"), platforms.map { it.platform.tag })
    }

    @Test fun `an ordinary scan leaves an orphaned platform alone`() {
        val romDir = tmp.newFolder("Roms")
        File(romDir, "GB").mkdirs()

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1, "SNES" to 4))
        val scanned = scannedTags(vm, reconcileOrphans = false)

        assertEquals(listOf("GB"), scanned)
    }

    @Test fun `a missing rom directory clears nothing`() {
        val romDir = File(tmp.root, "NotMounted")

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1, "SNES" to 4))
        val scanned = scannedTags(vm, reconcileOrphans = true)

        assertEquals(emptyList<String>(), scanned)
    }

    @Test fun `declining the reconcile leaves the orphaned rows in place`() {
        val romDir = tmp.newFolder("Roms")
        File(romDir, "GB").mkdirs()

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1, "SNES" to 4))
        assertEquals(listOf("GB"), scannedTags(vm, reconcileOrphans = false))
        assertEquals(listOf("GB", "SNES"), scannedTags(vm, reconcileOrphans = true).sorted())
    }

    @Test fun `a folder that is not a platform is never scanned`() {
        val romDir = tmp.newFolder("Roms")
        File(romDir, "GB").mkdirs()
        File(romDir, "#recycle").mkdirs()

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1))
        val scanned = scannedTags(vm, reconcileOrphans = true)

        assertEquals(listOf("GB"), scanned)
    }

    @Test fun `saved platform identity and viewport survive recreation and changed list ordering`() {
        val romDir = tmp.newFolder("Roms")
        val original = viewModel(romDir, indexed = linkedMapOf("GB" to 2, "SNES" to 4))
        fun loadIndex(vm: SystemListViewModel) {
            val done = CountDownLatch(1)
            vm.scan(scanDisk = false, onReady = { done.countDown() })
            assertTrue(done.await(10, TimeUnit.SECONDS))
        }
        loadIndex(original)
        val gbIndex = original.state.value.items.indexOfFirst {
            it is SystemListViewModel.ListItem.PlatformItem && it.platform.tag == "GB"
        }
        assertTrue(gbIndex >= 0)
        original.setSelectedIndex(gbIndex)
        original.firstVisibleIndex = 1
        val saved = original.browsePosition()
        val restored = viewModel(romDir, indexed = linkedMapOf("NES" to 1, "GB" to 9, "SNES" to 4))
        loadIndex(restored)
        restored.restoreBrowsePosition(saved)
        assertEquals("GB", restored.getSelectedPlatformTag())
        assertEquals(1, restored.firstVisibleIndex)
        verify(exactly = 0) { romScanner.scanPlatform(any(), any()) }
    }

    private companion object {
        val KNOWN = setOf("GB", "SNES", "NES", "PS")
    }
}
