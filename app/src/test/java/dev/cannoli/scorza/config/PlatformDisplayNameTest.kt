package dev.cannoli.scorza.config

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformDisplayNameTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newConfig(): PlatformConfig {
        val assets = RuntimeEnvironment.getApplication().assets
        return PlatformConfig(tempFolder.root, assets)
    }

    @Test fun `common folder names resolve to canonical systems with inherited launch configuration`() {
        val config = newConfig()
        val variations = mapOf(
            "Ps1" to "PS", "psx" to "PS", "Sony - PlayStation" to "PS",
            "playstation_2" to "PS2", "Nintendo GameCube" to "GC",
            "Mega Drive" to "MD", "sega_genesis" to "MD", "Super-Famicom" to "SNES",
            "game_boy_advance" to "GBA", "nintendo-switch" to "NSW",
        )
        for ((folder, tag) in variations) {
            assertEquals(tag, PlatformFolders.canonicalTag(folder))
            assertEquals(true, config.isKnownTag(folder))
            val resolved = config.resolvePlatform(folder, tempFolder.root, 2)
            assertEquals(tag, resolved.tag)
            assertEquals(config.getDisplayName(tag), resolved.displayName)
            assertEquals(config.getCoreName(tag), resolved.coreName)
        }
        assertEquals(false, config.isKnownTag("PlayStation backup"))
        assertEquals("PS2", PlatformFolders.canonicalTag("PS2"))
    }

    @Test fun `rename platform to its uppercase tag is kept`() {
        val config = newConfig()
        config.setDisplayName("NGPC", "NGPC")
        assertEquals("NGPC", config.getDisplayName("NGPC"))
    }

    @Test fun `rename game boy to GB is kept`() {
        val config = newConfig()
        config.setDisplayName("GB", "GB")
        assertEquals("GB", config.getDisplayName("GB"))
    }

    @Test fun `rename to the default name clears the override`() {
        val config = newConfig()
        config.setDisplayName("NGPC", "My NGPC")
        assertEquals("My NGPC", config.getDisplayName("NGPC"))
        config.setDisplayName("NGPC", "Neo Geo Pocket Color")
        assertEquals("Neo Geo Pocket Color", config.getDisplayName("NGPC"))
    }

    @Test fun `rename to a custom name is kept`() {
        val config = newConfig()
        config.setDisplayName("NGPC", "ngpc")
        assertEquals("ngpc", config.getDisplayName("NGPC"))
    }

    @Test fun `blank name resets to the default`() {
        val config = newConfig()
        config.setDisplayName("NGPC", "My NGPC")
        assertEquals("My NGPC", config.getDisplayName("NGPC"))
        config.setDisplayName("NGPC", "")
        assertEquals("Neo Geo Pocket Color", config.getDisplayName("NGPC"))
    }

    @Test fun `first rename with lowercase tag is visible immediately and survives reload`() {
        val config = newConfig()
        config.load()
        config.setDisplayName("gb", "Handheld")
        assertEquals("Handheld", config.resolvePlatform("GB", tempFolder.root, 1).displayName)
        config.load()
        assertEquals("Handheld", config.getDisplayName("GB"))
        config.setDisplayName("gb", "")
        assertEquals("Game Boy", config.getDisplayName("GB"))
    }
}
