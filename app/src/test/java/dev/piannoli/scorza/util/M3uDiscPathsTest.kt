package dev.piannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class M3uDiscPathsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `parseM3uDiscPaths returns ordered absolute disc paths`() {
        val dir = tmp.newFolder()
        val m3u = File(dir, "game.m3u").apply {
            writeText("# comment\ngame (Disc 1).iso\ngame (Disc 2).iso\n")
        }
        assertEquals(
            listOf(File(dir, "game (Disc 1).iso").absolutePath, File(dir, "game (Disc 2).iso").absolutePath),
            parseM3uDiscPaths(m3u),
        )
    }

    @Test
    fun `parseM3uDiscPaths skips blank lines`() {
        val dir = tmp.newFolder()
        val m3u = File(dir, "game.m3u").apply {
            writeText("game (Disc 1).iso\n\n  \ngame (Disc 2).iso\n")
        }
        assertEquals(
            listOf(File(dir, "game (Disc 1).iso").absolutePath, File(dir, "game (Disc 2).iso").absolutePath),
            parseM3uDiscPaths(m3u),
        )
    }

    @Test(expected = java.io.IOException::class)
    fun `parseM3uDiscPaths reports unreadable file`() {
        val missing = File(tmp.newFolder(), "missing.m3u")
        parseM3uDiscPaths(missing)
    }

    @Test fun `BOM quotes absolute paths parent paths and nested playlists preserve order`() {
        val dir = tmp.newFolder()
        val subdir = File(dir, ".multi").apply { mkdir() }
        val one = File(subdir, "one.chd")
        val two = File(dir, "two.chd")
        val nested = File(subdir, "nested.m3u").apply { writeText("../two.chd\n") }
        val m3u = File(dir, "game.m3u").apply {
            writeText("\uFEFF#EXTM3U\n\"${one.absolutePath}\"\n.multi\\${nested.name}\n${two.absolutePath}\n")
        }
        assertEquals(listOf(one.canonicalPath, two.canonicalPath, two.canonicalPath), parseM3uDiscPaths(m3u))
    }

    @Test(expected = java.io.IOException::class)
    fun `circular playlists are reported without recursion overflow`() {
        val dir = tmp.newFolder()
        val a = File(dir, "a.m3u").apply { writeText("b.m3u\n") }
        File(dir, "b.m3u").writeText("a.m3u\n")
        parseM3uDiscPaths(a)
    }
}
