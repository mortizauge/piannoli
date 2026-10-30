package dev.piannoli.scorza.scraper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.util.ArtworkLookup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrapedArtworkStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var artwork: ArtworkLookup
    private lateinit var store: ScrapedArtworkStore
    private lateinit var rom: Rom

    @Before fun setup() {
        root = temp.newFolder()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        val paths = PiannoliPathsProvider(settings)
        artwork = ArtworkLookup(paths)
        store = ScrapedArtworkStore(paths, artwork)
        rom = Rom(1, File(root, "Roms/SNES/Game (USA).sfc"), "SNES", "Game")
    }

    private fun png(): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", output)
            output.toByteArray()
        }

    }

    @Test fun `port cover uses sanitized display name and shared Ports lookup with overwrite protection`() {
        val port = dev.piannoli.scorza.model.App(1, dev.piannoli.scorza.model.AppType.PORT,
            "Game: Deluxe", "dev.game.deluxe")
        val basename = dev.piannoli.scorza.romm.download.sanitizeFsName(port.displayName)
        assertNull(artwork.findByName("PORTS", basename))
        val saved = store.installPort(port, png())
        assertEquals(File(File(File(root, "Art"), "PORTS"), "$basename.png"), saved)
        assertEquals(saved, artwork.findByName("PORTS", basename))
        assertEquals(ScraperError.EXISTING_ARTWORK,
            assertThrows(ScraperException::class.java) { store.installPort(port, png()) }.error)
        val old = File(saved.parentFile, "$basename.jpg").apply { writeText("old") }
        store.installPort(port, png(), overwrite = true)
        assertFalse(old.exists())
        assertEquals(saved, artwork.findByName("PORTS", basename))
    }

    @Test fun `cover uses ROM basename and becomes immediately discoverable`() {
        assertNull(artwork.find(rom.platformTag, rom.path))
        val bytes = png()
        val saved = store.install(rom, bytes)
        assertEquals(File(root, "Art/SNES/Game (USA).png"), saved)
        assertArrayEquals(bytes, saved.readBytes())
        assertEquals(saved, artwork.find(rom.platformTag, rom.path))
        assertFalse(saved.parentFile!!.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun `preserves existing art even when it appeared after lookup was cached`() {
        assertNull(artwork.find(rom.platformTag, rom.path))
        val existing = File(root, "Art/SNES/Game (USA).jpg")
        existing.parentFile!!.mkdirs()
        existing.writeText("existing artwork")
        val error = assertThrows(ScraperException::class.java) { store.install(rom, png()) }
        assertEquals(ScraperError.EXISTING_ARTWORK, error.error)
        assertEquals("existing artwork", existing.readText())
        assertFalse(File(root, "Art/SNES/Game (USA).png").exists())
    }

    @Test fun `invalid media leaves library and artwork directory unchanged`() {
        val error = assertThrows(ScraperException::class.java) { store.install(rom, "NOMEDIA".toByteArray()) }
        assertEquals(ScraperError.INVALID_RESPONSE, error.error)
        assertFalse(File(root, "Art").exists())
    }

    @Test fun `overwrite installs new format and removes old artwork only after validation`() {
        val existing = File(root, "Art/SNES/Game (USA).jpg")
        existing.parentFile!!.mkdirs()
        existing.writeText("old art")
        val other = File(existing.parentFile, "Other.jpg").apply { writeText("keep") }
        assertThrows(ScraperException::class.java) { store.install(rom, byteArrayOf(1), overwrite = true) }
        assertEquals("old art", existing.readText())
        val bytes = png()
        val saved = store.install(rom, bytes, overwrite = true)
        assertArrayEquals(bytes, saved.readBytes())
        assertFalse(existing.exists())
        assertEquals("keep", other.readText())
        assertEquals(saved, artwork.find(rom.platformTag, rom.path))
        store.install(rom, bytes, overwrite = true)
        assertArrayEquals(bytes, saved.readBytes())
    }

    @Test fun `JPEG cover retains its format and is immediately discoverable`() {
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", output)
            output.toByteArray()
        }
        val saved = store.install(rom, bytes)
        assertEquals(File(root, "Art/SNES/Game (USA).jpg"), saved)
        assertArrayEquals(bytes, saved.readBytes())
        assertEquals(saved, artwork.find(rom.platformTag, rom.path))
    }

    @Test fun `embedded pico artwork is never replaced`() {
        val pico = rom.copy(path = File(root, "Roms/PICO/Game.p8.png"), platformTag = "PICO")
        val error = assertThrows(ScraperException::class.java) { store.install(pico, png()) }
        assertEquals(ScraperError.EXISTING_ARTWORK, error.error)
        val overwriteError = assertThrows(ScraperException::class.java) { store.install(pico, png(), overwrite = true) }
        assertEquals(ScraperError.EXISTING_ARTWORK, overwriteError.error)
    }

    @Test fun `system resource types are cached separately with bounded raster dimensions`() {
        val grid = dev.piannoli.scorza.settings.SystemArtworkType.GRID
        val logo = dev.piannoli.scorza.settings.SystemArtworkType.LOGO
        val icon = dev.piannoli.scorza.settings.SystemArtworkType.ICON
        store.installSystem("gb", grid, png())
        val gridFile = requireNotNull(SystemArtwork.file(root, "GB", grid))
        val original = gridFile.readBytes()
        val largePng = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(2048, 1024, BufferedImage.TYPE_INT_ARGB), "png", output)
            output.toByteArray()
        }
        store.installSystem("GB", logo, largePng)
        val logoFile = requireNotNull(SystemArtwork.file(root, "GB", logo))
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(logoFile.absolutePath, options)
        assertEquals(1024, options.outWidth)
        assertEquals(512, options.outHeight)
        assertArrayEquals(original, gridFile.readBytes())
        val jpeg = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", output)
            output.toByteArray()
        }
        val iconFile = store.installSystem("GB", icon, jpeg)
        assertEquals(iconFile, SystemArtwork.file(root, "GB", icon))
        assertFalse(gridFile.parentFile!!.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun `deleting system artwork removes only current type and refreshes the display`() {
        val logo = dev.piannoli.scorza.settings.SystemArtworkType.LOGO
        val icon = dev.piannoli.scorza.settings.SystemArtworkType.ICON
        val selected = store.installSystem("GB", logo, png())
        val otherType = store.installSystem("GB", icon, png())
        val otherSystem = store.installSystem("PORTS", logo, png())
        val gameArt = store.install(rom, png())
        val revision = SystemArtwork.revision.value
        SystemArtwork.delete(root, "gb", logo)
        assertFalse(selected.exists())
        org.junit.Assert.assertTrue(otherType.isFile && otherSystem.isFile && gameArt.isFile)
        org.junit.Assert.assertTrue(SystemArtwork.revision.value > revision)
        SystemArtwork.delete(root, "GB", logo)
        assertThrows(java.io.IOException::class.java) { SystemArtwork.delete(root, "../GB", logo) }
    }

    @Test fun `system artwork deletion reports storage failures without changing cache revision`() {
        val type = dev.piannoli.scorza.settings.SystemArtworkType.LOGO
        val target = requireNotNull(SystemArtwork.file(root, "GB", type))
        target.mkdirs()
        File(target, "blocked").writeText("preserve")
        val revision = SystemArtwork.revision.value
        assertThrows(java.io.IOException::class.java) { SystemArtwork.delete(root, "GB", type) }
        assertEquals(revision, SystemArtwork.revision.value)
    }

    @Test fun `invalid system art preserves cache and custom tags are supported without path traversal`() {
        val type = dev.piannoli.scorza.settings.SystemArtworkType.GRID
        store.installSystem("GB", type, png())
        val file = requireNotNull(SystemArtwork.file(root, "GB", type))
        val original = file.readBytes()
        assertThrows(ScraperException::class.java) { store.installSystem("GB", type, byteArrayOf(1)) }
        assertArrayEquals(original, file.readBytes())
        assertNull(SystemArtwork.file(root, "..", type))
        assertThrows(ScraperException::class.java) { store.installSystem("../custom", type, png()) }
        assertEquals(File(File(File(root, "Art"), "Systems"), "custom_grid.png"),
            store.installSystem("CUSTOM", type, png()))
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `local imports accept raster formats and GIF first frame and refresh artwork`() {
        val type = dev.piannoli.scorza.settings.SystemArtworkType.LOGO
        val formats = listOf("png", "jpeg", "gif")
        for (format in formats) {
            val bytes = ByteArrayOutputStream().use { output ->
                ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), format, output)
                output.toByteArray()
            }
            val revision = SystemArtwork.revision.value
            val saved = bytes.inputStream().use { store.importSystem("@favorites", type, it) }
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(saved.absolutePath, options)
            assertEquals(3, options.outWidth)
            assertEquals(2, options.outHeight)
            assertEquals("image/png", options.outMimeType)
            org.junit.Assert.assertTrue(SystemArtwork.revision.value > revision)
        }
        val webp = java.util.Base64.getDecoder().decode(
            "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
        webp.inputStream().use { store.importSystem("@favorites", type, it) }
    }

    @Test fun `local import failures preserve existing artwork and category paths are isolated`() {
        val type = dev.piannoli.scorza.settings.SystemArtworkType.ICON
        val category = png().inputStream().use { store.importSystem("@collection-7", type, it) }
        val original = category.readBytes()
        val revision = SystemArtwork.revision.value
        assertThrows(ScraperException::class.java) {
            byteArrayOf(1, 2).inputStream().use { store.importSystem("@collection-7", type, it) }
        }
        assertThrows(ScraperException::class.java) {
            ByteArray(20 * 1024 * 1024 + 1).inputStream().use { store.importSystem("@collection-7", type, it) }
        }
        assertEquals(revision, SystemArtwork.revision.value)
        assertArrayEquals(original, category.readBytes())
        val system = store.installSystem("COLLECTION-7", type, png())
        assertFalse(system == category)
        assertEquals("Categories", category.parentFile!!.name)
        assertNull(SystemArtwork.file(root, "@../favorites", type))
        SystemArtwork.delete(root, "@collection-7", type)
        org.junit.Assert.assertTrue(system.isFile)
        assertFalse(category.exists())
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `Android icons become discoverable tool and port artwork without replacing existing art`() {
        for (type in dev.piannoli.scorza.model.AppType.entries) {
            val app = dev.piannoli.scorza.model.App(1, type, "Game: Deluxe", "dev.game")
            val tag = if (type == dev.piannoli.scorza.model.AppType.TOOL) "TOOLS" else "PORTS"
            val basename = dev.piannoli.scorza.romm.download.sanitizeFsName(app.displayName)
            assertNull(artwork.findByName(tag, basename))
            val icon = android.graphics.drawable.AdaptiveIconDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK),
                android.graphics.drawable.ColorDrawable(android.graphics.Color.RED),
            )
            icon.setBounds(1, 2, 33, 34)
            val bounds = android.graphics.Rect(icon.bounds)
            val saved = store.installAppIcon(app, icon)
            assertEquals(File(File(File(root, "Art"), tag), "$basename.png"), saved)
            assertEquals(saved, artwork.findByName(tag, basename))
            assertEquals(bounds, icon.bounds)
            val bitmap = android.graphics.BitmapFactory.decodeFile(saved.absolutePath)
            try {
                assertEquals(256, bitmap.width)
                assertEquals(256, bitmap.height)
                assertEquals(android.graphics.Color.RED, bitmap.getPixel(128, 128))
            } finally {
                bitmap.recycle()
            }
            val original = saved.readBytes()
            store.installAppIcon(app, android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE))
            assertArrayEquals(original, saved.readBytes())
            val other = app.copy(displayName = "Custom")
            val custom = File(saved.parentFile, "Custom.jpg").apply { writeText("keep custom artwork") }
            assertEquals(custom, store.installAppIcon(other, icon))
            assertEquals("keep custom artwork", custom.readText())
        }
    }
}
