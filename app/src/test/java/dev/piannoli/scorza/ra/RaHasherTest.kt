package dev.piannoli.scorza.ra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RaHasherTest {
    @get:Rule val files = TemporaryFolder()

    private fun hash(name: String, data: ByteArray, console: Int): String? =
        RaHasher.hashRom(File(files.root, name).apply { writeBytes(data) }.absolutePath, console)

    @Test fun `headered cartridges and archived ROMs identify the same game without JNI`() {
        val payload = ByteArray(8192) { (it % 251).toByte() }
        val expected = hash("game.bin", payload, 7)
        assertNotNull(expected)
        assertEquals(expected, hash("game.nes", byteArrayOf(0x4e, 0x45, 0x53, 0x1a) + ByteArray(12) + payload, 7))
        assertEquals(expected, hash("game.fds", byteArrayOf(0x46, 0x44, 0x53, 0x1a) + ByteArray(12) + payload, 7))
        assertEquals(expected, hash("game.smc", ByteArray(512) + payload, 3))
        val zip = File(files.root, "game.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("nested/game.nes"))
            it.write(byteArrayOf(0x4e, 0x45, 0x53, 0x1a) + ByteArray(12) + payload)
            it.closeEntry()
        }
        assertEquals(expected, RaHasher.hashRom(zip.absolutePath, 7))
    }

    @Test fun `N64 byte orders normalize to the same RA hash and invalid dumps fail`() {
        val native = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(65536) { it.toByte() }
        fun swapped(word: Int) = native.toList().chunked(word).flatMap { it.reversed() }.toByteArray()
        val expected = hash("game.z64", native, 2)
        assertNotNull(expected)
        assertEquals(expected, hash("game.v64", swapped(2), 2))
        assertEquals(expected, hash("game.n64", swapped(4), 2))
        assertNull(hash("invalid.z64", byteArrayOf(1, 2, 3, 4), 2))
        assertNull(hash("truncated.n64", byteArrayOf(0x40, 1, 2), 2))
    }

    @Test fun `invalid disc hashing and recursive playlists fail instead of inventing identities`() {
        assertNull(hash("game.iso", byteArrayOf(1, 2, 3), 12))
        val playlist = File(files.root, "cycle.m3u").apply { writeText("cycle.m3u") }
        assertNull(RaHasher.hashRom(playlist.absolutePath, 1))
    }

    @Test fun `arcade identities use romset names and retain FBNeo subsystem prefixes`() {
        val archive = File(files.root, "game.zip").apply { writeText("romset") }
        assertEquals(hash("plain.bin", "game".toByteArray(), 1), RaHasher.hashRom(archive.absolutePath, 27))
        val subsystem = File(files.root, "NES/game.zip").apply { parentFile!!.mkdirs(); writeText("romset") }
        assertEquals(hash("subsystem.bin", "nes_game".toByteArray(), 1), RaHasher.hashRom(subsystem.absolutePath, 27))
    }

    @Test fun `PlayStation ISO hashes match published rcheevos executable vectors`() {
        // test/rhash/test_hash_disc.c at the revision documented in RaPlayStationHasher.
        assertEquals("db433fb038cde4fb15c144e8c7dea6e3", hash("game.iso", PlayStationHashFixture.iso(), 12))
        assertEquals("674018e23a4052113665dfb264e9c2fc",
            hash("folder.iso", PlayStationHashFixture.iso(name = "bin\\SCES_012.37"), 12))
        assertEquals("e494c79a7315be0dc3e8571c45df162c",
            hash("homebrew.iso", PlayStationHashFixture.iso(name = "PSX.EXE", size = 0x12000, system = false), 12))
    }

    @Test fun `raw Mode1 and Mode2 sectors hash payload not sector headers or trailers`() {
        val iso = PlayStationHashFixture.iso()
        for ((stride, header) in listOf(2352 to 16, 2352 to 24, 2336 to 8)) {
            val raw = PlayStationHashFixture.raw(iso, stride, header)
            assertEquals(PlayStationHashFixture.HASH, hash("raw-$header.bin", raw, 12))
            val cue = File(files.root, "raw-$header.cue").apply {
                val mode = if (header == 16) "MODE1" else "MODE2"
                writeText("FILE \"raw-$header.bin\" BINARY\n  TRACK 01 $mode/$stride\n    INDEX 01 00:00:00\n")
            }
            assertEquals(PlayStationHashFixture.HASH, RaHasher.hashRom(cue.absolutePath, 12))
        }
    }

    @Test fun `CUE references and stored pregap are honored and M3U uses first disc`() {
        val iso = PlayStationHashFixture.iso(sectorBase = 150)
        val raw = ByteArray(150 * 2352) + PlayStationHashFixture.raw(iso, 2352, 24, sectorBase = 150)
        File(files.root, "disc data.bin").writeBytes(raw)
        File(files.root, "audio.bin").writeBytes(ByteArray(2352))
        val cue = File(files.root, "bundle.cue").apply {
            writeText("""
                FILE "disc data.bin" BINARY
                  TRACK 01 MODE2/2352
                    INDEX 00 00:00:00
                    INDEX 01 00:02:00
                FILE "audio.bin" BINARY
                  TRACK 02 AUDIO
                    INDEX 01 00:00:00
            """.trimIndent())
        }
        assertEquals(PlayStationHashFixture.HASH, RaHasher.hashRom(cue.absolutePath, 12))
        val playlist = File(files.root, "game.m3u").apply {
            writeText("\uFEFF#EXTM3U\n\nbundle.cue\nmissing-second-disc.chd\n")
        }
        assertEquals(PlayStationHashFixture.HASH, RaHasher.hashRom(playlist.absolutePath, 12))
        assertTrue(RaHasher.supports("PS", playlist))
        playlist.writeText("missing.chd\nbundle.cue")
        assertNull(RaHasher.hashRom(playlist.absolutePath, 12))
        assertFalse(RaHasher.supports("PS", playlist))
    }

    @Test fun `CUE INDEX01 without stored pregap and companion CUE override direct BIN layout`() {
        val raw = PlayStationHashFixture.raw(PlayStationHashFixture.iso(sectorBase = 150), 2352, 24, sectorBase = 150)
        File(files.root, "actual.bin").writeBytes(raw)
        assertEquals(PlayStationHashFixture.HASH, hash("addressed.bin", raw, 12))
        val cue = File(files.root, "launch.cue").apply {
            writeText("FILE \"actual.bin\" BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:02:00")
        }
        val launch = File(files.root, "launch.bin").apply { writeText("not the data track") }
        assertEquals(PlayStationHashFixture.HASH, RaHasher.hashRom(cue.absolutePath, 12))
        assertEquals(PlayStationHashFixture.HASH, RaHasher.hashRom(launch.absolutePath, 12))
    }

    @Test fun `PSX header length overrides directory size and disc metadata is not hashed`() {
        val iso = PlayStationHashFixture.iso(directorySize = 32)
        "DIFFERENT VOLUME".toByteArray().copyInto(iso, 16 * 2048 + 40)
        assertEquals(PlayStationHashFixture.HASH, hash("metadata.iso", iso, 12))
    }

    @Test fun `malformed and unsupported PlayStation discs never return fabricated hashes`() {
        assertTrue(RaHasher.supports("ps"))
        for (extension in listOf("chd", "pbp", "toc", "ccd", "zip")) {
            val file = File(files.root, "game.$extension").apply { writeBytes(PlayStationHashFixture.iso()) }
            assertFalse(RaHasher.supports("PS", file))
            assertNull(RaHasher.hashRom(file.absolutePath, 12))
        }
        val iso = PlayStationHashFixture.iso()
        assertNull(hash("truncated.iso", iso.copyOf(26 * 2048 + 32), 12))
        val badDirectory = iso.copyOf().apply { this[20 * 2048] = 1 }
        assertNull(hash("malformed.iso", badDirectory, 12))
        val badCue = File(files.root, "missing.cue").apply {
            writeText("FILE \"missing.bin\" BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:00:00")
        }
        assertNull(RaHasher.hashRom(badCue.absolutePath, 12))
        badCue.writeText("FILE \"audio.bin\" BINARY\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        assertNull(RaHasher.hashRom(badCue.absolutePath, 12))
        val nested = File(files.root, "nested.m3u").apply { writeText("cycle.m3u") }
        File(files.root, "cycle.m3u").writeText("nested.m3u")
        assertNull(RaHasher.hashRom(nested.absolutePath, 12))
    }
}

/** ISO layout is synthetic; executable bytes reproduce upstream's MIT-licensed fill_image fixture. */
internal object PlayStationHashFixture {
    const val HASH = "db433fb038cde4fb15c144e8c7dea6e3"

    fun iso(
        name: String = "SLUS_007.45",
        size: Int = 0x07D800,
        system: Boolean = true,
        sectorBase: Int = 0,
        directorySize: Int = size,
    ): ByteArray {
        val image = ByteArray((26 + (size + 2047) / 2048) * 2048)
        val volume = 16 * 2048
        image[volume] = 1
        "CD001".toByteArray().copyInto(image, volume + 1)
        image[volume + 6] = 1
        put32(image, volume + 128, 2048)
        record(image, volume + 156, byteArrayOf(0), 20 + sectorBase, 2048, true)
        var rootOffset = 20 * 2048
        if (system) {
            val config = "BOOT=cdrom:\\\\$name;1\nTCB=4\n".toByteArray()
            config.copyInto(image, 24 * 2048)
            rootOffset += record(image, rootOffset, "SYSTEM.CNF;1".toByteArray(), 24 + sectorBase, config.size)
        }
        val parts = name.split('\\')
        var directory = rootOffset
        if (parts.size > 1) {
            record(image, rootOffset, parts.first().toByteArray(), 21 + sectorBase, 2048, true)
            directory = 21 * 2048
        }
        record(image, directory, "${parts.last()};1".toByteArray(), 26 + sectorBase, directorySize)
        val executable = payload(size)
        "PS-X EXE".toByteArray().copyInto(executable)
        put32(executable, 28, size - 2048)
        executable.copyInto(image, 26 * 2048)
        return image
    }

    fun raw(iso: ByteArray, stride: Int, header: Int, sectorBase: Int = 0): ByteArray =
        ByteArray(iso.size / 2048 * stride) { 0x55 }.also { image ->
            for (sector in 0 until iso.size / 2048) {
                if (stride == 2352) {
                    val offset = sector * stride
                    image[offset] = 0
                    image.fill(255.toByte(), offset + 1, offset + 11)
                    image[offset + 11] = 0
                    val msf = sector + sectorBase + 150
                    fun bcd(value: Int): Byte = ((value / 10) * 16 + value % 10).toByte()
                    image[offset + 12] = bcd(msf / (60 * 75))
                    image[offset + 13] = bcd(msf / 75 % 60)
                    image[offset + 14] = bcd(msf % 75)
                    image[offset + 15] = if (header == 16) 1 else 2
                }
                iso.copyInto(image, sector * stride + header, sector * 2048, (sector + 1) * 2048)
            }
        }

    private fun record(image: ByteArray, offset: Int, name: ByteArray, sector: Int, size: Int, directory: Boolean = false): Int {
        val length = (33 + name.size + 1) and -2
        image[offset] = length.toByte()
        put32(image, offset + 2, sector)
        put32(image, offset + 10, size)
        image[offset + 25] = if (directory) 2 else 0
        image[offset + 32] = name.size.toByte()
        name.copyInto(image, offset + 33)
        return length
    }

    private fun put32(image: ByteArray, offset: Int, value: Int) {
        for (index in 0..3) image[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun payload(size: Int): ByteArray {
        var remaining = size
        var seed = size xor (size ushr 8) xor ((size - 1) * 25387)
        val result = ByteArray(size)
        while (remaining > 0) {
            val kind = seed and 255
            val count = when (kind) {
                0 -> ((seed shr 8) and 63 and (remaining and 15).inv()).coerceAtLeast(1)
                1 -> ((seed shr 8) and 7) + 1
                in 2..7 -> ((seed shr 8) and 3) + 1
                else -> 1
            }.coerceAtMost(remaining)
            val value = when (kind) {
                0 -> 0
                1 -> (seed shr 16) and 255
                in 2..7 -> ((seed shr 16) and 255) xor intArrayOf(255, 165, 195, 150, 120, 120)[kind - 2]
                else -> ((seed shr 8) xor (seed shr 16)) and 255
            }.toByte()
            result.fill(value, size - remaining, size - remaining + count)
            remaining -= count
            seed = (seed * 0x41C64E6D + 12345) and 0x7fffffff
        }
        return result
    }
}
