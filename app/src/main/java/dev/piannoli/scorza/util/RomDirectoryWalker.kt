package dev.piannoli.scorza.util

import android.content.res.AssetManager
import dev.piannoli.scorza.config.PiannoliPaths
import dev.piannoli.scorza.config.PlatformFolders
import dev.piannoli.scorza.di.PiannoliPathsProvider
import java.io.File
import java.io.IOException
import java.util.Locale

/** Reads the user's layout without moving files or generating playlists in the ROM directory. */
class RomDirectoryWalker(
    private val pathsProvider: PiannoliPathsProvider,
    private val assets: AssetManager,
    private val arcadeTitleLookup: ArcadeTitleLookup,
) {
    private val piannoliRoot: File get() = pathsProvider.root
    private val romDirectory: File get() = pathsProvider.romDir

    private data class CachedFolders(val stamp: List<Pair<String, Long>>, val isArcade: Boolean, val showHidden: Boolean, val folders: List<String>)
    private val categoryFolderCache = java.util.concurrent.ConcurrentHashMap<String, CachedFolders>()
    private val referencesByTag = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    @Volatile private var ignoredExtensions: Set<String> = emptySet()
    @Volatile private var ignoredFiles: Set<String> = emptySet()
    @Volatile private var ignoreListsLoaded = false

    data class ScannedRom(
        val relativePath: String,
        val displayName: String,
        val tags: String?,
        val memberPaths: List<String> = emptyList(),
    )
    data class RekeyMove(val oldRelPath: String, val newRelPath: String)
    data class WalkResult(
        val tagDir: File,
        val roms: List<ScannedRom>,
        val rekeys: List<RekeyMove> = emptyList(),
        val tagDirs: List<File> = listOf(tagDir),
    )

    private fun ensureIgnoreLists() {
        if (ignoreListsLoaded) return
        val paths = PiannoliPaths(piannoliRoot)
        seedFromAsset(assets, "ignore_extensions_roms.txt", paths.ignoreExtensionsRoms)
        seedFromAsset(assets, "ignore_files_roms.txt", paths.ignoreFilesRoms)
        ignoredExtensions = readSetLowercase(paths.ignoreExtensionsRoms) { it.removePrefix(".") }
        ignoredFiles = readSetLowercase(paths.ignoreFilesRoms) { it }
        ignoreListsLoaded = true
    }

    fun walk(platformTag: String, isArcade: Boolean): WalkResult? {
        ensureIgnoreLists()
        val tag = PlatformFolders.canonicalTag(platformTag)
        val directories = resolveTagDirs(tag)
        if (directories.isEmpty()) return null
        val referenced = collectPlaylistReferences(directories, tag, isArcade)
        referencesByTag[tag] = referenced
        val out = mutableListOf<ScannedRom>()
        directories.forEach { scanDir(it, tag, isArcade, referenced, out, 0) }
        return WalkResult(directories.first(), out, tagDirs = directories)
    }

    private fun collectPlaylistReferences(directories: List<File>, tag: String, isArcade: Boolean): Set<String> {
        val referenced = hashSetOf<String>()
        // Collect before scanning so references into other visible folders suppress duplicates too.
        fun collect(dir: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (file in visibleEntries(dir)) {
                if (file.isDirectory) {
                    if (!isArcade || !isArcadeSupportDir(file)) collect(file, depth + 1)
                } else if (accepts(tag, file) && MultiDisc.isPlaylist(file)) {
                    try {
                        val nested = hashSetOf<String>()
                        val discs = MultiDisc.playlistDiscs(file) { nested.add(it.canonicalPath) }
                        for (disc in discs) {
                            referenced.add(disc.canonicalPath)
                            MultiDisc.companions(disc).forEach { referenced.add(it.canonicalPath) }
                        }
                        referenced.addAll(nested)
                    } catch (e: IOException) {
                        ScanLog.write("playlist ${file.absolutePath}: ${e.message}")
                    }
                }
            }
        }
        directories.forEach { collect(it, 0) }
        return referenced
    }

    fun resolveTagDirs(tag: String): List<File> = PlatformFolders.directories(romDirectory, tag)
    fun resolveTagDir(tag: String): File? = resolveTagDirs(tag).firstOrNull()

    fun categoryDirectories(tag: String, path: String): List<File> =
        resolveTagDirs(tag).mapNotNull { root ->
            val directory = File(root, path)
            directory.takeIf { it.isDirectory && it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
        }

    fun renameCategory(tag: String, path: String, newName: String): List<RekeyMove>? {
        if (newName.isBlank() || newName in listOf(".", "..") || newName.any { it == '/' || it == '\\' }) return null
        val sources = categoryDirectories(tag, path)
        if (sources.isEmpty()) return null
        val moves = sources.map { it to File(it.parentFile, newName) }
        if (moves.any { (_, target) -> target.exists() }) return null
        val completed = mutableListOf<Pair<File, File>>()
        for ((source, target) in moves) {
            if (!source.renameTo(target)) {
                for ((old, renamed) in completed.asReversed()) {
                    if (!renamed.renameTo(old)) throw IOException("Cannot restore ${old.absolutePath}")
                }
                return null
            }
            completed.add(source to target)
        }
        return completed.map { (old, renamed) ->
            RekeyMove(old.relativeTo(romDirectory).path, renamed.relativeTo(romDirectory).path)
        }
    }

    fun presentExtensions(tag: String): List<String> {
        val extensions = sortedSetOf<String>()
        fun visit(dir: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            val entries = dir.listFiles() ?: throw IOException("Cannot read ${dir.absolutePath}")
            for (file in entries) {
                if (isHidden(file)) continue
                if (file.isDirectory) visit(file, depth + 1)
                else if (file.isFile) extensions.add(extensionOf(file))
            }
        }
        resolveTagDirs(tag).forEach { visit(it, 0) }
        return extensions.toList()
    }

    private fun extensionOf(file: File): String =
        if (file.name.endsWith(".p8.png", ignoreCase = true)) "p8.png" else file.extension.lowercase(Locale.ROOT)

    private fun accepts(tag: String, file: File): Boolean =
        !isHidden(file) && !isIgnored(file) && PlatformFileTypes.accepts(tag, file) &&
            extensionOf(file) !in pathsProvider.filteredExtensions(tag)

    private fun isHidden(file: File): Boolean = !pathsProvider.showHiddenFiles && file.name.startsWith(".")
    private fun visibleEntries(dir: File): List<File> =
        (dir.listFiles() ?: throw IOException("Cannot read ${dir.absolutePath}"))
            .filter { !isHidden(it) && !isIgnored(it) }

    fun invalidateNameMap(tagDir: File) = arcadeTitleLookup.invalidate(tagDir)
    fun invalidateCategoryFolders(platformTag: String) {
        categoryFolderCache.remove(PlatformFolders.canonicalTag(platformTag))
    }

    fun categoryFolders(platformTag: String, isArcade: Boolean): List<String> {
        ensureIgnoreLists()
        val tag = PlatformFolders.canonicalTag(platformTag)
        val directories = resolveTagDirs(tag)
        val stamp = directories.map { it.name to it.lastModified() }
        val showHidden = pathsProvider.showHiddenFiles
        categoryFolderCache[tag]?.let {
            if (it.stamp == stamp && it.isArcade == isArcade && it.showHidden == showHidden) return it.folders
        }
        val folders = mutableSetOf<String>()
        fun visit(dir: File, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (sub in visibleEntries(dir).filter { it.isDirectory }) {
                if (isArcade && isArcadeSupportDir(sub)) continue
                if (findDirLaunchFile(sub, tag) != null) continue
                val rel = if (prefix.isEmpty()) sub.name else "$prefix/${sub.name}"
                folders.add(rel)
                visit(sub, rel, depth + 1)
            }
        }
        directories.forEach { visit(it, "", 0) }
        val sorted = folders.sortedBy { it.lowercase(Locale.ROOT) }
        categoryFolderCache[tag] = CachedFolders(stamp, isArcade, showHidden, sorted)
        return sorted
    }

    /** A conservative dedicated bundle check, also used by explicit rename/delete operations. */
    fun gameDirectory(primaryFile: File): File? {
        val parent = primaryFile.parentFile ?: return null
        if (parent.parentFile == romDirectory) return null
        return parent.takeIf { findDirLaunchFile(it, platformTagFor(it)) == primaryFile }
    }

    fun arcadeSupportDir(primaryFile: File, isArcade: Boolean): File? {
        if (!isArcade || primaryFile.extension.lowercase(Locale.ROOT) !in ARCADE_ROMSET_EXTENSIONS) return null
        return File(primaryFile.parentFile, primaryFile.nameWithoutExtension).takeIf { it.isDirectory }
    }

    fun gameFiles(primaryFile: File): List<File> {
        val discs = when {
            MultiDisc.isPlaylist(primaryFile) -> MultiDisc.playlistDiscs(primaryFile)
            else -> looseDiscGroup(primaryFile)?.takeUnless { pathsProvider.discGroupingDisabled(it.key) }?.discs ?: listOf(primaryFile)
        }
        return (listOf(primaryFile) + discs.flatMap { listOf(it) + MultiDisc.companions(it) })
            .distinctBy { it.canonicalPath }
    }

    fun deleteGameFiles(primaryFile: File) {
        if (MultiDisc.isPlaylist(primaryFile)) {
            if (primaryFile.exists() && !primaryFile.delete()) throw IOException("Cannot delete ${primaryFile.absolutePath}")
            return
        }
        val group = looseDiscGroup(primaryFile)
        val directory = gameDirectory(primaryFile)
        if (group == null && directory != null) {
            if (!directory.deleteRecursively()) throw IOException("Cannot delete ${directory.absolutePath}")
            return
        }
        for (file in gameFiles(primaryFile)) {
            if (file.exists() && !file.delete()) throw IOException("Cannot delete ${file.absolutePath}")
        }
    }

    internal fun looseDiscGroup(file: File): MultiDisc.Group? {
        if (MultiDisc.name(file) == null) return null
        ensureIgnoreLists()
        val tag = platformTagFor(file)
        val referenced = referencesByTag.getOrPut(tag) { collectPlaylistReferences(resolveTagDirs(tag), tag, false) }
        val files = visibleEntries(file.parentFile ?: return null)
            .filter { it.isFile && accepts(tag, it) && it.canonicalPath !in referenced }
        return MultiDisc.groups(files, tag).firstOrNull { file in it.discs }
    }

    fun discFiles(file: File): List<File> =
        if (MultiDisc.isPlaylist(file)) MultiDisc.playlistDiscs(file)
        else looseDiscGroup(file)?.takeUnless { pathsProvider.discGroupingDisabled(it.key) }?.discs.orEmpty()

    fun canToggleDiscGrouping(file: File): Boolean = !MultiDisc.isPlaylist(file) && looseDiscGroup(file) != null
    fun discGroupingDisabled(file: File): Boolean =
        MultiDisc.key(file)?.let(pathsProvider::discGroupingDisabled) ?: false

    fun toggleDiscGrouping(file: File) {
        val key = MultiDisc.key(file) ?: throw IOException("No numbered disc set for ${file.name}")
        pathsProvider.setDiscGroupingDisabled(key, !pathsProvider.discGroupingDisabled(key))
        invalidateCategoryFolders(platformTagFor(file))
    }

    enum class RenameOutcome { RENAMED, NAME_TAKEN, FAILED }
    data class RenameGameResult(val outcome: RenameOutcome, val newPrimary: File? = null)

    fun renameGame(primaryFile: File, newName: String): RenameGameResult {
        if (newName.isBlank() || newName in listOf(".", "..") || newName.any { it == '/' || it == '\\' }) {
            return RenameGameResult(RenameOutcome.FAILED)
        }
        // Renaming just one member would break virtual detection or user-authored descriptors.
        if (MultiDisc.isPlaylist(primaryFile) || looseDiscGroup(primaryFile) != null) {
            return RenameGameResult(RenameOutcome.FAILED)
        }
        val dir = gameDirectory(primaryFile)
        if (dir == null) {
            if (newName.equals(primaryFile.nameWithoutExtension, ignoreCase = true)) {
                return RenameGameResult(RenameOutcome.RENAMED, primaryFile)
            }
            val target = File(primaryFile.parentFile, newName + primaryFile.extension.let { if (it.isEmpty()) "" else ".$it" })
            if (target.exists()) return RenameGameResult(RenameOutcome.NAME_TAKEN)
            return if (primaryFile.renameTo(target)) RenameGameResult(RenameOutcome.RENAMED, target)
            else RenameGameResult(RenameOutcome.FAILED)
        }
        val oldBase = dir.name
        if (newName.equals(oldBase, ignoreCase = true)) return RenameGameResult(RenameOutcome.RENAMED, primaryFile)
        val newDir = File(dir.parentFile, newName)
        if (newDir.exists()) return RenameGameResult(RenameOutcome.NAME_TAKEN)
        if (!dir.renameTo(newDir)) return RenameGameResult(RenameOutcome.FAILED)
        val prefixed = newDir.listFiles { f ->
            f.isFile && f.name.startsWith(oldBase) && f.name.removePrefix(oldBase).let {
                it.isEmpty() || it[0] == '.' || it[0] == '(' || it[0] == ' '
            }
        }.orEmpty()
        val renamed = mutableListOf<Pair<File, File>>()
        var newPrimary: File? = null
        for (file in prefixed) {
            val target = File(newDir, newName + file.name.removePrefix(oldBase))
            if (file.name == primaryFile.name) newPrimary = target
            if (file.renameTo(target)) renamed.add(file to target)
            else {
                for ((from, to) in renamed.asReversed()) to.renameTo(from)
                newDir.renameTo(dir)
                return RenameGameResult(RenameOutcome.FAILED)
            }
        }
        return RenameGameResult(RenameOutcome.RENAMED, newPrimary ?: File(newDir, primaryFile.name))
    }

    private fun scanDir(dir: File, tag: String, isArcade: Boolean, referenced: Set<String>, out: MutableList<ScannedRom>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val entries = visibleEntries(dir)
        for (subdir in entries.filter { it.isDirectory }) {
            if (isArcade && isArcadeSupportDir(subdir)) continue
            val launch = findDirLaunchFile(subdir, tag)
            if (launch != null && launch.canonicalPath !in referenced) {
                val (name, tags) = RomNaming.splitNameAndTags(subdir.name)
                val members = if (MultiDisc.isPlaylist(launch)) emptyList()
                    else looseDiscGroup(launch)?.discs.orEmpty().map { it.relativeTo(romDirectory).path }
                out.add(ScannedRom(launch.relativeTo(romDirectory).path, name, tags, members))
            } else {
                scanDir(subdir, tag, isArcade, referenced, out, depth + 1)
            }
        }
        val files = MultiDisc.withoutCompanions(entries.filter { it.isFile && accepts(tag, it) })
            .filter { it.canonicalPath !in referenced }
        val groups = MultiDisc.groups(files, tag).filterNot { pathsProvider.discGroupingDisabled(it.key) }
        val grouped = groups.flatMap { it.discs }.toSet()
        val overrides = arcadeTitleLookup.mapFor(dir, fallbackToArcade = isArcade)
        fun add(file: File, rawName: String, members: List<File> = emptyList()) {
            val (name, tags) = overrides[file.name]?.let { it to null } ?: RomNaming.splitNameAndTags(rawName)
            out.add(ScannedRom(file.relativeTo(romDirectory).path, name, tags, members.map { it.relativeTo(romDirectory).path }))
        }
        groups.forEach { add(it.discs.first(), it.name, it.discs) }
        for (file in files) {
            if (file in grouped) continue
            val rawName = when {
                file.name.endsWith(".p8.png", ignoreCase = true) -> file.name.dropLast(".p8.png".length)
                tag == "XBOX" && file.name.endsWith(".xiso.iso", ignoreCase = true) -> file.name.dropLast(".xiso.iso".length)
                else -> file.nameWithoutExtension
            }
            add(file, rawName)
        }
    }

    private fun isArcadeSupportDir(dir: File): Boolean =
        ARCADE_ROMSET_EXTENSIONS.any { File(dir.parentFile, "${dir.name}.$it").isFile }

    private fun findDirLaunchFile(dir: File, tag: String): File? {
        val referenced = referencesByTag[tag].orEmpty()
        val children = visibleEntries(dir).filter { it.isFile && accepts(tag, it) && it.canonicalPath !in referenced }
        val primary = MultiDisc.withoutCompanions(children)
        val playlist = primary.firstOrNull { MultiDisc.isPlaylist(it) && it.nameWithoutExtension.equals(dir.name, true) }
        if (playlist != null) {
            val refs = try {
                MultiDisc.playlistDiscs(playlist).mapTo(hashSetOf()) { it.canonicalPath }
            } catch (e: IOException) {
                ScanLog.write("playlist ${playlist.absolutePath}: ${e.message}")
                emptySet()
            }
            if (primary.all { it == playlist || it.canonicalPath in refs || it.nameWithoutExtension.equals(dir.name, true) }) {
                // Hidden support directories are fine; visible subdirectories may contain other games.
                if (visibleEntries(dir).none { it.isDirectory }) return playlist
            }
            return null
        }
        val group = MultiDisc.groups(primary, tag).singleOrNull()
        if (group != null && group.name.equals(dir.name, true) && group.discs.size == primary.size &&
            !pathsProvider.discGroupingDisabled(group.key) && visibleEntries(dir).none { it.isDirectory }) {
            return group.discs.first()
        }
        if (primary.isNotEmpty() && primary.all { it.nameWithoutExtension.equals(dir.name, true) }) {
            return primary.firstOrNull { it.extension.equals("cue", true) }
                ?: primary.maxBy { it.length() }
        }
        return null
    }

    private fun platformTagFor(file: File): String =
        PlatformFolders.canonicalTag(file.relativeToOrNull(romDirectory)?.path?.substringBefore(File.separatorChar).orEmpty())

    private fun isIgnored(file: File): Boolean =
        file.name.lowercase(Locale.ROOT) in ignoredFiles ||
            (file.isFile && file.extension.lowercase(Locale.ROOT) in ignoredExtensions)

    private fun seedFromAsset(assets: AssetManager, name: String, target: File) {
        if (target.exists()) return
        try {
            assets.open(name).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { input.copyTo(it) }
            }
        } catch (_: java.io.FileNotFoundException) {
            // Optional ignore-list assets are absent in some builds.
        } catch (e: IOException) {
            ScanLog.write("ignore list $name: ${e.message}")
        }
    }

    private fun readSetLowercase(file: File, transform: (String) -> String): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            file.readLines().map { transform(it.trim().lowercase(Locale.ROOT)) }.filter { it.isNotEmpty() }.toSet()
        } catch (e: IOException) {
            ScanLog.write("ignore list ${file.absolutePath}: ${e.message}")
            emptySet()
        }
    }

    private companion object {
        const val MAX_DEPTH = 16
        val ARCADE_ROMSET_EXTENSIONS = listOf("zip", "7z")
    }
}
