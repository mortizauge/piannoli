package dev.piannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformSeedTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun config(name: String): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, name).apply { mkdirs() }
        return PlatformConfig(root, ctx.assets).also { it.load() }
    }

    private fun pm() = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager

    private fun install(pkg: String) {
        shadowOf(pm()).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        })
    }

    @Test fun `an absent external emulator leaves the platform unset with setup guidance`() {
        val pc = config("seed-int")
        pc.seedUnsetPlatforms(pm())
        assertNull(pc.getPlatformChoice("GBA"))
        assertEquals("RetroArch", pc.getRunnerLabel("GBA"))
        assertEquals(
            dev.piannoli.scorza.ui.screens.EmulatorMappingStatus.NOT_INSTALLED,
            pc.detailedMappingFor("GBA", pm()).status,
        )
        assertEquals(listOf(EmulatorSource.Standalone, EmulatorSource.RetroArch), pc.availableSources("GBA"))
    }

    @Test fun `installed standalones win in curated order over installed RetroArch`() {
        val pc = config("seed-app")
        val apps = pc.getAppOptions("GBA")
        install(apps[1].packageName)
        install(apps[0].packageName)
        install("com.retroarch")
        pc.seedUnsetPlatforms(pm(), mapOf("com.retroarch" to setOf("mgba_libretro")))
        assertEquals(EmulatorChoice(EmulatorSource.Standalone, appPackage = apps[0].packageName), pc.getPlatformChoice("GBA"))
        assertEquals("Standalone", pc.detailedMappingFor("GBA", pm()).runnerLabel)
    }

    @Test fun `an installed configured RetroArch core seeds when no standalone is installed`() {
        val pc = config("seed-ra")
        install("com.retroarch")
        pc.seedUnsetPlatforms(pm(), mapOf("com.retroarch" to setOf("mgba_libretro")))
        assertEquals(EmulatorChoice(EmulatorSource.RetroArch, "mgba_libretro"), pc.getPlatformChoice("GBA"))
        assertNull(pc.getPlatformChoice("PS2"))
    }

    @Test fun `stale reports from an uninstalled RetroArch never seed a default`() {
        val pc = config("seed-stale")
        pc.seedUnsetPlatforms(pm(), mapOf("com.retroarch" to setOf("mgba_libretro")))
        assertNull(pc.getPlatformChoice("GBA"))
    }

    @Test fun `no installed external emulator leaves the platform unset`() {
        val pc = config("seed-none")
        pc.seedUnsetPlatforms(pm())
        assertNull(pc.getPlatformChoice("PS2"))
    }

    // The invariant. Seeding must never touch an existing choice, including one whose target
    // is not installed, which is the case a "repair on boot" implementation would get wrong.
    @Test fun `seeding never overwrites an explicit choice`() {
        val pc = config("seed-keep")
        pc.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.not.installed"))
        val before = pc.getPlatformChoice("GBA")
        pc.seedUnsetPlatforms(pm())
        assertEquals(before, pc.getPlatformChoice("GBA"))
        assertEquals(
            EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.not.installed"),
            pc.getPlatformChoice("GBA"),
        )
    }

    @Test fun `an uninstalled core choice also survives seeding`() {
        val pc = config("seed-keep-core")
        pc.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.RetroArch, "gpsp_libretro"))
        pc.seedUnsetPlatforms(pm())
        assertEquals(
            EmulatorChoice(EmulatorSource.RetroArch, "gpsp_libretro"),
            pc.getPlatformChoice("GBA"),
        )
    }

    @Test fun `seeding is idempotent`() {
        val pc = config("seed-idem")
        install(pc.getAppOptions("GBA").first().packageName)
        val first = pc.seedUnsetPlatforms(pm())
        assertTrue("the first pass must seed something", first > 0)
        assertEquals(0, pc.seedUnsetPlatforms(pm()))
    }

    // Reset resolves the default immediately, so what the user sees after confirming is final
    // rather than something a later boot fills in.
    @Test fun `reset replaces an explicit core with an installed external straight away`() {
        val pc = config("seed-reset")
        install("com.retroarch")
        pc.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.RetroArch, "gpsp_libretro"))
        val restored = pc.resetPlatformToDefault("GBA", pm(), mapOf("com.retroarch" to setOf("mgba_libretro")))
        assertEquals(EmulatorChoice(EmulatorSource.RetroArch, "mgba_libretro"), restored)
        assertEquals(restored, pc.getPlatformChoice("GBA"))
    }

    @Test fun `reset on a platform with no default leaves it unmapped`() {
        val pc = config("seed-reset-none")
        pc.setPlatformChoice("PS2", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.picked.emu"))
        assertNull(pc.resetPlatformToDefault("PS2", pm()))
        assertNull(pc.getPlatformChoice("PS2"))
    }

    @Test fun `reset persists so a reload keeps the restored default`() {
        val pc = config("seed-reset-persist")
        install("com.retroarch")
        pc.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.RetroArch, "gpsp_libretro"))
        pc.resetPlatformToDefault("GBA", pm(), mapOf("com.retroarch" to setOf("mgba_libretro")))
        pc.reloadCoreMappings()
        assertEquals(EmulatorChoice(EmulatorSource.RetroArch, "mgba_libretro"), pc.getPlatformChoice("GBA"))
    }

    @Test fun `installing an external emulator backfills an untouched platform`() {
        val pc = config("seed-later")
        pc.seedUnsetPlatforms(pm())
        assertNull(pc.getPlatformChoice("GBA"))

        install(pc.getAppOptions("GBA").first().packageName)
        assertTrue(pc.seedUnsetPlatforms(pm()) > 0)
        assertEquals(EmulatorSource.Standalone, pc.getPlatformChoice("GBA")?.source)
    }

    @Test fun `a failed load suppresses seeding entirely`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, "seed-torn").apply { mkdirs() }
        File(root, "Config").apply { mkdirs() }.also { File(it, "cores.json").writeText("{ broken") }
        val pc = PlatformConfig(root, ctx.assets).also { it.load() }
        assertEquals(0, pc.seedUnsetPlatforms(pm()))
    }

    @Test fun `a seeded choice persists across a reload`() {
        val pc = config("seed-persist")
        install("com.retroarch")
        pc.seedUnsetPlatforms(pm(), mapOf("com.retroarch" to setOf("mgba_libretro")))
        pc.reloadCoreMappings()
        assertEquals(EmulatorChoice(EmulatorSource.RetroArch, "mgba_libretro"), pc.getPlatformChoice("GBA"))
    }

    @Test fun `legacy stored Built-in choices reload as external auto defaults`() {
        val pc = config("seed-old")
        File(tmp.root, "seed-old/Config/cores.json").writeText("""{"v":2,"platforms":{"GBA":{"source":"Internal","core":"mgba_libretro"}}}""")
        pc.reloadCoreMappings()
        assertNull(pc.getPlatformChoice("GBA"))
        install(pc.getAppOptions("GBA").first().packageName)
        pc.seedUnsetPlatforms(pm())
        assertEquals(EmulatorSource.Standalone, pc.getPlatformChoice("GBA")?.source)
        pc.reloadCoreMappings()
        assertEquals(EmulatorSource.Standalone, pc.getPlatformChoice("GBA")?.source)
    }
}
