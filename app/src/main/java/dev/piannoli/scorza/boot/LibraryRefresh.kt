package dev.piannoli.scorza.boot

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.piannoli.scorza.R
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.config.PlatformFolders
import dev.piannoli.scorza.db.ScanScheduler
import dev.piannoli.scorza.di.IoScope
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.util.ScanLog
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class LibraryRefresh @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paths: PiannoliPathsProvider,
    private val platformConfig: PlatformConfig,
    private val scanScheduler: ScanScheduler,
    @IoScope private val scope: CoroutineScope,
) {
    data class Result(
        val id: Long,
        val root: String,
        val romDirectory: String,
        val newGames: Int,
        val removedGames: Int,
        val changed: Boolean,
        val issue: String?,
    ) {
        fun message(context: Context): String? {
            val messages = buildList {
                if (newGames > 0) add(context.resources.getQuantityString(R.plurals.library_refresh_new_games, newGames, newGames))
                if (removedGames > 0) add(context.resources.getQuantityString(R.plurals.library_refresh_removed_games, removedGames, removedGames))
                if (issue != null) add(context.getString(R.string.library_refresh_failed, issue))
            }
            return messages.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    private val _result = MutableStateFlow<Result?>(null)
    val result = _result.asStateFlow()
    private var requestedLibrary: Pair<String, String>? = null
    private var refreshJob: Job? = null
    private var nextId = 0L
    private var notifiedId = 0L
    private val pending = context.getSharedPreferences("library_refresh", Context.MODE_PRIVATE)

    private fun pendingKey(library: Pair<String, String>): String =
        "${library.first.length}:${library.first}${library.second}"

    private fun savePending(library: Pair<String, String>, added: Int, removed: Int) {
        val key = pendingKey(library)
        if (!pending.edit().putInt("$key:added", added).putInt("$key:removed", removed).commit()) {
            throw IOException(context.getString(R.string.library_refresh_notification_save_failed))
        }
    }

    @Synchronized
    fun retryIfFailed() {
        val previous = _result.value ?: return
        if (previous.issue != null &&
            previous.root == paths.root.absolutePath &&
            previous.romDirectory == paths.romDir.absolutePath) {
            start(paths.root, paths.romDir)
        }
    }

    @Synchronized
    fun start(root: File, romDirectory: File, importedGames: Int = 0) {
        val library = root.absolutePath to romDirectory.absolutePath
        if (requestedLibrary == library && (refreshJob?.isActive == true || _result.value?.issue == null)) return
        val previousChanges = requestedLibrary == library && _result.value?.changed == true
        refreshJob?.cancel()
        requestedLibrary = library
        _result.value = null
        val id = ++nextId
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val task = currentCoroutineContext()
            fun ensureCurrentLibrary() {
                task.ensureActive()
                if (paths.root.absolutePath != library.first || paths.romDir.absolutePath != library.second) {
                    throw CancellationException("Library changed during automatic refresh")
                }
            }
            val key = pendingKey(library)
            var newGames = pending.getInt("$key:added", 0) + importedGames
            var removedGames = pending.getInt("$key:removed", 0)
            var changed = previousChanges || newGames + removedGames > 0
            var issue: String? = null
            try {
                ensureCurrentLibrary()
                if (importedGames > 0) savePending(library, newGames, removedGames)
                val directories = romDirectory.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
                    ?: throw IOException(context.getString(R.string.library_refresh_unreadable))
                for (tag in PlatformFolders.scanTags(directories.toList(), platformConfig::isKnownTag)) {
                    ensureCurrentLibrary()
                    try {
                        val counts = scanScheduler.runNowForRefresh(tag)
                        newGames += counts.inserted
                        removedGames += counts.removed
                        changed = changed || counts.inserted + counts.updated + counts.removed > 0
                        if (counts.inserted + counts.removed > 0) savePending(library, newGames, removedGames)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ScanLog.write("ERROR automatic refresh $tag: ${e.message}")
                        if (issue == null) issue = context.getString(R.string.library_refresh_platform_failed, tag)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScanLog.write("ERROR automatic library refresh: ${e.message}")
                issue = e.message ?: context.getString(R.string.library_refresh_error)
            }
            ensureCurrentLibrary()
            synchronized(this@LibraryRefresh) {
                if (requestedLibrary == library && nextId == id) {
                    _result.value = Result(id, library.first, library.second, newGames, removedGames, changed, issue)
                }
            }
        }
        refreshJob = job
        job.invokeOnCompletion {
            synchronized(this) { if (refreshJob === job) refreshJob = null }
        }
        job.start()
    }

    suspend fun claimNotification(id: Long): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@LibraryRefresh) {
            val current = _result.value
            if (current?.id != id || notifiedId == id) return@synchronized false
            try {
                savePending(current.root to current.romDirectory, 0, 0)
            } catch (e: IOException) {
                ScanLog.write("ERROR acknowledging library refresh notification: ${e.message}")
                return@synchronized false
            }
            notifiedId = id
            true
        }
    }
}
