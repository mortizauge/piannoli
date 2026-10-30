package dev.piannoli.scorza.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformFileTypesTest {
    @Test fun `every bundled system has a restrictive file filter`() {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val platforms = JSONObject(assets.open("platforms.json").bufferedReader().use { it.readText() })
        for (tag in platforms.keys()) {
            assertFalse("$tag must not accept arbitrary files", PlatformFileTypes.accepts(tag, File("anything.notarom")))
        }
    }

    @Test fun `systems sharing a core do not inherit each others file formats`() {
        for ((tag, extension) in listOf(
            "GB" to "gb", "GBC" to "gbc", "GBA" to "gba",
            "GG" to "gg", "SMS" to "sms", "MD" to "md", "SG1000" to "sg",
            "NES" to "nes", "FDS" to "fds", "NGP" to "ngp", "NGPC" to "ngc",
            "WS" to "ws", "WSC" to "wsc",
        )) {
            assertTrue("$tag accepts $extension", PlatformFileTypes.accepts(tag, File("Game.$extension")))
            assertFalse("$tag rejects unrelated disc images", PlatformFileTypes.accepts(tag, File("Other.iso")))
        }
        assertFalse(PlatformFileTypes.accepts("GB", File("Color.gbc")))
        assertFalse(PlatformFileTypes.accepts("SMS", File("Game.gg")))
        assertFalse(PlatformFileTypes.accepts("NES", File("Game.fds")))
    }

    @Test fun `launcher shortcut formats remain usable only for their systems`() {
        for (extension in listOf("steam", "steamappid", "epic", "gog", "amazon", "pcgame", "localgameid", "txt")) {
            assertTrue(PlatformFileTypes.accepts("PC", File("Game.$extension")))
            assertFalse(PlatformFileTypes.accepts("SNES", File("Game.$extension")))
        }
        assertTrue(PlatformFileTypes.accepts("PSVITA", File("Game.psvita")))
        assertFalse(PlatformFileTypes.accepts("PSVITA", File("Manual.txt")))
    }
}
