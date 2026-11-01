package dev.piannoli.scorza.scraper

import android.graphics.BitmapFactory
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.model.Rom
import dev.piannoli.scorza.model.App
import dev.piannoli.scorza.model.artTag
import dev.piannoli.scorza.util.ArtworkLookup
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

class ScrapedArtworkStore @Inject constructor(
    private val paths: PiannoliPathsProvider,
    private val artwork: ArtworkLookup,
) {
    fun systemArtworkFile(tag: String, type: dev.piannoli.scorza.settings.SystemArtworkType): File? =
        SystemArtwork.file(paths.root, tag, type)?.takeIf { it.isFile }

    fun importSystem(tag: String, type: dev.piannoli.scorza.settings.SystemArtworkType, input: InputStream): File {
        return installSystem(tag, type, readBounded(input), allowGif = true)
    }

    fun importGame(rom: Rom, input: InputStream): File {
        val basename = if (rom.path.name.endsWith(".p8.png", ignoreCase = true)) {
            rom.path.name.dropLast(".p8.png".length)
        } else {
            rom.path.nameWithoutExtension
        }
        val destination = artwork.customGameFile(rom.platformTag, basename)
            ?: throw ScraperException(ScraperError.NO_ARTWORK)
        return installCustomImage(destination, rom.platformTag, basename, readBounded(input))
    }

    fun importPortArtwork(app: App, input: InputStream): File {
        require(app.type == dev.piannoli.scorza.model.AppType.PORT)
        val basename = dev.piannoli.scorza.romm.download.sanitizeFsName(app.displayName)
        val destination = artwork.customGameFile("PORTS", basename)
            ?: throw ScraperException(ScraperError.NO_ARTWORK)
        return installCustomImage(destination, "PORTS", basename, readBounded(input))
    }

    fun importFolderArtwork(platformTag: String, relativePath: String, input: InputStream): File {
        val destination = artwork.customFolderFile(platformTag, relativePath)
            ?: throw ScraperException(ScraperError.NO_ARTWORK)
        return installCustomImage(destination, platformTag, null, readBounded(input)).also {
            artwork.notifyFolderArtworkChanged()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = input.read(buffer)
        while (count != -1) {
            if (output.size() + count > 20 * 1024 * 1024) throw ScraperException(ScraperError.INVALID_RESPONSE)
            output.write(buffer, 0, count)
            count = input.read(buffer)
        }
        return output.toByteArray()
    }

    private fun installCustomImage(destination: File, tag: String, basename: String?, bytes: ByteArray): File {
        val bitmap = SystemArtwork.bitmap(bytes, allowGif = true)
        try {
            val directory = destination.parentFile ?: throw ScraperException(ScraperError.STORAGE)
            if (!directory.isDirectory && !directory.mkdirs()) throw ScraperException(ScraperError.STORAGE)
            val temp = File.createTempFile(".custom-", ".part", directory)
            try {
                temp.outputStream().use {
                    if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) {
                        throw ScraperException(ScraperError.STORAGE)
                    }
                }
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (basename != null) {
                    directory.listFiles()?.filter {
                        it != destination && it.isFile && it.nameWithoutExtension == basename
                    }?.forEach { Files.deleteIfExists(it.toPath()) }
                    artwork.invalidate(tag)
                }
            } catch (e: IOException) {
                throw ScraperException(ScraperError.STORAGE)
            } finally {
                temp.delete()
            }
            return destination
        } finally {
            bitmap.recycle()
        }
    }

    fun installSystem(
        tag: String,
        type: dev.piannoli.scorza.settings.SystemArtworkType,
        bytes: ByteArray,
        allowGif: Boolean = false,
    ): File {
        val destination = SystemArtwork.file(paths.root, tag, type) ?: throw ScraperException(ScraperError.NO_ARTWORK)
        val bitmap = SystemArtwork.bitmap(bytes, allowGif)
        try {
            val directory = destination.parentFile ?: throw ScraperException(ScraperError.STORAGE)
            if (!directory.isDirectory && !directory.mkdirs()) throw ScraperException(ScraperError.STORAGE)
            val temp = File.createTempFile(".system-", ".part", directory)
            try {
                temp.outputStream().use {
                    if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) throw ScraperException(ScraperError.STORAGE)
                }
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
            SystemArtwork.notifyChanged()
            return destination
        } catch (e: IOException) {
            throw ScraperException(ScraperError.STORAGE)
        } finally {
            bitmap.recycle()
        }
    }

    fun install(rom: Rom, bytes: ByteArray, overwrite: Boolean = false): File {
        return install(rom.platformTag, rom.path.nameWithoutExtension,
            rom.path.name.endsWith(".p8.png", ignoreCase = true), bytes, overwrite)
    }

    fun installPort(app: dev.piannoli.scorza.model.App, bytes: ByteArray, overwrite: Boolean = false): File {
        require(app.type == dev.piannoli.scorza.model.AppType.PORT)
        return install("PORTS", dev.piannoli.scorza.romm.download.sanitizeFsName(app.displayName), false, bytes, overwrite)
    }

    fun installAppIcon(app: App, icon: android.graphics.drawable.Drawable): File {
        val tag = app.type.artTag
        val basename = dev.piannoli.scorza.romm.download.sanitizeFsName(app.displayName)
        artwork.invalidate(tag)
        artwork.findByName(tag, basename)?.let { return it }
        val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        val bounds = android.graphics.Rect(icon.bounds)
        try {
            icon.setBounds(0, 0, bitmap.width, bitmap.height)
            icon.draw(android.graphics.Canvas(bitmap))
            val output = java.io.ByteArrayOutputStream()
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                throw ScraperException(ScraperError.STORAGE)
            }
            return install(tag, basename, false, output.toByteArray(), overwrite = false)
        } finally {
            icon.bounds = bounds
            bitmap.recycle()
        }
    }

    private fun install(tag: String, basename: String, embedded: Boolean, bytes: ByteArray, overwrite: Boolean): File {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        bytes.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        val extension = when (options.outMimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> throw ScraperException(ScraperError.INVALID_RESPONSE)
        }
        if (options.outWidth !in 1..4096 || options.outHeight !in 1..4096
        ) throw ScraperException(ScraperError.INVALID_RESPONSE)
        val directory = File(File(paths.root, "Art"), tag)
        try {
            artwork.invalidate(tag)
            if (embedded ||
                (!overwrite && artwork.findByName(tag, basename) != null)
            ) throw ScraperException(ScraperError.EXISTING_ARTWORK)
            if (!directory.isDirectory && !directory.mkdirs()) throw ScraperException(ScraperError.STORAGE)
            val destination = File(directory, "$basename.$extension")
            val temp = File.createTempFile(".scrape-", ".part", directory)
            try {
                temp.writeBytes(bytes)
                if (overwrite) {
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    val entries = directory.listFiles() ?: throw ScraperException(ScraperError.STORAGE)
                    for (file in entries) {
                        if (file != destination && file.isFile && file.nameWithoutExtension == basename) {
                            Files.delete(file.toPath())
                        }
                    }
                } else {
                    Files.move(temp.toPath(), destination.toPath())
                }
            } finally {
                temp.delete()
                artwork.invalidate(tag)
            }
            artwork.invalidate(tag)
            return destination
        } catch (e: ScraperException) {
            throw e
        } catch (e: IOException) {
            throw ScraperException(ScraperError.STORAGE)
        }
    }
}
