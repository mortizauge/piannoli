package dev.piannoli.scorza.util

import dev.piannoli.scorza.config.PiannoliPaths
import dev.piannoli.scorza.di.PiannoliPathsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ArtworkLookup(private val pathsProvider: PiannoliPathsProvider) {
    private val artDir: File get() = PiannoliPaths(pathsProvider.root).artDir
    private val cache = ConcurrentHashMap<String, Map<String, File>>()
    private val _folderRevision = MutableStateFlow(0L)
    val folderRevision = _folderRevision.asStateFlow()

    fun find(platformTag: String, romFile: File): File? {
        val hit = findByName(platformTag, artBasename(romFile))
        if (hit != null) return hit
        if (romFile.name.endsWith(".p8.png", ignoreCase = true)) return romFile
        return null
    }

    fun findByName(platformTag: String, basename: String): File? =
        cache.getOrPut(platformTag) { buildMap(platformTag) }[basename]

    fun findFolder(platformTag: String, relativePath: String): File? =
        customFolderFile(platformTag, relativePath)?.takeIf { it.isFile }
            ?: legacyFolderFile(platformTag, relativePath)?.takeIf { it.isFile }

    fun hasSharedFolderArtwork(platformTag: String, relativePath: String): Boolean =
        customFolderFile(platformTag, relativePath)?.isFile == true

    fun customGameFile(platformTag: String, basename: String): File? {
        if (!isSafeTag(platformTag) || !isSafeName(basename)) return null
        return File(File(artDir, platformTag.uppercase()), "$basename.png")
    }

    fun customFolderFile(platformTag: String, relativePath: String): File? {
        val normalized = validatedFolderPath(platformTag, relativePath) ?: return null
        val identity = normalized.substringAfterLast('/').lowercase()
        return File(File(artDir, ".folders"), "${folderDigest(identity)}.png")
    }

    private fun legacyFolderFile(platformTag: String, relativePath: String): File? {
        val normalized = validatedFolderPath(platformTag, relativePath) ?: return null
        val digest = folderDigest("${platformTag.uppercase()}/$normalized")
        return File(File(File(artDir, platformTag.uppercase()), ".folders"), "$digest.png")
    }

    private fun validatedFolderPath(platformTag: String, relativePath: String): String? {
        if (!isSafeTag(platformTag)) return null
        val normalized = relativePath.replace('\\', '/')
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        return normalized.takeIf { it.split('/').all(::isSafeName) }
    }

    private fun folderDigest(identity: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun notifyFolderArtworkChanged() {
        _folderRevision.update { it + 1 }
    }

    fun deleteFolder(platformTag: String, relativePath: String): Boolean {
        val existing = findFolder(platformTag, relativePath) ?: return false
        return existing.delete().also { if (it) notifyFolderArtworkChanged() }
    }

    fun renameArt(platformTag: String, oldBasename: String, newBasename: String): Boolean {
        val existing = findByName(platformTag, oldBasename) ?: return false
        if (findByName(platformTag, newBasename) != null) return false
        val ext = existing.extension
        val target = File(existing.parentFile, if (ext.isEmpty()) newBasename else "$newBasename.$ext")
        val renamed = existing.renameTo(target)
        invalidate(platformTag)
        return renamed
    }

    fun deleteArt(platformTag: String, basename: String): Boolean {
        val existing = findByName(platformTag, basename) ?: return false
        val deleted = existing.delete()
        invalidate(platformTag)
        return deleted
    }

    fun invalidate(platformTag: String) {
        cache.remove(platformTag)
    }

    fun invalidateAll() {
        cache.clear()
    }

    private fun artBasename(romFile: File): String =
        if (romFile.name.endsWith(".p8.png", ignoreCase = true)) {
            romFile.name.dropLast(".p8.png".length)
        } else {
            romFile.nameWithoutExtension
        }

    private fun buildMap(platformTag: String): Map<String, File> {
        val tagDir = File(artDir, platformTag)
        if (!tagDir.exists()) return emptyMap()
        val entries = tagDir.listFiles() ?: return emptyMap()
        val out = mutableMapOf<String, File>()
        for (file in entries) if (file.isFile) out[file.nameWithoutExtension] = file
        return out
    }

    private fun isSafeTag(tag: String): Boolean =
        tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }
}
