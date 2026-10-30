package dev.piannoli.igm

import java.io.File

data class GuideFile(val file: File, val type: GuideType) {
    val name: String get() = file.name
}
