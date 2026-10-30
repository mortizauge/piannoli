package dev.piannoli.scorza.scraper

import dev.piannoli.scorza.model.App
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.model.Rom
import java.io.File

sealed interface ScrapeGame {
    val identity: String
    val displayName: String
    val platformTag: String
    val artFile: File?
    val embeddedArtwork: Boolean
    fun install(store: ScrapedArtworkStore, bytes: ByteArray, overwrite: Boolean): File

    data class SystemGame(
        override val platformTag: String,
        override val displayName: String,
        val type: dev.piannoli.scorza.settings.SystemArtworkType,
        override val artFile: File? = null,
    ) : ScrapeGame {
        override val identity get() = "system:$platformTag"
        override val embeddedArtwork get() = false
        override fun install(store: ScrapedArtworkStore, bytes: ByteArray, overwrite: Boolean) =
            store.installSystem(platformTag, type, bytes)
    }

    data class RomGame(val rom: Rom) : ScrapeGame {
        override val identity get() = "rom:${rom.id}"
        override val displayName get() = rom.displayName
        override val platformTag get() = rom.platformTag
        override val artFile get() = rom.artFile
        override val embeddedArtwork get() = rom.path.name.endsWith(".p8.png", ignoreCase = true)
        override fun install(store: ScrapedArtworkStore, bytes: ByteArray, overwrite: Boolean) =
            store.install(rom, bytes, overwrite)
    }

    data class PortGame(val app: App) : ScrapeGame {
        init { require(app.type == AppType.PORT) }
        override val identity get() = "app:${app.id}"
        override val displayName get() = app.displayName
        override val platformTag get() = "PORTS"
        override val artFile get() = app.artFile
        override val embeddedArtwork get() = false
        override fun install(store: ScrapedArtworkStore, bytes: ByteArray, overwrite: Boolean) =
            store.installPort(app, bytes, overwrite)
    }
}
