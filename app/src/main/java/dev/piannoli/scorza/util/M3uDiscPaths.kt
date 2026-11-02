package dev.piannoli.scorza.util

import java.io.File

/** Parses an m3u playlist into the ordered absolute paths of the disc files it references. */
fun parseM3uDiscPaths(m3u: File): List<String> = MultiDisc.playlistDiscs(m3u).map { it.absolutePath }
