package dev.piannoli.scorza.scanner

import android.os.FileObserver
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.config.PlatformFolders
import dev.piannoli.scorza.db.ScanScheduler
import dev.piannoli.scorza.util.ScanLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RomDirectoryWatcher @Inject constructor(
    private val scanScheduler: ScanScheduler,
    private val platformConfig: PlatformConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val debounceJobs = ConcurrentHashMap<String, Job>()
    private var rootObserver: FileObserver? = null
    private var watchedRoot: File? = null
    private var generation = 0L
    private val deferredScans = DeferredScanRequests(scanScheduler::enqueue)

    fun setLauncherActive(active: Boolean) = deferredScans.setActive(active)

    private val perPlatformMask =
        FileObserver.CREATE or FileObserver.DELETE or
        FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
        FileObserver.CLOSE_WRITE

    private val rootMask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.MOVED_FROM

    private val debounceMs = 750L

    @Synchronized
    fun start(romDir: File, platformTags: List<String>) {
        val pendingTags = if (watchedRoot == romDir) debounceJobs.keys.toList() else emptyList()
        stopObservers()
        if (watchedRoot != romDir) deferredScans.clear()
        watchedRoot = romDir
        if (!romDir.exists()) {
            ScanLog.write("RomDirectoryWatcher: rom dir does not exist: ${romDir.absolutePath}")
            return
        }
        startRootObserver(romDir)
        for (tag in platformTags.map(PlatformFolders::canonicalTag).distinct()) {
            PlatformFolders.directories(romDir, tag).forEach { startPlatformObserver(it, tag) }
        }
        pendingTags.forEach(::scheduleScan)
    }

    fun restart(romDir: File, platformTags: List<String>) = start(romDir, platformTags)

    @Synchronized
    fun stop() {
        stopObservers()
        watchedRoot = null
        deferredScans.clear()
    }

    private fun stopObservers() {
        generation++
        rootObserver?.stopWatching()
        rootObserver = null
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
    }

    // String-path constructor (deprecated but available since API 1); the File-based
    // overload requires API 29 and would crash on minSdk 28.
    @Suppress("DEPRECATION")
    private fun startRootObserver(romDir: File) {
        val epoch = generation
        val obs = object : FileObserver(romDir.absolutePath, rootMask) {
            override fun onEvent(event: Int, path: String?) {
                synchronized(this@RomDirectoryWatcher) {
                    if (generation != epoch) return
                    val name = path ?: return
                    val child = File(romDir, name)
                    if (name.startsWith(".")) return
                    val tag = PlatformFolders.canonicalTag(name)
                    if (!platformConfig.isKnownTag(tag)) return
                    if (child.isDirectory) {
                        if (!observers.containsKey(child.absolutePath)) startPlatformObserver(child, tag)
                    } else {
                        observers.remove(child.absolutePath)?.stopWatching()
                    }
                    scheduleScan(tag)
                }
            }
        }
        rootObserver = obs
        try {
            obs.startWatching()
        } catch (t: Throwable) {
            ScanLog.write("RomDirectoryWatcher: startWatching(root) failed: ${t.message}")
            rootObserver = null
        }
    }

    @Suppress("DEPRECATION")
    private fun startPlatformObserver(dir: File, tag: String) {
        if (!dir.isDirectory) return
        val epoch = generation
        val obs = object : FileObserver(dir.absolutePath, perPlatformMask) {
            override fun onEvent(event: Int, path: String?) {
                synchronized(this@RomDirectoryWatcher) {
                    if (generation == epoch) scheduleScan(tag)
                }
            }
        }
        try {
            obs.startWatching()
            observers[dir.absolutePath] = obs
        } catch (t: Throwable) {
            ScanLog.write("RomDirectoryWatcher: startWatching($tag) failed: ${t.message}")
        }
    }

    @Synchronized
    private fun scheduleScan(tag: String) {
        debounceJobs[tag]?.cancel()
        debounceJobs[tag] = scope.launch {
            delay(debounceMs)
            synchronized(this@RomDirectoryWatcher) {
                if (debounceJobs[tag] === coroutineContext[Job]) {
                    debounceJobs.remove(tag)
                    deferredScans.request(tag)
                }
            }
        }
    }
}
