package dev.piannoli.scorza.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.piannoli.core.StateSlotPaths
import java.io.File

/** Read-only slot metadata for the frontend's external emulator resume picker. */
class SaveSlotManager(private val stateBasePath: String) {
    data class Slot(val index: Int, val label: String)

    val slots = listOf(Slot(0, "Auto")) + (0..9).map { Slot(it + 1, "Slot $it") }

    fun statePath(slot: Slot): String = StateSlotPaths.statePath(stateBasePath, slot.index)

    fun thumbnailPath(slot: Slot): String = StateSlotPaths.thumbnailPath(stateBasePath, slot.index)

    fun stateExists(slot: Slot): Boolean = File(statePath(slot)).exists()

    fun loadThumbnail(slot: Slot): Bitmap? {
        val file = File(thumbnailPath(slot))
        if (!file.exists()) return null
        return try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
    }
}
