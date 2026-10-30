package dev.cannoli.scorza.scraper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.cannoli.scorza.settings.SystemArtworkType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import kotlin.math.max

object SystemArtwork {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    private fun sourceName(tag: String): String? {
        val upper = tag.uppercase(Locale.ROOT)
        if (!upper.matches(Regex("[A-Z0-9][A-Z0-9_-]*"))) return null
        return names[upper] ?: upper.lowercase(Locale.ROOT)
    }

    fun file(root: File, tag: String, type: SystemArtworkType): File? {
        val category = tag.removePrefix("@").takeIf {
            tag.startsWith("@") && (it in setOf("recent", "favorites", "collections", "tools") ||
                it.matches(Regex("collection-[1-9][0-9]*")))
        }
        val name = category ?: sourceName(tag) ?: return null
        val directory = File(File(root, "Art"), if (category != null) "Categories" else "Systems")
        return File(directory, "${name}_${type.name.lowercase(Locale.ROOT)}.png")
    }

    fun delete(root: File, tag: String, type: SystemArtworkType) {
        val target = file(root, tag, type) ?: throw IOException("Invalid platform tag")
        Files.deleteIfExists(target.toPath())
        notifyChanged()
    }

    internal fun bitmap(bytes: ByteArray, allowGif: Boolean = false): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        bytes.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth !in 1..4096 || options.outHeight !in 1..4096 ||
            (options.outMimeType !in setOf("image/png", "image/jpeg", "image/webp") &&
                !(allowGif && options.outMimeType == "image/gif"))
        ) throw ScraperException(ScraperError.INVALID_RESPONSE)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (max(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
        return bytes.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw ScraperException(ScraperError.INVALID_RESPONSE)
    }

    internal fun notifyChanged() { changes.value += 1 }

    private val names = mapOf(
        "PORTS" to "ports",
        "GB" to "gb", "GBC" to "gbc", "GBA" to "gba", "NES" to "nes", "FDS" to "fds",
        "SNES" to "snes", "N64" to "n64", "NDS" to "nds", "GG" to "gamegear",
        "SMS" to "mastersystem", "MD" to "megadrive", "SG1000" to "sg-1000",
        "32X" to "sega32x", "SEGACD" to "segacd", "SATURN" to "saturn",
        "PS" to "psx", "PSP" to "psp", "DC" to "dreamcast", "LYNX" to "atarilynx",
        "JAGUAR" to "atarijaguar", "ATARI2600" to "atari2600", "ATARI5200" to "atari5200",
        "ATARI7800" to "atari7800", "PCE" to "pcengine", "PCFX" to "pcfx", "PICO8" to "pico8",
        "NGP" to "ngp", "NGPC" to "ngpc", "WS" to "wonderswan", "WSC" to "wonderswancolor",
        "NEOGEO" to "neogeo", "MAME" to "mame", "FBN" to "fbneo", "VIRTUALBOY" to "virtualboy",
        "POKEMINI" to "pokemini", "COLECOVISION" to "colecovision", "VECTREX" to "vectrex",
        "INTELLIVISION" to "intellivision", "SUPERGRAFX" to "supergrafx", "DOS" to "dos",
        "SCUMMVM" to "scummvm", "AMIGA" to "amiga", "PC" to "pc", "PS2" to "ps2",
        "GC" to "gc", "WII" to "wii", "3DS" to "n3ds", "WIIU" to "wiiu",
        "PSVITA" to "psvita", "PS3" to "ps3", "NSW" to "switch",
    )
}
