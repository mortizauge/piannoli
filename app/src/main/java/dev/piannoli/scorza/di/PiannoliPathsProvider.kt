package dev.piannoli.scorza.di

import dev.piannoli.scorza.settings.SettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the Piannoli root (and ROM directory) on demand from [SettingsRepository], rather than
 * capturing a [File] at construction time. Storage-backed singletons must read through this so they
 * pick up the SD-card root that the setup flow writes after MANAGE_EXTERNAL_STORAGE is granted,
 * instead of freezing the default path during Hilt's eager field injection in MainActivity.onCreate.
 */
@Singleton
class PiannoliPathsProvider @Inject constructor(
    private val settings: SettingsRepository,
) {
    val root: File get() = File(settings.sdCardRoot)
    val showHiddenFiles: Boolean get() = settings.showHiddenFiles
    fun filteredExtensions(tag: String): Set<String> = settings.filteredExtensions(tag)

    val romDir: File
        get() = settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(root, "Roms")
}
