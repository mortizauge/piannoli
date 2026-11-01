package dev.piannoli.scorza.launcher

import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.config.EmulatorChoice
import dev.piannoli.scorza.config.EmulatorSource
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchManagerSlotTest {

    @get:Rule val tmp = TemporaryFolder()
    private val platformConfig = mockk<PlatformConfig>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val retroArchLauncher = mockk<RetroArchLauncher>(relaxed = true)

    private fun rom(root: File): Rom {
        val romFile = File(root, "roms/snes/Zelda.sfc").apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(
            id = 1L,
            path = romFile,
            platformTag = "snes",
            displayName = "Zelda",
        )
    }

    private fun manager(root: File): LaunchManager {
        every { settings.sdCardRoot } returns root.absolutePath
        every { settings.retroArchPackage } returns "dev.piannoli.ricotta"
        every { platformConfig.getPlatformChoice(any()) } returns null
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns null
        every { platformConfig.getCoreName(any()) } returns "snes9x_libretro"
        val activeMapping = mockk<dev.piannoli.scorza.input.runtime.ActiveMappingHolder>(relaxed = true)
        every { activeMapping.active } returns MutableStateFlow<dev.piannoli.scorza.input.DeviceMapping?>(null)
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = platformConfig,
            retroArchLauncher = retroArchLauncher,
            emuLauncher = mockk(relaxed = true),
            apkLauncher = mockk(relaxed = true),
            delfinoLauncher = mockk(relaxed = true),
            launchState = mockk(relaxed = true),
            activeMappingHolder = activeMapping,
        )
    }

    @Test
    fun slotOccupancy_reflects_existing_state_files() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val r = rom(root)
        val base = mgr.saveStateBasePath(r)
        // slot index 1 == base with no suffix; slot index 3 == base + "2"
        File(base).apply { parentFile!!.mkdirs(); writeText("s") }
        File(base + "2").writeText("s")

        val occ = mgr.slotOccupancy(r)
        assertEquals(11, occ.size)
        assertTrue(occ[1])
        assertTrue(occ[3])
        assertEquals(false, occ[2])
        assertEquals(false, occ[0])
    }

    @Test fun `external resume ignores built-in states and isolates package and core`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val r = rom(root)
        val builtin = dev.piannoli.scorza.config.PiannoliPaths(root).saveStateBase(r.platformTag, r.path.nameWithoutExtension).absolutePath
        File(builtin).apply { parentFile!!.mkdirs(); writeText("built-in state") }
        every { platformConfig.getPlatformChoice(any()) } returns EmulatorChoice(EmulatorSource.RetroArch, "snes9x_libretro")
        val external = mgr.saveStateBasePath(r)
        assertFalse(builtin == external)
        assertNull(mgr.findMostRecentSlot(r))
        assertTrue(mgr.findResumableRoms(listOf(r)).isEmpty())

        File(external).apply { parentFile!!.mkdirs(); writeText("external state") }
        assertEquals(setOf(r.path.absolutePath), mgr.findResumableRoms(listOf(r)))
        every { platformConfig.getCoreName(any()) } returns "bsnes_libretro"
        assertNull(mgr.findMostRecentSlot(r))
        every { platformConfig.getCoreName(any()) } returns "snes9x_libretro"
        every { settings.retroArchPackage } returns "com.retroarch"
        assertNull(mgr.findMostRecentSlot(r))
        assertTrue(File(builtin).exists())
        assertTrue(File(external).exists())
    }

    @Test fun `standalone auto routing and stock RetroArch never advertise libretro Resume`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val r = rom(root)
        File(mgr.saveStateBasePath(r)).apply { parentFile!!.mkdirs(); writeText("external state") }
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns dev.piannoli.scorza.config.AppConfig("com.snes.app")
        assertTrue(mgr.findResumableRoms(listOf(r)).isEmpty())
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns null
        every { settings.retroArchPackage } returns "com.retroarch"
        File(mgr.saveStateBasePath(r)).apply { parentFile!!.mkdirs(); writeText("stock state") }
        assertTrue(mgr.findResumableRoms(listOf(r)).isEmpty())
    }

    @Test fun `Ricotta launch and resume agree on external states and retain battery save sync layout`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val r = rom(root)
        every { settings.alwaysSaveOnQuit } returns true
        every { retroArchLauncher.launchRicotta(any(), any(), any(), any(), any()) } returns LaunchResult.Success
        val cfg = slot<String>()
        val igm = slot<RicottaIgm>()
        assertNull(mgr.launchRom(r))
        verify { retroArchLauncher.launchRicotta(r.path, "snes9x_libretro", capture(cfg), "dev.piannoli.ricotta", capture(igm)) }
        val text = File(cfg.captured).readText()
        assertTrue(text.contains("savefile_directory = \"${root.absolutePath}/Saves\""))
        assertTrue(text.contains("sort_savefiles_enable = \"false\""))
        assertTrue(text.contains("savestate_directory = \"${File(mgr.saveStateBasePath(r)).parent}\""))
        assertTrue(text.contains("savestate_auto_save = \"true\""))
        assertEquals(mgr.saveStateBasePath(r), igm.captured.stateBasePath)
        assertNull(mgr.resumeRom(r, 3))
        val resumed = File(cfg.captured).readText()
        assertTrue(resumed.contains("state_slot = \"2\""))
        assertTrue(resumed.contains("savestate_auto_load = \"true\""))
    }

    @Test fun `an unwritable external launch config reports failure instead of using unrelated states`() {
        val root = tmp.newFolder()
        val manager = manager(root)
        val rom = rom(root)
        dev.piannoli.scorza.config.PiannoliPaths(root).raLaunchCfg.mkdirs()
        assertTrue(manager.launchRom(rom) is dev.piannoli.scorza.ui.screens.DialogState.LaunchError)
        assertTrue(manager.resumeRom(rom, 3) is dev.piannoli.scorza.ui.screens.DialogState.LaunchError)
        verify(exactly = 0) { retroArchLauncher.launchRicotta(any(), any(), any(), any(), any()) }
    }
}
