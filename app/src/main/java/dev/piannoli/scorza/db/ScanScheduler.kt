package dev.piannoli.scorza.db

import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.config.PlatformFolders
import dev.piannoli.scorza.util.ScanLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanScheduler @Inject constructor(
    private val romScanner: RomScanner,
    private val platformConfig: PlatformConfig,
) {
    data class ScanResult(
        val platformTag: String,
        val counts: RomScanner.SyncCounts,
        val silent: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<String>(capacity = Channel.UNLIMITED)

    private val queued = mutableSetOf<String>()
    private val rerunNeeded = mutableSetOf<String>()
    private var runningTag: String? = null
    private val mutex = Any()

    private val _results = MutableSharedFlow<ScanResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ScanResult> = _results.asSharedFlow()

    init {
        scope.launch { worker() }
    }

    fun runNow(platformTag: String): RomScanner.SyncCounts {
        return runNow(platformTag, refresh = false)
    }

    fun runNowForRefresh(platformTag: String): RomScanner.SyncCounts =
        runNow(platformTag, refresh = true)

    private fun runNow(platformTag: String, refresh: Boolean): RomScanner.SyncCounts {
        val tag = PlatformFolders.canonicalTag(platformTag)
        synchronized(mutex) {
            while (runningTag != null || tag in queued) {
                (mutex as java.lang.Object).wait()
            }
            runningTag = tag
        }
        var counts = RomScanner.SyncCounts(0, 0, 0)
        try {
            counts = if (refresh) romScanner.scanPlatformForRefresh(tag, isArcade = platformConfig.isArcade(tag))
                else romScanner.scanPlatform(tag, isArcade = platformConfig.isArcade(tag))
            return counts
        } catch (e: Exception) {
            ScanLog.write("ScanScheduler runNow $tag failed: ${e.message}")
            if (refresh) throw e
            return counts
        } finally {
            finishScan(tag, counts) { result ->
                if (!refresh) scope.launch { _results.tryEmit(result) }
            }
        }
    }

    private inline fun finishScan(tag: String, counts: RomScanner.SyncCounts, emit: (ScanResult) -> Unit) {
        val needsRerun = synchronized(mutex) {
            runningTag = null
            val rerun = rerunNeeded.remove(tag)
            (mutex as java.lang.Object).notifyAll()
            rerun
        }
        val silent = romScanner.consumeLauncherMutation(tag)
        if (counts.inserted + counts.updated + counts.removed > 0) {
            emit(ScanResult(tag, counts, silent))
        }
        if (needsRerun) enqueue(tag)
    }

    fun markLauncherMutation(platformTag: String) = romScanner.markLauncherMutation(platformTag)

    fun enqueue(platformTag: String) {
        val tag = PlatformFolders.canonicalTag(platformTag)
        synchronized(mutex) {
            if (runningTag == tag) {
                rerunNeeded.add(tag)
                return
            }
            if (!queued.add(tag)) return
        }
        requests.trySend(tag)
    }

    private suspend fun worker() {
        for (tag in requests) {
            synchronized(mutex) {
                while (runningTag != null) {
                    (mutex as java.lang.Object).wait()
                }
                queued.remove(tag)
                runningTag = tag
            }
            val counts = try {
                romScanner.scanPlatform(tag, isArcade = platformConfig.isArcade(tag))
            } catch (t: Throwable) {
                ScanLog.write("ScanScheduler $tag failed: ${t.message}")
                RomScanner.SyncCounts(0, 0, 0)
            }
            finishScan(tag, counts) { result -> _results.tryEmit(result) }
        }
    }
}
