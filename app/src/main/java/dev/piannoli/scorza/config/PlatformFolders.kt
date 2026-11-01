package dev.piannoli.scorza.config

import java.io.File
import java.util.Locale

object PlatformFolders {
    private val aliases = mapOf(
        "GB" to listOf("Game Boy", "Nintendo Game Boy"),
        "GBC" to listOf("Game Boy Color", "Nintendo Game Boy Color"),
        "GBA" to listOf("Game Boy Advance", "Nintendo Game Boy Advance"),
        "NES" to listOf("Nintendo Entertainment System", "Famicom", "FC"),
        "FDS" to listOf("Famicom Disk System", "Nintendo Famicom Disk System"),
        "SNES" to listOf("Super Nintendo", "Super Nintendo Entertainment System", "Super Famicom", "SFC"),
        "N64" to listOf("Nintendo 64"),
        "NDS" to listOf("Nintendo DS", "DS"),
        "GG" to listOf("Game Gear", "Sega Game Gear"),
        "SMS" to listOf("Master System", "Sega Master System"),
        "MD" to listOf("Mega Drive", "Genesis", "Sega Mega Drive", "Sega Genesis"),
        "SG1000" to listOf("Sega SG-1000"),
        "32X" to listOf("Sega 32X", "Mega Drive 32X"),
        "SEGACD" to listOf("Sega CD", "Mega CD", "Sega Mega CD"),
        "SATURN" to listOf("Sega Saturn"),
        "PS" to listOf("PS1", "PSX", "PlayStation", "PlayStation 1", "Sony PlayStation", "Sony PlayStation 1"),
        "PSP" to listOf("PlayStation Portable", "Sony PSP", "Sony PlayStation Portable"),
        "DC" to listOf("Dreamcast", "Sega Dreamcast"),
        "LYNX" to listOf("Atari Lynx"),
        "JAGUAR" to listOf("Atari Jaguar"),
        "ATARI2600" to listOf("Atari 2600"),
        "ATARI5200" to listOf("Atari 5200"),
        "ATARI7800" to listOf("Atari 7800"),
        "PCE" to listOf("PC Engine", "TurboGrafx 16", "TG16", "PC Engine CD", "PCECD", "TurboGrafx CD", "TGCD"),
        "PCFX" to listOf("NEC PC-FX"),
        "PICO8" to listOf("Pico-8"),
        "NGP" to listOf("Neo Geo Pocket", "SNK Neo Geo Pocket"),
        "NGPC" to listOf("Neo Geo Pocket Color", "SNK Neo Geo Pocket Color"),
        "WS" to listOf("WonderSwan", "Bandai WonderSwan"),
        "WSC" to listOf("WonderSwan Color", "Bandai WonderSwan Color"),
        "NEOGEO" to listOf("Neo Geo", "SNK Neo Geo"),
        "MAME" to emptyList(),
        "FBN" to listOf("FBNeo", "FinalBurn Neo"),
        "VIRTUALBOY" to listOf("Virtual Boy", "Nintendo Virtual Boy", "VB"),
        "POKEMINI" to listOf("Pokemon Mini"),
        "COLECOVISION" to listOf("Coleco ColecoVision"),
        "VECTREX" to listOf("GCE Vectrex"),
        "INTELLIVISION" to listOf("Mattel Intellivision"),
        "SUPERGRAFX" to listOf("SuperGrafx", "PC Engine SuperGrafx", "NEC SuperGrafx"),
        "DOS" to listOf("MS-DOS", "DOSBox"),
        "SCUMMVM" to listOf("ScummVM"),
        "AMIGA" to listOf("Commodore Amiga"),
        "PC" to listOf("PC Games"),
        "PS2" to listOf("PlayStation 2", "Sony PlayStation 2"),
        "XBOX" to listOf("Microsoft Xbox", "Original Xbox"),
        "GC" to listOf("GameCube", "Nintendo GameCube"),
        "WII" to listOf("Nintendo Wii"),
        "3DS" to listOf("N3DS", "Nintendo 3DS"),
        "WIIU" to listOf("Wii U", "Nintendo Wii U"),
        "PSVITA" to listOf("Vita", "PS Vita", "PlayStation Vita", "Sony PlayStation Vita"),
        "PS3" to listOf("PlayStation 3", "Sony PlayStation 3"),
        "NSW" to listOf("Switch", "Nintendo Switch"),
    ).flatMap { (tag, names) -> (names + tag).map { normalize(it) to tag } }.toMap()

    private fun normalize(name: String): String =
        name.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun canonicalTag(name: String): String =
        aliases[normalize(name)] ?: name.uppercase(Locale.ROOT)

    fun directories(root: File, tag: String): List<File> {
        val canonical = canonicalTag(tag)
        return root.listFiles { file ->
            file.isDirectory && !file.name.startsWith(".") && canonicalTag(file.name) == canonical
        }.orEmpty().sortedWith(compareBy<File> { !it.name.equals(canonical, ignoreCase = true) }.thenBy { it.name })
    }
}
