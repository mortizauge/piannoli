package dev.piannoli.scorza.util

import java.io.File
import java.io.IOException
import java.util.Locale

/** Disc detection never crosses folders or discards edition/region tags. Playlists are explicit. */
internal object MultiDisc {
    data class DiscName(val base: String, val number: Int)
    data class Group(val key: String, val name: String, val discs: List<File>)

    private val marker = Regex(
        """(?i)\s*[\[(](?:disc|disk|cd)\s*(\d+)(?:\s*(?:of|/)\s*\d+)?[\])]""",
    )
    private val suffix = Regex("""(?i)(?:\s+-\s+|\s+)(?:disc|disk|cd)\s*(\d+)$""")
    private val trackMarker = Regex("""(?i)[\[(]track\s*\d+[\])]""")
    private val formats = setOf(
        "cue", "chd", "gdi", "toc", "ccd", "iso", "img", "bin", "mdf", "mds", "pbp",
        "cdi", "rvz", "gcm", "gcz", "ciso", "wia", "wbfs", "adf", "adz", "dms", "fdi", "ipf",
    )
    private val cueLine = Regex("""(?i)^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+\w+\s*$""")
    private val gdiLine = Regex("""^\s*\d+\s+\d+\s+\d+\s+\d+\s+(?:"([^"]+)"|(\S+))\s+\d+\s*$""")

    fun isPlaylist(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in setOf("m3u", "m3u8")

    fun firstDisc(file: File): File =
        if (isPlaylist(file)) playlistDiscs(file).firstOrNull() ?: throw IOException("Empty playlist: ${file.name}")
        else file

    fun name(file: File): DiscName? {
        if (file.extension.lowercase(Locale.ROOT) !in formats) return null
        val stem = file.nameWithoutExtension
        if (trackMarker.containsMatchIn(stem)) return null
        val matches = marker.findAll(stem).toList()
        val match = matches.singleOrNull() ?: if (matches.isEmpty()) suffix.find(stem) else null
        match ?: return null
        val number = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val base = stem.removeRange(match.range).trim().replace(Regex("""\s+"""), " ")
        return base.takeIf { it.isNotEmpty() }?.let { DiscName(it, number) }
    }

    fun key(file: File): String? = name(file)?.let {
        File(file.parentFile, it.base.lowercase(Locale.ROOT)).absolutePath
    }

    fun groups(files: List<File>, tag: String): List<Group> {
        if (!PlatformFileTypes.supports(tag, "m3u")) return emptyList()
        val primary = withoutCompanions(files)
        return primary.mapNotNull { file -> name(file)?.let { file to it } }
            .groupBy { (_, name) -> name.base.lowercase(Locale.ROOT) }
            .values.mapNotNull { candidates ->
                // Duplicate disc numbers can be alternate dumps or editions; do not guess.
                if (candidates.size < 2 || candidates.map { it.second.number }.distinct().size != candidates.size) {
                    return@mapNotNull null
                }
                val ordered = candidates.sortedBy { it.second.number }
                val first = ordered.first()
                Group(key(first.first)!!, first.second.base, ordered.map { it.first })
            }
    }

    fun withoutCompanions(files: List<File>): List<File> {
        val companions = files.flatMap(::companions).mapTo(hashSetOf()) { it.canonicalPath }
        return files.filter { it.canonicalPath !in companions }
    }

    fun companions(file: File): List<File> {
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext == "ccd" || ext == "mds") {
            val extensions = if (ext == "ccd") listOf("img", "sub") else listOf("mdf")
            return extensions.map { File(file.parentFile, "${file.nameWithoutExtension}.$it") }
                .filter { it.extension != "sub" || it.isFile }
        }
        val regex = when (ext) {
            "cue" -> cueLine
            "gdi" -> gdiLine
            else -> return emptyList()
        }
        return try {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    regex.find(line)?.let { match ->
                        resolve(file, match.groupValues[1].ifEmpty { match.groupValues[2] })
                    }
                }.toList()
            }
        } catch (e: IOException) {
            ScanLog.write("disc companions ${file.absolutePath}: ${e.message}")
            emptyList()
        }
    }

    /** Keeps missing entries so launch validation can report them instead of changing disc order. */
    fun playlistDiscs(playlist: File, onNestedPlaylist: (File) -> Unit = {}): List<File> {
        val active = hashSetOf<String>()
        fun read(file: File, depth: Int): List<File> {
            if (depth > 16 || !active.add(file.canonicalPath)) {
                throw IOException("Circular or excessively nested playlist: ${file.name}")
            }
            try {
                return file.readLines().flatMap { raw ->
                    val entry = raw.trim().removePrefix("\uFEFF").trim()
                    if (entry.isEmpty() || entry.startsWith("#")) emptyList()
                    else {
                        val disc = resolve(file, entry.removeSurrounding("\""))
                        if (isPlaylist(disc)) {
                            onNestedPlaylist(disc)
                            read(disc, depth + 1)
                        } else listOf(disc)
                    }
                }
            } finally {
                active.remove(file.canonicalPath)
            }
        }
        return read(playlist, 0)
    }

    private fun resolve(owner: File, entry: String): File {
        if (entry.startsWith("file:", true)) {
            return try {
                File(java.net.URI(entry)).canonicalFile
            } catch (e: IllegalArgumentException) {
                throw IOException("Invalid file URI in ${owner.name}: $entry", e)
            } catch (e: java.net.URISyntaxException) {
                throw IOException("Invalid file URI in ${owner.name}: $entry", e)
            }
        }
        if (entry.contains("://")) throw IOException("Unsupported disc URI in ${owner.name}: $entry")
        val normalized = entry.replace('\\', File.separatorChar).replace('/', File.separatorChar)
        val file = File(normalized)
        return (if (file.isAbsolute) file else File(owner.parentFile, normalized)).canonicalFile
    }
}
