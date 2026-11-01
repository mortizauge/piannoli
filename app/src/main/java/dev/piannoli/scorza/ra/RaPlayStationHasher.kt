package dev.piannoli.scorza.ra

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/**
 * Managed equivalent of rcheevos rc_hash_psx and its default CD reader, revision
 * e9ca3694c862b61235595176dac4b22677848c93 (MIT; licenses/libraries/rcheevos-hashing-MIT.txt).
 * Hashes the BOOT name and executable, never the whole image or the disc volume label.
 */
internal object RaPlayStationHasher {
    private const val SECTOR_BYTES = 2048
    private const val MAX_HASH_BYTES = 64 * 1024 * 1024L
    private val imageExtensions = setOf("iso", "bin", "img")
    private val cueFile = Regex("""^FILE\s+(?:"([^"]+)"|(\S+))\s+BINARY\s*$""", RegexOption.IGNORE_CASE)
    private val cueTrack = Regex("""^TRACK\s+(\d+)\s+(\S+)\s*$""", RegexOption.IGNORE_CASE)
    private val cueIndex = Regex("""^INDEX\s+(0[01])\s+(\d+):(\d+):(\d+)\s*$""", RegexOption.IGNORE_CASE)
    private val bootLine = Regex("""^BOOT\s*=\s*(?:cdrom:)?\\*([^\s;]+)""", RegexOption.MULTILINE)

    fun supports(file: File): Boolean = try {
        val disc = firstDisc(file)
        disc.extension.lowercase(Locale.ROOT) in imageExtensions + "cue"
    } catch (_: IOException) {
        false
    }

    fun hash(file: File): String {
        val disc = firstDisc(file)
        val extension = disc.extension.lowercase(Locale.ROOT)
        val cue = if (extension in setOf("bin", "img"))
            File(disc.parentFile, "${disc.nameWithoutExtension}.cue").takeIf { it.isFile }
        else null
        val track = when {
            extension == "cue" || cue != null -> openCue(cue ?: disc)
            extension in imageExtensions -> openImage(disc)
            else -> throw IOException("Unsupported PlayStation .$extension container; a known RA game ID is required")
        }
        return track.use { hashExecutable(it) }
    }

    private fun firstDisc(file: File): File {
        if (!file.extension.equals("m3u", true)) return file
        val entry = file.useLines { lines ->
            lines.map { it.trim().removePrefix("\uFEFF") }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        } ?: throw IOException("Empty PlayStation playlist")
        val disc = relativeFile(file, entry)
        if (disc.extension.equals("m3u", true)) throw IOException("Nested PlayStation playlists are unsupported")
        return disc
    }

    private fun relativeFile(parent: File, path: String): File =
        File(path.replace('\\', File.separatorChar).replace('/', File.separatorChar))
            .let { if (it.isAbsolute) it else File(parent.parentFile, it.path) }

    private fun openCue(cue: File): Track {
        var image: File? = null
        var mode: String? = null
        var firstIndex: Long? = null
        var dataIndex: Long? = null
        cue.useLines { lines ->
            for (line in lines) {
                val text = line.trim()
                cueFile.matchEntire(text)?.let {
                    if (mode != null) throw IOException("Track 1 is missing INDEX 01")
                    image = relativeFile(cue, it.groupValues[1].ifEmpty { it.groupValues[2] })
                }
                cueTrack.matchEntire(text)?.let {
                    if (it.groupValues[1].toIntOrNull() != 1 || mode != null)
                        throw IOException("PlayStation CUE must start with data track 1")
                    mode = it.groupValues[2].uppercase(Locale.ROOT)
                }
                val index = cueIndex.matchEntire(text) ?: continue
                if (mode == null) throw IOException("CUE index without a track")
                val minutes = index.groupValues[2].toLongOrNull() ?: throw IOException("Invalid CUE time")
                val seconds = index.groupValues[3].toIntOrNull() ?: throw IOException("Invalid CUE time")
                val frames = index.groupValues[4].toIntOrNull() ?: throw IOException("Invalid CUE time")
                if (minutes > 1000 || seconds !in 0..59 || frames !in 0..74) throw IOException("Invalid CUE time")
                val sector = (minutes * 60 + seconds) * 75 + frames
                if (firstIndex == null) firstIndex = sector
                if (index.groupValues[1] == "01") {
                    dataIndex = sector
                    break
                }
            }
        }
        val layout = when (mode) {
            "MODE1/2048" -> Layout(2048, 0)
            "MODE1/2352" -> Layout(2352, 16)
            "MODE2/2352" -> Layout(2352, 24)
            "MODE2/2336" -> Layout(2336, 8)
            else -> throw IOException("Unsupported PlayStation CUE track mode: $mode")
        }
        val start = firstIndex ?: throw IOException("CUE is missing an index")
        val data = dataIndex ?: throw IOException("CUE is missing INDEX 01")
        if (data < start) throw IOException("Invalid CUE pregap")
        // Like the default rcheevos reader, INDEX 00 is stored pregap; INDEX 01 locates the PVD.
        return openTrack(image ?: throw IOException("CUE is missing a BINARY file"), layout, start, data)
    }

    private fun openImage(file: File): Track {
        for (layout in listOf(Layout(2352, 24), Layout(2352, 16), Layout(2048, 0), Layout(2336, 8))) {
            val track = openTrack(file, layout)
            try {
                if (track.read(track.dataSector + 16).isVolumeDescriptor()) return track
            } catch (_: IOException) {
                // Try the remaining sector layouts; none are raw-MD5 fallbacks.
            }
            track.close()
        }
        throw IOException("Not a supported ISO9660 PlayStation image")
    }

    private fun openTrack(file: File, layout: Layout, firstSector: Long = 0, dataSector: Long = 0): Track {
        val track = Track(file, layout, firstSector, dataSector)
        try {
            track.normalizeRawAddress()
            return track
        } catch (error: IOException) {
            track.close()
            throw error
        }
    }

    private fun hashExecutable(track: Track): String {
        val volume = track.read(track.dataSector + 16)
        if (!volume.isVolumeDescriptor() || volume.u16(128) != SECTOR_BYTES)
            throw IOException("Invalid PlayStation ISO9660 volume descriptor")
        val root = directoryRecord(volume, 156)
        val system = findFile(track, root, "SYSTEM.CNF")
        val boot = system?.let {
            bootLine.find(track.read(it.sector).copyOf(2047).toString(Charsets.US_ASCII).substringBefore('\u0000'))
                ?.groupValues?.get(1)?.take(63)
        }
        val bootFile = boot?.let { findFile(track, root, it) }
        val name = boot?.takeIf { bootFile != null } ?: "PSX.EXE"
        val executable = bootFile ?: findFile(track, root, name)
            ?: throw IOException("Could not locate PlayStation boot executable")
        val header = track.read(executable.sector)
        val size = if (header.copyOfRange(0, 7).toString(Charsets.US_ASCII) == "PS-X EX")
            header.u32(28) + SECTOR_BYTES
        else executable.size
        if (size <= 0) throw IOException("Empty PlayStation boot executable")
        var remaining = minOf(size, MAX_HASH_BYTES)
        val digest = MessageDigest.getInstance("MD5")
        digest.update(name.toByteArray(Charsets.US_ASCII))
        var sector = executable.sector
        while (remaining > 0) {
            val bytes = minOf(remaining, SECTOR_BYTES.toLong()).toInt()
            digest.update(track.read(sector++, bytes))
            remaining -= bytes
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun findFile(track: Track, root: Record, path: String): Record? {
        var directory = root
        val parts = path.trimStart('\\').split('\\')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) throw IOException("Invalid ISO9660 boot path")
        for ((index, part) in parts.withIndex()) {
            val found = findEntry(track, directory, part) ?: return null
            if (index == parts.lastIndex) return found.takeIf { !it.directory }
            if (!found.directory) return null
            directory = found
        }
        return null
    }

    private fun findEntry(track: Track, directory: Record, name: String): Record? {
        if (directory.size <= 0 || directory.size > MAX_HASH_BYTES) throw IOException("Invalid ISO9660 directory size")
        var remaining = directory.size
        var sector = directory.sector
        while (remaining > 0) {
            val data = track.read(sector++)
            val limit = minOf(remaining, SECTOR_BYTES.toLong()).toInt()
            var offset = 0
            while (offset < limit && data[offset].toInt() != 0) {
                val record = directoryRecord(data, offset)
                val length = data[offset].toInt() and 255
                if (offset + length > limit) throw IOException("ISO9660 record exceeds directory")
                val filenameLength = data[offset + 32].toInt() and 255
                val filename = data.copyOfRange(offset + 33, offset + 33 + filenameLength)
                    .toString(Charsets.US_ASCII).substringBefore(';')
                if (filename.equals(name, ignoreCase = true)) return record
                offset += length
            }
            remaining -= limit
        }
        return null
    }

    private fun directoryRecord(data: ByteArray, offset: Int): Record {
        val length = data.getOrNull(offset)?.toInt()?.and(255) ?: 0
        if (length < 34 || offset + length > data.size || 33 + (data[offset + 32].toInt() and 255) > length)
            throw IOException("Malformed ISO9660 directory record")
        return Record(data.u32(offset + 2), data.u32(offset + 10), data[offset + 25].toInt() and 2 != 0)
    }

    private fun ByteArray.isVolumeDescriptor(): Boolean =
        this[0].toInt() == 1 && copyOfRange(1, 6).toString(Charsets.US_ASCII) == "CD001" && this[6].toInt() == 1

    private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(offset: Int): Long =
        (0..3).fold(0L) { value, index -> value or ((this[offset + index].toLong() and 255) shl (index * 8)) }

    private data class Layout(val bytes: Int, val header: Int)
    private data class Record(val sector: Long, val size: Long, val directory: Boolean)

    private class Track(file: File, private val layout: Layout, private var firstSector: Long = 0, dataSector: Long = 0) : Closeable {
        private val input = RandomAccessFile(file, "r")
        var dataSector: Long = dataSector
            private set

        fun normalizeRawAddress() {
            if (layout.bytes != 2352) return
            val pregap = dataSector - firstSector
            val physicalSector = pregap + 16
            val offset = physicalSector * layout.bytes
            if (offset > input.length() - 16) return
            input.seek(offset)
            val header = ByteArray(16).also { input.readFully(it) }
            if (header[0].toInt() != 0 || header[11].toInt() != 0 ||
                (1..10).any { header[it].toInt() and 255 != 255 }) return
            fun bcd(index: Int): Int {
                val value = header[index].toInt() and 255
                if (value and 15 > 9 || value shr 4 > 9) throw IOException("Invalid raw-sector MSF address")
                return (value shr 4) * 10 + (value and 15)
            }
            val minutes = bcd(12)
            val seconds = bcd(13)
            val frames = bcd(14)
            if (seconds > 59 || frames > 74) throw IOException("Invalid raw-sector MSF address")
            // Raw headers express absolute LBA in BCD MSF with the standard 150-sector lead-in.
            firstSector = (minutes * 60L + seconds) * 75 + frames - 150 - physicalSector
            if (firstSector < 0) throw IOException("Invalid PlayStation data track address")
            dataSector = firstSector + pregap
        }

        fun read(sector: Long, size: Int = SECTOR_BYTES): ByteArray {
            if (sector < firstSector) throw IOException("ISO9660 sector precedes the data track")
            val offset = (sector - firstSector) * layout.bytes + layout.header
            if (offset > input.length() - size) throw IOException("Truncated PlayStation disc image")
            input.seek(offset)
            return ByteArray(size).also { input.readFully(it) }
        }

        override fun close() = input.close()
    }
}
