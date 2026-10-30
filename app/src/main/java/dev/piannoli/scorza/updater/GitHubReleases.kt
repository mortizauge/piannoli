package dev.piannoli.scorza.updater

import dev.piannoli.scorza.util.StorageLog
import org.json.JSONArray
import java.math.BigInteger
import java.net.URI

internal data class ReleaseVersion(
    private val major: BigInteger,
    private val minor: BigInteger,
    private val patch: BigInteger,
    private val prerelease: List<String>
) : Comparable<ReleaseVersion> {
    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: ReleaseVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            val comparison = when {
                leftNumeric && rightNumeric -> BigInteger(left).compareTo(BigInteger(right))
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex(
            """^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"""
        )

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val identifiers = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.').orEmpty()
            if (identifiers.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
            return ReleaseVersion(
                BigInteger(match.groupValues[1]),
                BigInteger(match.groupValues[2]),
                BigInteger(match.groupValues[3]),
                identifiers
            )
        }
    }
}

internal object GitHubReleases {
    const val OWNER = "mortizauge"
    const val REPOSITORY = "piannoli"
    const val SOURCE = "github.com/$OWNER/$REPOSITORY"
    private const val DOWNLOAD_PATH = "/$OWNER/$REPOSITORY/releases/download/"

    fun parsePage(json: JSONArray, channel: ReleaseChannel, supportedAbis: List<String>): List<UpdateInfo> =
        buildList {
            for (index in 0 until json.length()) {
                val release = json.optJSONObject(index) ?: continue
                if (release.optBoolean("draft", false)) continue
                val tag = release.optString("tag_name", "")
                val versionName = tag.removePrefix("v").removePrefix("V")
                val version = ReleaseVersion.parse(tag)
                if (version == null) {
                    StorageLog.write("ignoring GitHub release with invalid version tag: $tag")
                    continue
                }
                val prerelease = release.optBoolean("prerelease", false)
                if (channel == ReleaseChannel.STABLE && prerelease) continue
                val asset = selectApk(release.optJSONArray("assets") ?: JSONArray(), supportedAbis) ?: continue
                add(
                    UpdateInfo(
                        versionName = versionName,
                        tag = tag,
                        apk = asset.name,
                        downloadUrl = asset.url,
                        changelog = release.optString("body", ""),
                        isPrerelease = prerelease
                    )
                )
            }
        }

    fun isEligible(
        info: UpdateInfo,
        installedVersion: String,
        channel: ReleaseChannel,
        cacheSource: String,
        cacheChannel: String
    ): Boolean {
        if (cacheSource != SOURCE || cacheChannel != channel.name) return false
        if (channel == ReleaseChannel.STABLE && info.isPrerelease) return false
        val installed = ReleaseVersion.parse(installedVersion) ?: return false
        val candidate = ReleaseVersion.parse(info.versionName) ?: return false
        val tagged = ReleaseVersion.parse(info.tag) ?: return false
        return candidate == tagged &&
            candidate > installed &&
            isSafeApkName(info.apk) &&
            isRepositoryDownloadUrl(info.downloadUrl)
    }

    fun isSafeApkName(name: String): Boolean =
        name.matches(Regex("(?i)^piannoli-[a-z0-9._+-]+\\.apk$")) &&
            !name.contains("..") &&
            !name.contains('/') &&
            !name.contains('\\')

    private data class ApkAsset(val name: String, val url: String, val rank: Int)

    private fun selectApk(assets: JSONArray, supportedAbis: List<String>): ApkAsset? {
        val candidates = mutableListOf<ApkAsset>()
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name", "")
            if (!isSafeApkName(name)) continue
            val url = asset.optString("browser_download_url", "")
            if (!isRepositoryDownloadUrl(url)) continue

            val namedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                .filter { name.contains(it, ignoreCase = true) }
            val rank = if (namedAbis.isEmpty()) {
                supportedAbis.size
            } else {
                namedAbis.mapNotNull { supportedAbis.indexOfOrNull(it) }.minOrNull() ?: continue
            }
            candidates += ApkAsset(name, url, rank)
        }
        return candidates.minByOrNull { it.rank }
    }

    private fun List<String>.indexOfOrNull(value: String): Int? =
        indexOfFirst { it.equals(value, ignoreCase = true) }.takeIf { it >= 0 }

    fun isRepositoryDownloadUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.path.startsWith(DOWNLOAD_PATH)
    }.getOrDefault(false)
}
