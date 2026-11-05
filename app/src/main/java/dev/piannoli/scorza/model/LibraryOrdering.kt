package dev.piannoli.scorza.model

import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.settings.LibraryOrder
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.util.sortedNatural
import org.json.JSONArray
import javax.inject.Inject

class LibraryOrdering @Inject constructor(
    private val settings: SettingsRepository,
    private val paths: PiannoliPathsProvider,
) {
    fun key(kind: String, identity: String, folder: String? = null): String =
        JSONArray(listOf(paths.romDir.absolutePath, kind, identity, folder.orEmpty())).toString()

    fun selection(key: String): LibraryOrder = settings.listOrder(key)

    fun select(key: String, order: LibraryOrder) = settings.setListOrder(key, order)

    fun resolved(key: String): LibraryOrder =
        selection(key).takeUnless { it == LibraryOrder.DEFAULT } ?: settings.defaultLibraryOrder

    fun rememberManual(key: String, items: List<ListItem>) =
        settings.setManualListOrder(key, items.mapNotNull(::memberKey))

    fun hasMultiplePlatforms(items: List<ListItem>): Boolean =
        items.mapNotNull(::platform).distinct().size > 1

    fun sort(
        items: List<ListItem>,
        key: String,
        favoriteRoms: Set<Long>,
        favoriteApps: Set<Long>,
    ): List<ListItem> {
        val order = resolved(key)
        val (folders, members) = items.partition { memberKey(it) == null }
        val sorted = when (order) {
            LibraryOrder.ALPHABETICAL -> members.sortedNatural(::name)
            LibraryOrder.PLATFORM -> members.sortedNatural(::name).sortedNatural { platform(it).orEmpty() }
            LibraryOrder.LAST_PLAYED -> members.sortedNatural(::name).sortedByDescending {
                when (it) {
                    is ListItem.RomItem -> it.rom.lastPlayedAt
                    is ListItem.AppItem -> it.app.lastPlayedAt
                    else -> null
                } ?: Long.MIN_VALUE
            }
            LibraryOrder.MANUAL -> {
                val positions = settings.manualListOrder(key).withIndex().associate { it.value to it.index }
                members.sortedBy { positions[memberKey(it)] ?: Int.MAX_VALUE }
            }
            LibraryOrder.DEFAULT -> error("Default library order must be explicit")
        }
        return folders + sorted.sortedBy { reorderGroup(it, favoriteRoms, favoriteApps) }
    }

    fun reorderGroup(item: ListItem, favoriteRoms: Set<Long>, favoriteApps: Set<Long>): Int = when (item) {
        is ListItem.AppItem -> when {
            item.app.isPinned -> 1
            item.app.id in favoriteApps -> 2
            else -> 3
        }
        is ListItem.RomItem -> if (item.rom.id in favoriteRoms) 2 else 3
        else -> 0
    }

    private fun memberKey(item: ListItem): String? = when (item) {
        is ListItem.RomItem -> "rom:${item.rom.id}"
        is ListItem.AppItem -> "app:${item.app.id}"
        else -> null
    }

    private fun platform(item: ListItem): String? = when (item) {
        is ListItem.RomItem -> item.rom.platformTag.uppercase()
        is ListItem.AppItem -> if (item.app.type == AppType.PORT) "PORTS" else null
        else -> null
    }

    private fun name(item: ListItem): String = when (item) {
        is ListItem.RomItem -> item.rom.displayName
        is ListItem.AppItem -> item.app.displayName
        else -> error("Only content items are sorted")
    }
}
