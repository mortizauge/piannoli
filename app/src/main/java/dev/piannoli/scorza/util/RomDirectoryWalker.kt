package dev.piannoli.scorza.util

import android.content.res.AssetManager
import dev.piannoli.scorza.config.PiannoliPaths
import dev.piannoli.scorza.config.PlatformFolders
import dev.piannoli.scorza.di.PiannoliPathsProvider
import java.io.File

/**
 * Walks a ROM directory and returns the in-memory list of ROMs that should exist for a platform.
 * Reorganizes loose multi-disc sets into per-game subfolders with a generated `<base>.m3u` so
 * libretro cores resolve disc paths correctly. Honors ignore lists, m3u/cue/stem-matching-rom
 * dir launches (collapsing only when all top-level files share the folder's name), disc grouping,
 * name-map overrides, and tag/region splitting.
 */
class RomDirectoryWalker(
    private val pathsProvider: PiannoliPathsProvider,
    private val assets: AssetManager,
    private val arcadeTitleLookup: ArcadeTitleLookup,
) {
    private val piannoliRoot: File get() = pathsProvider.root
    private val romDirectory: File get() = pathsProvider.romDir

    private val discRegex = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)
    private val cueFileLineRegex = Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+\w+\s*$""", RegexOption.IGNORE_CASE)

    private data class CachedFolders(val stamp: List<Pair<String, Long>>, val isArcade: Boolean, val showHidden: Boolean, val folders: List<String>)

    private val categoryFolderCache = java.util.concurrent.ConcurrentHashMap<String, CachedFolders>()

    @Volatile private var ignoredExtensions: Set<String> = emptySet()
    @Volatile private var ignoredFiles: Set<String> = emptySet()
    @Volatile private var ignoreListsLoaded = false

    data class ScannedRom(
        val relativePath: String,
        val displayName: String,
        val tags: String?,
    )

    /** A multi-disc set that the organizer relocated; `oldRelPath` is what the DB previously
     *  referenced (the first disc's path), `newRelPath` is the generated m3u. */
    data class RekeyMove(val oldRelPath: String, val newRelPath: String)

    private fun ensureIgnoreLists() {
        if (ignoreListsLoaded) return
        val paths = PiannoliPaths(piannoliRoot)
        seedFromAsset(assets, "ignore_extensions_roms.txt", paths.ignoreExtensionsRoms)
        seedFromAsset(assets, "ignore_files_roms.txt", paths.ignoreFilesRoms)
        ignoredExtensions = readSetLowercase(paths.ignoreExtensionsRoms) { it.removePrefix(".") }
        ignoredFiles = readSetLowercase(paths.ignoreFilesRoms) { it }
        ignoreListsLoaded = true
    }

    /** Returns null when the platform directory does not exist. */
    fun walk(platformTag: String, isArcade: Boolean): WalkResult? {
        ensureIgnoreLists()
        val tag = PlatformFolders.canonicalTag(platformTag)
        val directories = resolveTagDirs(tag)
        if (directories.isEmpty()) return null
        val rekeys = mutableListOf<RekeyMove>()
        val out = mutableListOf<ScannedRom>()
        for (dir in directories) {
            val prefix = "${dir.name}${File.separator}"
            organizeDir(dir, prefix, tag, rekeys, depth = 0)
            scanDir(dir, prefix, tag, isArcade, out, depth = 0)
        }
        return WalkResult(tagDir = directories.first(), roms = out, rekeys = rekeys, tagDirs = directories)
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
                    if (!renamed.renameTo(old)) throw java.io.IOException("Cannot restore ${old.absolutePath}")
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
            val entries = dir.listFiles() ?: throw java.io.IOException("Cannot read ${dir.absolutePath}")
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
        if (file.name.endsWith(".p8.png", ignoreCase = true)) "p8.png"
        else file.extension.lowercase(java.util.Locale.ROOT)

    private fun accepts(tag: String, file: File): Boolean =
        !isHidden(file) && PlatformFileTypes.accepts(tag, file) &&
            extensionOf(file) !in pathsProvider.filteredExtensions(tag)

    private fun isHidden(file: File): Boolean = !pathsProvider.showHiddenFiles && file.name.startsWith(".")

    fun invalidateNameMap(tagDir: File) = arcadeTitleLookup.invalidate(tagDir)

    /** Invalidates the cached folder list when a platform's contents change,
     *  since a folder created below the top level does not move the
     *  tag directory's own timestamp. */
    fun invalidateCategoryFolders(platformTag: String) {
        categoryFolderCache.remove(PlatformFolders.canonicalTag(platformTag))
    }

    /** Every category (non-game) folder under the platform, as platform-relative slash paths. */
    fun categoryFolders(platformTag: String, isArcade: Boolean): List<String> {
        val tag = PlatformFolders.canonicalTag(platformTag)
        val directories = resolveTagDirs(tag)
        // Finding the subfolders means stat-ing every entry, so a platform holding a few thousand
        // loose ROMs pays for all of them to discover a handful of directories, or none at all.
        // The timestamp catches top-level edits; deeper ones arrive via invalidateCategoryFolders.
        val stamp = directories.map { it.name to it.lastModified() }
        val showHidden = pathsProvider.showHiddenFiles
        categoryFolderCache[tag]?.let {
            if (it.stamp == stamp && it.isArcade == isArcade && it.showHidden == showHidden) return it.folders
        }
        val folders = directories.flatMap { walkCategoryFolders(it, tag, isArcade) }
            .distinct().sortedWith(compareBy { it.lowercase() })
        categoryFolderCache[tag] = CachedFolders(stamp, isArcade, showHidden, folders)
        return folders
    }

    private fun walkCategoryFolders(tagDir: File, tag: String, isArcade: Boolean): List<String> {
        ensureIgnoreLists()
        val out = mutableListOf<String>()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            val subdirs = dir.listFiles { f -> f.isDirectory && !isHidden(f) } ?: return
            for (sub in subdirs) {
                if (isArcade && isArcadeSupportDir(sub)) continue
                if (findDirLaunchFile(sub, tag) != null) continue
                val rel = if (prefix.isEmpty()) sub.name else "$prefix/${sub.name}"
                out.add(rel)
                walk(sub, rel, depth + 1)
            }
        }
        walk(tagDir, "", 0)
        return out.sortedWith(compareBy { it.lowercase() })
    }

    /** The dedicated on-disk folder for the game launched by [primaryFile], or null when the game
     *  is a loose file not contained in its own folder. */
    fun gameDirectory(primaryFile: File): File? {
        val parent = primaryFile.parentFile ?: return null
        if (parent.parentFile == romDirectory) return null
        val launch = findDirLaunchFile(parent) ?: return null
        return if (launch.file == primaryFile) parent else null
    }

    /** The `<romset>/` CHD folder belonging to an arcade game, which deleting the game must take
     *  with it. Null when the game has no such folder. */
    fun arcadeSupportDir(primaryFile: File, isArcade: Boolean): File? {
        if (!isArcade) return null
        if (primaryFile.extension.lowercase() !in ARCADE_ROMSET_EXTENSIONS) return null
        val parent = primaryFile.parentFile ?: return null
        return File(parent, primaryFile.nameWithoutExtension).takeIf { it.isDirectory }
    }

    /** Every on-disk file that makes up the game launched by [primaryFile]. */
    fun gameFiles(primaryFile: File): List<File> {
        gameDirectory(primaryFile)?.let { dir ->
            return dir.listFiles { f -> f.isFile }?.sortedBy { it.name.lowercase() }
                ?: listOf(primaryFile)
        }
        if (primaryFile.extension.equals("m3u", ignoreCase = true)) {
            val parent = primaryFile.parentFile
            if (parent != null) {
                val out = linkedSetOf(primaryFile)
                for (entry in readM3uEntries(primaryFile)) {
                    val disc = File(parent, entry)
                    if (disc.isFile) {
                        out.add(disc)
                        if (disc.extension.equals("cue", ignoreCase = true)) {
                            out.addAll(parseCueReferencedFiles(disc))
                        }
                    }
                }
                return out.toList()
            }
        }
        return listOf(primaryFile)
    }

    enum class RenameOutcome { RENAMED, NAME_TAKEN, FAILED }

    /** [newPrimary] is non-null exactly when [outcome] is RENAMED: the primary file's new
     *  location, cascaded to match the folder/file's new name. */
    data class RenameGameResult(val outcome: RenameOutcome, val newPrimary: File? = null)

    /** Renames a game. For a single-file game, renames the rom file. For a folder game, cascades:
     *  the folder, the launch file, the disc files, and the m3u contents all take the new name. */
    fun renameGame(primaryFile: File, newName: String): RenameGameResult {
        if (newName.isBlank()) return RenameGameResult(RenameOutcome.FAILED)

        val dir = gameDirectory(primaryFile)
        if (dir == null) {
            val currentBase = primaryFile.nameWithoutExtension
            if (newName.equals(currentBase, ignoreCase = true)) {
                return RenameGameResult(RenameOutcome.RENAMED, primaryFile)
            }
            val ext = primaryFile.extension
            val target = File(primaryFile.parentFile, if (ext.isEmpty()) newName else "$newName.$ext")
            if (target.exists()) return RenameGameResult(RenameOutcome.NAME_TAKEN)
            return if (primaryFile.renameTo(target)) {
                RenameGameResult(RenameOutcome.RENAMED, target)
            } else {
                RenameGameResult(RenameOutcome.FAILED)
            }
        }

        val oldBase = dir.name
        if (newName.equals(oldBase, ignoreCase = true)) return RenameGameResult(RenameOutcome.RENAMED, primaryFile)
        val newDir = File(dir.parentFile, newName)
        if (newDir.exists()) return RenameGameResult(RenameOutcome.NAME_TAKEN)

        if (!dir.renameTo(newDir)) return RenameGameResult(RenameOutcome.FAILED)

        val prefixed = newDir.listFiles { f ->
            f.isFile && f.name.startsWith(oldBase) && run {
                val rest = f.name.substring(oldBase.length)
                rest.isEmpty() || rest[0] == '.' || rest[0] == '(' || rest[0] == ' '
            }
        }.orEmpty()
        val renamed = mutableListOf<Pair<File, File>>()
        var newPrimary: File? = null
        for (file in prefixed) {
            val target = File(newDir, newName + file.name.removePrefix(oldBase))
            if (file.name == primaryFile.name) newPrimary = target
            if (file.renameTo(target)) {
                renamed.add(file to target)
            } else {
                for ((from, to) in renamed.asReversed()) to.renameTo(from)
                newDir.renameTo(dir)
                return RenameGameResult(RenameOutcome.FAILED)
            }
        }

        val m3u = File(newDir, "$newName.m3u")
        if (m3u.exists()) {
            val discs = newDir.listFiles { f ->
                f.isFile && discRegex.containsMatchIn(f.nameWithoutExtension)
            }.orEmpty().toList()
            val primaries = discs.groupBy { it.nameWithoutExtension }
                .values.map { pickPrimary(it) }.sortedBy { it.name }
            m3u.writeText(primaries.joinToString("\n") { it.name } + "\n")
        }

        return RenameGameResult(RenameOutcome.RENAMED, newPrimary ?: File(newDir, primaryFile.name))
    }

    data class WalkResult(
        val tagDir: File,
        val roms: List<ScannedRom>,
        val rekeys: List<RekeyMove> = emptyList(),
        val tagDirs: List<File> = listOf(tagDir),
    )

    private data class DirLaunch(val file: File)

    private fun scanDir(
        dir: File,
        relPrefix: String,
        tag: String,
        isArcade: Boolean,
        out: MutableList<ScannedRom>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles()?.filter { !isHidden(it) && !isIgnored(it) } ?: return
        val (subdirs, files) = entries.partition { it.isDirectory }

        for (subdir in subdirs) {
            if (isArcade && isArcadeSupportDir(subdir)) continue
            val launch = findDirLaunchFile(subdir, tag)
            if (launch != null) {
                val launchRel = "$relPrefix${subdir.name}${File.separator}${launch.file.name}"
                val (displayName, tags) = RomNaming.splitNameAndTags(subdir.name)
                out.add(ScannedRom(launchRel, displayName, tags))
            } else if (subdir.listFiles()?.any { !isHidden(it) } == true) {
                scanDir(subdir, "$relPrefix${subdir.name}${File.separator}", tag, isArcade, out, depth + 1)
            }
        }

        if (files.isEmpty()) return

        val romFiles = files.filter { accepts(tag, it) && !isIgnoredExtension(it) }
        val discCandidates = romFiles.filter {
            PlatformFileTypes.supports(tag, "m3u") && discRegex.containsMatchIn(it.nameWithoutExtension)
        }
        val discGroups = discCandidates.groupBy { discRegex.replace(it.nameWithoutExtension, "").trim() }
        val m3uByBase = romFiles
            .filter { it.extension.equals("m3u", ignoreCase = true) }
            .associateBy { it.nameWithoutExtension }

        // Discs belonging to a loose m3u in the same directory are represented by that m3u.
        val suppressed = discGroups
            .filter { (baseName, discs) -> discs.size > 1 && m3uByBase[baseName] != null }
            .values.flatten()
            .mapTo(mutableSetOf()) { it.absolutePath }

        val nameOverrides = arcadeTitleLookup.mapFor(dir, fallbackToArcade = isArcade)
        for (file in romFiles) {
            if (file.absolutePath in suppressed) continue
            val override = nameOverrides[file.name]
            val rawName = if (file.name.endsWith(".p8.png", ignoreCase = true)) {
                file.name.dropLast(".p8.png".length)
            } else {
                file.nameWithoutExtension
            }
            val (displayName, tags) = if (override != null) override to null else RomNaming.splitNameAndTags(rawName)
            out.add(ScannedRom("$relPrefix${file.name}", displayName, tags))
        }
    }

    // MAME and FBNeo read a romset's CHDs from `<romset>/` sitting beside `<romset>.zip`, so that
    // folder is support data for the archive rather than a game of its own.
    private fun isArcadeSupportDir(dir: File): Boolean {
        val parent = dir.parentFile ?: return false
        return ARCADE_ROMSET_EXTENSIONS.any { File(parent, "${dir.name}.$it").isFile }
    }

    private fun findDirLaunchFile(dir: File, tag: String = platformTagFor(dir)): DirLaunch? {
        val children = dir.listFiles()?.filter {
            it.isFile && !isHidden(it) && !isIgnored(it) && accepts(tag, it)
        } ?: return null
        children.firstOrNull { it.name.equals("${dir.name}.m3u", ignoreCase = true) }?.let { return DirLaunch(it) }
        children.firstOrNull { it.name.equals("${dir.name}.cue", ignoreCase = true) }?.let { return DirLaunch(it) }
        children.firstOrNull { it.extension.equals("cue", ignoreCase = true) }?.let { return DirLaunch(it) }
        val discs = children.filter {
            PlatformFileTypes.supports(tag, "m3u") && discRegex.containsMatchIn(it.nameWithoutExtension)
        }
        if (discs.size > 1) {
            return DirLaunch(discs.sortedBy { it.name }.first())
        }
        val named = children.filter { it.nameWithoutExtension.equals(dir.name, ignoreCase = true) }
        if (named.isNotEmpty() && named.size == children.size) {
            return DirLaunch(named.maxBy { it.length() })
        }
        return null
    }

    private fun platformTagFor(file: File): String =
        PlatformFolders.canonicalTag(file.relativeToOrNull(romDirectory)?.path?.substringBefore(File.separatorChar).orEmpty())

    private fun organizeDir(
        dir: File,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles()?.filter { !isHidden(it) && !isIgnored(it) } ?: return
        val (subdirs, files) = entries.partition { it.isDirectory }

        for (subdir in subdirs) {
            organizeDir(subdir, "$relPrefix${subdir.name}${File.separator}", tag, moves, depth + 1)
        }

        val romFiles = files.filter { accepts(tag, it) && !isIgnoredExtension(it) }
        val m3uByBase = romFiles.filter { it.extension.equals("m3u", ignoreCase = true) }
            .associateBy { it.nameWithoutExtension }

        val processed = mutableSetOf<File>()
        val discCandidates = romFiles.filter {
            PlatformFileTypes.supports(tag, "m3u") && discRegex.containsMatchIn(it.nameWithoutExtension)
        }
        val discGroups = discCandidates.groupBy { discRegex.replace(it.nameWithoutExtension, "").trim() }
        for ((baseName, groupFiles) in discGroups) {
            val byStem = groupFiles.groupBy { it.nameWithoutExtension }
            val existingSubdir = File(dir, baseName)
            if (existingSubdir.isDirectory) {
                if (mergeLooseDiscsIntoBundle(baseName, existingSubdir, groupFiles, files, tag)) {
                    processed.addAll(groupFiles)
                }
                continue
            }
            if (byStem.size <= 1) continue
            val looseM3u = m3uByBase[baseName]
            if (looseM3u != null) {
                if (relocateLooseM3uSet(dir, baseName, looseM3u, byStem, files, relPrefix, tag, moves)) {
                    processed.addAll(groupFiles)
                    processed.add(looseM3u)
                }
                continue
            }
            if (dir.name == baseName) {
                if (writeInPlaceM3u(dir, baseName, byStem, relPrefix, tag, moves)) {
                    processed.addAll(groupFiles)
                }
                continue
            }
            if (organizeMultiDisc(dir, baseName, byStem, files, relPrefix, tag, moves)) {
                processed.addAll(groupFiles)
            }
        }

        val remainingSiblings = romFiles.filter { it !in processed }
        val looseCues = remainingSiblings.filter {
            it.extension.equals("cue", ignoreCase = true) &&
                !discRegex.containsMatchIn(it.nameWithoutExtension)
        }
        for (cue in looseCues) {
            organizeSingleCue(dir, cue, files.filter { it !in processed }, relPrefix, tag, moves)
        }
    }

    private fun organizeMultiDisc(
        parent: File,
        baseName: String,
        discsByStem: Map<String, List<File>>,
        siblings: List<File>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ): Boolean {
        if (parent.name == baseName) return false
        val subdir = File(parent, baseName)
        if (!createSubdir(subdir, tag, baseName)) return false

        val primaries = discsByStem.values.map { pickPrimary(it) }.sortedBy { it.name }
        val allDiscFiles = discsByStem.values.flatten()
        val toMove = linkedSetOf<File>().apply {
            addAll(allDiscFiles)
            for (file in allDiscFiles) {
                addAll(stemSiblings(file, siblings))
                if (file.extension.equals("cue", ignoreCase = true)) {
                    addAll(parseCueReferencedFiles(file))
                }
            }
        }

        val moved = mutableListOf<Pair<File, File>>()
        if (!moveAll(toMove, subdir, moved, tag, baseName)) return false

        val m3uFile = File(subdir, "$baseName.m3u")
        try {
            m3uFile.writeText(primaries.joinToString("\n") { it.name } + "\n")
        } catch (e: Throwable) {
            ScanLog.write("organize $tag: failed to write $baseName.m3u: ${e.message}")
            rollback(moved, subdir)
            return false
        }

        val sortedAll = allDiscFiles.sortedBy { it.name }
        val firstStem = sortedAll.first().nameWithoutExtension
        if (firstStem != baseName) migrateSidecarFiles(tag, firstStem, baseName)

        val oldRel = "$relPrefix${sortedAll.first().name}"
        val newRel = "$relPrefix$baseName${File.separator}${m3uFile.name}"
        moves.add(RekeyMove(oldRel, newRel))
        ScanLog.write("organize $tag: bundled $baseName (${primaries.size} discs, ${toMove.size - primaries.size} companions)")
        return true
    }

    private fun readM3uEntries(m3u: File): List<String> = runCatching {
        m3u.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }.getOrDefault(emptyList())

    // Relocates a loose m3u set into its own folder; skips m3us whose entries are not flat siblings.
    private fun relocateLooseM3uSet(
        parent: File,
        baseName: String,
        m3u: File,
        discsByStem: Map<String, List<File>>,
        siblings: List<File>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ): Boolean {
        if (parent.name == baseName) return false
        val entries = readM3uEntries(m3u)
        if (entries.isEmpty() || entries.any { !File(parent, it).isFile }) return false

        val subdir = File(parent, baseName)
        if (!createSubdir(subdir, tag, baseName)) return false

        val allDiscFiles = discsByStem.values.flatten()
        val toMove = linkedSetOf<File>().apply {
            add(m3u)
            entries.forEach { add(File(parent, it)) }
            addAll(allDiscFiles)
            for (file in allDiscFiles) {
                addAll(stemSiblings(file, siblings))
                if (file.extension.equals("cue", ignoreCase = true)) {
                    addAll(parseCueReferencedFiles(file))
                }
            }
        }
        val moved = mutableListOf<Pair<File, File>>()
        if (!moveAll(toMove, subdir, moved, tag, baseName)) return false

        val firstStem = allDiscFiles.sortedBy { it.name }.first().nameWithoutExtension
        if (firstStem != baseName) migrateSidecarFiles(tag, firstStem, baseName)
        moves.add(RekeyMove("$relPrefix${m3u.name}", "$relPrefix$baseName${File.separator}${m3u.name}"))
        ScanLog.write("organize $tag: relocated loose m3u set $baseName (${allDiscFiles.size} discs)")
        return true
    }

    private fun writeInPlaceM3u(
        dir: File,
        baseName: String,
        discsByStem: Map<String, List<File>>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ): Boolean {
        val allDiscFiles = discsByStem.values.flatten()
        val primaries = discsByStem.values.map { pickPrimary(it) }.sortedBy { it.name }
        if (primaries.size <= 1) return false
        val m3uFile = File(dir, "$baseName.m3u")
        try {
            m3uFile.writeText(primaries.joinToString("\n") { it.name } + "\n")
        } catch (e: Throwable) {
            ScanLog.write("organize $tag: failed to write in-place $baseName.m3u: ${e.message}")
            return false
        }
        val firstDisc = allDiscFiles.sortedBy { it.name }.first()
        val firstStem = firstDisc.nameWithoutExtension
        if (firstStem != baseName) migrateSidecarFiles(tag, firstStem, baseName)
        moves.add(RekeyMove("$relPrefix${firstDisc.name}", "$relPrefix${m3uFile.name}"))
        ScanLog.write("organize $tag: wrote in-place m3u for $baseName (${primaries.size} discs)")
        return true
    }

    // Moves late-arriving loose discs into an existing `<baseName>/` bundle and rewrites its m3u,
    // so incremental uploads (a disc landing after the bundle was already organized) still converge.
    private fun mergeLooseDiscsIntoBundle(
        baseName: String,
        subdir: File,
        looseDiscs: List<File>,
        siblings: List<File>,
        tag: String,
    ): Boolean {
        val m3uFile = File(subdir, "$baseName.m3u")
        // Also merge into a folder that already holds this game's discs but has no m3u yet.
        if (!m3uFile.exists() && !folderHoldsDiscsOf(subdir, baseName, tag)) return false

        val toMove = linkedSetOf<File>().apply {
            addAll(looseDiscs)
            for (file in looseDiscs) {
                addAll(stemSiblings(file, siblings))
                if (file.extension.equals("cue", ignoreCase = true)) {
                    addAll(parseCueReferencedFiles(file))
                }
            }
        }
        val moved = mutableListOf<Pair<File, File>>()
        if (!moveAll(toMove, subdir, moved, tag, baseName)) return false

        val discFiles = subdir.listFiles()?.filter {
            it.isFile && !isIgnored(it) && accepts(tag, it) &&
                discRegex.containsMatchIn(it.nameWithoutExtension)
        }.orEmpty()
        val primaries = discFiles.groupBy { it.nameWithoutExtension }
            .values.map { pickPrimary(it) }.sortedBy { it.name }
        if (primaries.isNotEmpty()) {
            try {
                m3uFile.writeText(primaries.joinToString("\n") { it.name } + "\n")
            } catch (e: Throwable) {
                ScanLog.write("organize $tag: failed to rewrite $baseName.m3u: ${e.message}")
            }
        }
        for (disc in discFiles) {
            val stem = disc.nameWithoutExtension
            if (stem != baseName) migrateSidecarFiles(tag, stem, baseName)
        }
        ScanLog.write("organize $tag: merged ${looseDiscs.size} loose disc(s) into $baseName/")
        return true
    }

    private fun folderHoldsDiscsOf(subdir: File, baseName: String, tag: String): Boolean =
        subdir.listFiles()?.any {
            it.isFile && !isIgnored(it) && accepts(tag, it) &&
                discRegex.containsMatchIn(it.nameWithoutExtension) &&
                discRegex.replace(it.nameWithoutExtension, "").trim() == baseName
        } == true

    private fun organizeSingleCue(
        parent: File,
        cue: File,
        siblings: List<File>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ) {
        val baseName = cue.nameWithoutExtension
        if (parent.name == baseName) return
        val subdir = File(parent, baseName)
        if (!createSubdir(subdir, tag, baseName)) return

        val toMove = linkedSetOf<File>().apply {
            add(cue)
            addAll(stemSiblings(cue, siblings))
            addAll(parseCueReferencedFiles(cue))
        }
        val moved = mutableListOf<Pair<File, File>>()
        if (!moveAll(toMove, subdir, moved, tag, baseName)) return

        val oldRel = "$relPrefix${cue.name}"
        val newRel = "$relPrefix$baseName${File.separator}${cue.name}"
        moves.add(RekeyMove(oldRel, newRel))
        ScanLog.write("organize $tag: bundled single-disc $baseName (${toMove.size - 1} companions)")
    }

    private fun createSubdir(subdir: File, tag: String, baseName: String): Boolean {
        if (subdir.exists()) {
            ScanLog.write("organize $tag: skip $baseName (target subfolder already exists)")
            return false
        }
        if (!subdir.mkdir()) {
            ScanLog.write("organize $tag: failed to mkdir $baseName")
            return false
        }
        return true
    }

    private fun moveAll(
        toMove: Collection<File>,
        subdir: File,
        moved: MutableList<Pair<File, File>>,
        tag: String,
        baseName: String,
    ): Boolean {
        for (file in toMove) {
            val target = File(subdir, file.name)
            if (file.renameTo(target)) {
                moved.add(file to target)
            } else {
                ScanLog.write("organize $tag: failed to move ${file.name} into $baseName/")
                rollback(moved, subdir)
                return false
            }
        }
        return true
    }

    private fun rollback(moved: List<Pair<File, File>>, subdir: File) {
        for ((src, dst) in moved) dst.renameTo(src)
        subdir.delete()
    }

    private fun pickPrimary(files: List<File>): File {
        return files.minByOrNull { f ->
            val idx = PRIMARY_DISC_EXTENSIONS.indexOf(f.extension.lowercase())
            if (idx >= 0) idx else Int.MAX_VALUE
        } ?: files.first()
    }

    private fun stemSiblings(disc: File, siblings: List<File>): List<File> {
        val stem = disc.nameWithoutExtension
        return siblings.filter { other ->
            if (other == disc || other.isDirectory) return@filter false
            val otherStem = other.nameWithoutExtension
            otherStem == stem ||
                otherStem.startsWith("$stem ") ||
                otherStem.startsWith("$stem.") ||
                other.name.startsWith("$stem.")
        }
    }

    private fun parseCueReferencedFiles(cue: File): List<File> {
        val parent = cue.parentFile ?: return emptyList()
        return try {
            cue.useLines { lines ->
                lines.mapNotNull { line ->
                    val match = cueFileLineRegex.find(line) ?: return@mapNotNull null
                    val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    File(parent, name).takeIf { it.isFile }
                }.toList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun migrateSidecarFiles(tag: String, fromStem: String, toStem: String) {
        val paths = PiannoliPaths(piannoliRoot)
        renameStemMatchedFiles(paths.savesFor(tag), fromStem, toStem)
        val statesTagDir = paths.saveStatesFor(tag)
        renameStemMatchedFiles(statesTagDir, fromStem, toStem)
        val stateSub = File(statesTagDir, fromStem)
        val stateSubTarget = File(statesTagDir, toStem)
        if (stateSub.isDirectory && !stateSubTarget.exists() && stateSub.renameTo(stateSubTarget)) {
            renameStemMatchedFiles(stateSubTarget, fromStem, toStem)
        }
    }

    private fun renameStemMatchedFiles(dir: File, fromStem: String, toStem: String) {
        if (!dir.isDirectory) return
        val matches = dir.listFiles()?.filter { f ->
            if (!f.isFile) return@filter false
            val n = f.nameWithoutExtension
            n == fromStem || n.startsWith("$fromStem.")
        } ?: return
        for (f in matches) {
            val newName = toStem + f.name.substring(fromStem.length)
            val target = File(dir, newName)
            if (!target.exists()) f.renameTo(target)
        }
    }

    private fun isIgnored(file: File): Boolean =
        file.name.lowercase() in ignoredFiles ||
            (file.isFile && file.extension.lowercase() in ignoredExtensions)

    private fun isIgnoredExtension(file: File): Boolean =
        file.extension.lowercase() in ignoredExtensions

    private fun seedFromAsset(assets: AssetManager, name: String, target: File) {
        if (target.exists()) return
        try {
            target.parentFile?.mkdirs()
            assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (_: Throwable) { }
    }

    private fun readSetLowercase(file: File, transform: (String) -> String): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            file.readLines().map { transform(it.trim().lowercase()) }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    private companion object {
        const val MAX_DEPTH = 16
        val PRIMARY_DISC_EXTENSIONS = listOf("cue", "chd", "gdi", "toc", "ccd", "iso", "img", "pbp", "bin")
        val ARCADE_ROMSET_EXTENSIONS = listOf("zip", "7z")
    }
}
