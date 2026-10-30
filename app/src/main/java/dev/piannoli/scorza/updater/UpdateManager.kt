package dev.piannoli.scorza.updater

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.piannoli.scorza.BuildConfig
import dev.piannoli.scorza.R
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.util.StorageLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {
    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(loadCached())
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable

    private val _downloadProgress = MutableStateFlow(-1f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError

    @Volatile
    private var downloadCancelled = false

    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun loadCached(): UpdateInfo? {
        val version = settings.cachedUpdateVersion
        val tag = settings.cachedUpdateTag
        val apk = settings.cachedUpdateApk
        val changelog = settings.cachedUpdateChangelog
        val url = settings.cachedUpdateUrl
        val channel = ReleaseChannel.fromString(settings.releaseChannel)
        val info = if (version.isNotEmpty() && tag.isNotEmpty() && apk.isNotEmpty() && url.isNotEmpty()) {
            UpdateInfo(version, tag, apk, url, changelog, settings.cachedUpdatePrerelease)
        } else {
            null
        }
        if (info == null || !GitHubReleases.isEligible(
                info,
                BuildConfig.VERSION_NAME,
                channel,
                settings.cachedUpdateSource,
                settings.cachedUpdateChannel
            )
        ) {
            if (version.isNotEmpty() || tag.isNotEmpty() || apk.isNotEmpty() || url.isNotEmpty()) {
                cacheUpdate(null)
            }
            return null
        }
        return info
    }

    private fun cacheUpdate(info: UpdateInfo?, channel: ReleaseChannel = ReleaseChannel.fromString(settings.releaseChannel)) {
        if (info != null) {
            settings.cachedUpdateVersion = info.versionName
            settings.cachedUpdateCode = 0
            settings.cachedUpdateTag = info.tag
            settings.cachedUpdateApk = info.apk
            settings.cachedUpdateChangelog = info.changelog
            settings.cachedUpdateUrl = info.downloadUrl
            settings.cachedUpdatePrerelease = info.isPrerelease
            settings.cachedUpdateSource = GitHubReleases.SOURCE
            settings.cachedUpdateChannel = channel.name
        } else {
            settings.cachedUpdateVersion = ""
            settings.cachedUpdateCode = 0
            settings.cachedUpdateTag = ""
            settings.cachedUpdateApk = ""
            settings.cachedUpdateChangelog = ""
            settings.cachedUpdateUrl = ""
            settings.cachedUpdatePrerelease = false
            settings.cachedUpdateSource = GitHubReleases.SOURCE
            settings.cachedUpdateChannel = channel.name
        }
    }

    fun shouldAutoCheck(): Boolean {
        if (!isOnline()) return false
        val channel = ReleaseChannel.fromString(settings.releaseChannel)
        if (settings.lastUpdateCheckSource != GitHubReleases.SOURCE ||
            settings.lastUpdateCheckChannel != channel.name
        ) return true
        val elapsed = System.currentTimeMillis() - settings.lastUpdateCheck
        return elapsed > 2 * 60 * 60 * 1000L
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        // Skip when offline and dedupe concurrent checks (e.g. holding Left/Right on the
        // release_channel row auto-repeats); otherwise each press would race a network fetch.
        val channel = ReleaseChannel.fromString(settings.releaseChannel)
        if (settings.cachedUpdateChannel != channel.name) {
            cacheUpdate(null, channel)
            _updateAvailable.value = null
        }
        if (!isOnline()) return@withContext _updateAvailable.value
        if (!checking.compareAndSet(false, true)) return@withContext _updateAvailable.value
        try {
            val installed = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
            if (installed == null) {
                StorageLog.write("update check skipped: invalid installed version ${BuildConfig.VERSION_NAME}")
                return@withContext _updateAvailable.value
            }
            val releases = mutableListOf<UpdateInfo>()
            var page = 1
            while (true) {
                val json = fetchReleasePage(page)
                releases += GitHubReleases.parsePage(json, channel, android.os.Build.SUPPORTED_ABIS.toList())
                if (json.length() < RELEASES_PER_PAGE) break
                page++
            }
            settings.lastUpdateCheck = System.currentTimeMillis()
            settings.lastUpdateCheckSource = GitHubReleases.SOURCE
            settings.lastUpdateCheckChannel = channel.name
            val result = releases
                .filter { candidate ->
                    ReleaseVersion.parse(candidate.versionName)?.let { it > installed } == true
                }
                .maxWithOrNull(compareBy<UpdateInfo> { ReleaseVersion.parse(it.versionName) }
                    .thenBy { !it.isPrerelease })
            cacheUpdate(result, channel)
            _updateAvailable.value = result
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StorageLog.write("update check failed: ${e.message ?: e.javaClass.simpleName}")
            _updateAvailable.value
        } finally {
            checking.set(false)
        }
    }

    private fun fetchReleasePage(page: Int): JSONArray {
        val url = URL("https://api.github.com/repos/${GitHubReleases.OWNER}/${GitHubReleases.REPOSITORY}/releases?per_page=$RELEASES_PER_PAGE&page=$page")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Piannoli-Android")
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("GitHub API returned HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { JSONArray(it.readText()) }
        } finally {
            conn.disconnect()
        }
    }

    // The install runs in the system installer after downloadAndInstall returns, so the APK
    // cannot be deleted at the call site. Nothing else reclaims it, and the filename carries
    // the version, so every update used to strand another ~300 MB here. No install from this
    // process can be in flight at startup, which makes it the one safe place to sweep.
    suspend fun purgeStaleDownloads() = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates")
        val freed = dir.listFiles().orEmpty().sumOf { f ->
            val size = f.length()
            if (f.isFile && f.delete()) size else 0L
        }
        if (freed > 0) StorageLog.write("purged $freed bytes of stale update downloads")
    }

    suspend fun downloadAndInstall(info: UpdateInfo) = withContext(Dispatchers.IO) {
        downloadCancelled = false
        _downloadError.value = null
        _downloadProgress.value = 0f
        val cacheDir = File(context.cacheDir, "updates")
        cacheDir.mkdirs()
        if (!GitHubReleases.isSafeApkName(info.apk) ||
            !GitHubReleases.isRepositoryDownloadUrl(info.downloadUrl)
        ) {
            _downloadError.value = context.getString(R.string.update_download_error_network)
            _downloadProgress.value = -1f
            return@withContext
        }
        val apkFile = File(cacheDir, info.apk)
        try {
            val url = URL(info.downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                _downloadError.value = context.getString(R.string.update_download_error_server)
                _downloadProgress.value = -1f
                return@withContext
            }
            val total = conn.contentLength.toLong()
            val available = availableBytes()
            if (!hasRoom(total, available)) {
                _downloadError.value = context.getString(
                    R.string.update_download_error_space,
                    Formatter.formatShortFileSize(context, requiredFor(total)),
                    Formatter.formatShortFileSize(context, available),
                )
                _downloadProgress.value = -1f
                return@withContext
            }
            var downloaded = 0L
            conn.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        if (downloadCancelled) {
                            apkFile.delete()
                            _downloadProgress.value = -1f
                            return@withContext
                        }
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) _downloadProgress.value = downloaded.toFloat() / total
                    }
                }
            }
            _downloadProgress.value = 1f
            withContext(Dispatchers.Main) { installApk(apkFile) }
        } catch (_: Exception) {
            apkFile.delete()
            if (!downloadCancelled) {
                _downloadError.value = context.getString(R.string.update_download_error_network)
            }
            _downloadProgress.value = -1f
        }
    }

    // A StatFs failure must not block an update, so an unreadable partition reads as unlimited.
    private fun availableBytes(): Long =
        runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun cancelDownload() {
        downloadCancelled = true
    }

    fun clearError() {
        _downloadError.value = null
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val RELEASES_PER_PAGE = 100

        private const val INSTALL_HEADROOM_BYTES = 48L * 1024 * 1024

        // The system installer stages its own copy of the APK on the same partition, and the
        // old version is not released until the new one commits, so the download is only half
        // of what the install actually needs. Headroom covers dexopt output.
        internal fun requiredFor(contentLength: Long) = contentLength * 2 + INSTALL_HEADROOM_BYTES

        // A server that sends no Content-Length leaves nothing to check against, so the
        // download proceeds rather than being blocked on a number we do not have.
        internal fun hasRoom(contentLength: Long, available: Long): Boolean =
            contentLength <= 0L || available >= requiredFor(contentLength)
    }

}
