package dev.piannoli.scorza.ra

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import dev.piannoli.scorza.util.ErrorLog
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Managed RA hashing following rcheevos' normalization rules (MIT; see licenses). */
object RaHasher {
    private const val MAX_BYTES = 64 * 1024 * 1024L
    private val wholeFileConsoles = setOf(1, 4, 5, 6, 10, 11, 14, 15, 17, 25, 28, 29, 33, 44, 45, 46, 53, 71)
    private val cartridgeConsoles = wholeFileConsoles + setOf(2, 3, 7, 8, 13, 27, 51)
    private val arcadeSubsystemFolders = setOf(
        "nes", "fds", "sms", "msx", "ngp", "pce", "chf", "sgx", "tg16", "msx1",
        "neocd", "coleco", "sg1000", "genesis", "gamegear", "megadriv", "pcengine",
        "channelf", "spectrum", "megadrive", "supergrafx", "zxspectrum", "mastersystem", "colecovision",
    )

    fun supports(platformTag: String, file: File? = null): Boolean {
        val console = RaConsoles.MAP[platformTag.uppercase(java.util.Locale.ROOT)]
        return console in cartridgeConsoles ||
            (console == 12 && (file == null || RaPlayStationHasher.supports(file)))
    }

    fun hashRom(path: String, consoleId: Int): String? = try {
        val file = File(path)
        when {
            consoleId == 12 -> RaPlayStationHasher.hash(file)
            consoleId !in cartridgeConsoles -> null
            consoleId == 27 -> hashArcade(file)
            file.extension.equals("m3u", ignoreCase = true) -> {
                val entry = file.useLines { lines ->
                    lines.map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                }
                val target = entry?.let { File(it).let { child -> if (child.isAbsolute) child else File(file.parentFile, it) } }
                // Do not follow nested playlists or cycles.
                if (target == null || target.extension.equals("m3u", ignoreCase = true)) null
                else hashRom(target.absolutePath, consoleId)
            }
            file.extension.equals("zip", ignoreCase = true) -> ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().filter { !it.isDirectory }.maxByOrNull { it.size }
                entry?.let { zip.getInputStream(it).use { input -> hashInput(input, it.size, consoleId) } }
            }
            file.extension.equals("7z", ignoreCase = true) -> hashSevenZip(file, consoleId)
            else -> file.inputStream().use { hashInput(it, file.length(), consoleId) }
        }
    } catch (error: java.io.IOException) {
        ErrorLog.error("RA identification failed for $path (console $consoleId)", error)
        null
    }

    private fun hashArcade(file: File): String {
        val parent = file.parentFile?.name?.lowercase(java.util.Locale.ROOT)
        val name = if (file.extension.isNotEmpty() && parent in arcadeSubsystemFolders)
            "${parent}_${file.nameWithoutExtension}"
        else file.nameWithoutExtension
        return MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun hashSevenZip(file: File, consoleId: Int): String? {
        val name = SevenZFile.builder().setFile(file).get().use { archive ->
            archive.entries.filter { !it.isDirectory }.maxByOrNull { it.size }?.name
        } ?: return null
        return SevenZFile.builder().setFile(file).get().use { archive ->
            var entry = archive.nextEntry
            while (entry != null && entry.name != name) entry = archive.nextEntry
            val selected = entry ?: return null
            val input = object : InputStream() {
                override fun read(): Int = archive.read()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    archive.read(buffer, offset, length)
            }
            hashInput(input, selected.size, consoleId)
        }
    }

    private fun hashInput(source: InputStream, size: Long, consoleId: Int): String? {
        if (size <= 0) return null
        val input = source.buffered()
        input.mark(128)
        val header = input.readChunk(128)
        input.reset()
        fun startsWith(signature: String, offset: Int = 0): Boolean =
            signature.indices.all { header.getOrNull(offset + it)?.toInt()?.and(0xff) == signature[it].code }
        val skip = when {
            consoleId == 7 && size > 16 && (startsWith("NES\u001a") || startsWith("FDS\u001a")) -> 16
            consoleId == 3 && size % 8192 == 512L -> 512
            consoleId == 8 && size and 512L != 0L -> 512
            consoleId == 13 && size > 64 && startsWith("LYNX\u0000") -> 64
            consoleId == 51 && size > 128 && startsWith("ATARI7800", 1) -> 128
            else -> 0
        }
        if (input.readChunk(skip).size != skip) return null
        val wordSize = if (consoleId == 2) when (header.firstOrNull()?.toInt()?.and(0xff)) {
            0x80, 0xe8, 0x22 -> 1
            0x37 -> 2
            0x40 -> 4
            else -> return null
        } else 1
        val digest = MessageDigest.getInstance("MD5")
        var remaining = minOf(size, MAX_BYTES) - skip
        if (remaining <= 0 || remaining % wordSize != 0L) return null
        while (remaining > 0) {
            val buffer = input.readChunk(minOf(remaining, 65536L).toInt())
            if (buffer.isEmpty() || buffer.size % wordSize != 0) return null
            if (wordSize > 1) {
                for (i in buffer.indices step wordSize) {
                    for (j in 0 until wordSize / 2) {
                        val other = i + wordSize - 1 - j
                        val value = buffer[i + j]
                        buffer[i + j] = buffer[other]
                        buffer[other] = value
                    }
                }
            }
            digest.update(buffer)
            remaining -= buffer.size
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.readChunk(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(buffer, offset, length - offset)
            if (count < 0) break
            if (count == 0) return buffer.copyOf(offset)
            offset += count
        }
        return if (offset == length) buffer else buffer.copyOf(offset)
    }
}
