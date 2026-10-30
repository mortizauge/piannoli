package dev.piannoli.scorza.updater

data class UpdateInfo(
    val versionName: String,
    val tag: String,
    val apk: String,
    val downloadUrl: String,
    val changelog: String,
    val isPrerelease: Boolean
)
