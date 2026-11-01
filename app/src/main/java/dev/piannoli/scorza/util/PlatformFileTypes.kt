package dev.piannoli.scorza.util

import java.io.File
import java.util.Locale

internal object PlatformFileTypes {
    // Based on bundled libretro core_info, split where one core emulates several systems.
    // Standalone systems and launcher shortcut formats supplement those lists.
    private val extensions = mapOf(
        "GB" to "gb zip",
        "GBC" to "gbc zip",
        "GBA" to "gba zip 7z",
        "NES" to "nes unf unif zip 7z",
        "FDS" to "fds zip 7z",
        "SNES" to "smc sfc swc fig bs st zip 7z",
        "N64" to "n64 v64 z64 ndd bin u1 zip 7z",
        "NDS" to "nds ids dsi zip 7z",
        "GG" to "gg zip 7z",
        "SMS" to "sms bms zip 7z",
        "MD" to "md mdx smd gen bin 68k sgd zip 7z",
        "SG1000" to "sg sc zip 7z",
        "32X" to "32x bin smd gen md zip 7z",
        "SEGACD" to "cue iso chd m3u",
        "SATURN" to "cue ccd chd toc m3u iso",
        "PS" to "bin cue img mdf pbp toc cbn m3u ccd chd iso exe",
        "PSP" to "elf iso cso zso prx pbp chd",
        "DC" to "chd cdi elf bin cue gdi lst zip dat 7z m3u",
        "LYNX" to "lnx lyx o zip 7z",
        "JAGUAR" to "j64 jag rom abs cof bin prg zip 7z",
        "ATARI2600" to "a26 bin zip 7z",
        "ATARI5200" to "a52 bin car rom zip 7z",
        "ATARI7800" to "a78 bin cdf zip 7z",
        "PCE" to "pce cue ccd chd toc m3u zip 7z",
        "PCFX" to "cue ccd toc chd m3u",
        "PICO8" to "p8 p8.png",
        "NGP" to "ngp zip 7z",
        "NGPC" to "ngc ngpc npc zip 7z",
        "WS" to "ws pc2 zip 7z",
        "WSC" to "wsc pcv2 zip 7z",
        "NEOGEO" to "zip 7z",
        "MAME" to "zip 7z",
        "FBN" to "zip 7z",
        "VIRTUALBOY" to "vb vboy bin zip 7z",
        "POKEMINI" to "min zip 7z",
        "COLECOVISION" to "col rom ri zip 7z",
        "VECTREX" to "bin vec zip 7z",
        "INTELLIVISION" to "int bin rom zip 7z",
        "SUPERGRAFX" to "pce sgx cue ccd chd m3u zip 7z",
        "DOS" to "zip dosz exe com bat iso chd cue ins img ima vhd jrc tc m3u m3u8 conf",
        "SCUMMVM" to "scummvm",
        "AMIGA" to "adf adz dms fdi ipf hdf hdz lha slave info cue ccd nrg mds iso chd uae m3u zip 7z rp9",
        "PC" to "steam steamappid epic gog amazon pcgame localgameid txt",
        "PS2" to "elf iso ciso cue bin gz chd cso zso mdf nrg dump img m3u",
        "XBOX" to "iso xiso xboe dotno",
        "GC" to "iso rvz gcm gcz ciso wia tgc dol elf dff m3u",
        "WII" to "iso rvz gcm gcz ciso wia wbfs wad dol elf dff m3u",
        "3DS" to "3ds 3dsx z3dsx elf axf cci zcci cxi zcxi app",
        "WIIU" to "wud wux wua rpx elf",
        "PSVITA" to "psvita",
        "PS3" to "iso bin self elf",
        "NSW" to "nsp xci nro nso nca",
    ).mapValues { (_, value) -> value.split(' ').toSet() }

    fun supports(platformTag: String, extension: String): Boolean =
        // Custom platform folders retain their existing behavior until formats are defined.
        extensions[platformTag.uppercase(Locale.ROOT)]?.contains(extension.lowercase(Locale.ROOT)) ?: true

    fun accepts(platformTag: String, file: File): Boolean {
        val extension = if (file.name.endsWith(".p8.png", ignoreCase = true)) "p8.png" else file.extension
        return supports(platformTag, extension)
    }
}
