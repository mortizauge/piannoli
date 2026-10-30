package dev.piannoli.scorza.setup

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SetupCoordinatorTest {

    private val coordinator = SetupCoordinator(mockk<Context>(relaxed = true))

    private fun tempDir(): File = Files.createTempDirectory("piannoli-test").toFile()

    private fun withPiannoli(parent: File): File {
        File(parent, "Piannoli/Config").mkdirs()
        File(parent, "Piannoli/Config/settings.json").writeText("{}")
        return parent
    }

    @Test fun returnsIndexOfVolumeHoldingExistingPiannoli() {
        val internal = tempDir()
        val sd = withPiannoli(tempDir())
        val volumes = listOf(
            "Internal Storage" to internal.absolutePath + "/",
            "SD card" to sd.absolutePath + "/",
            "Custom" to "",
        )
        assertEquals(1, coordinator.existingPiannoliVolumeIndex(volumes))
    }

    @Test fun prefersRemovableVolumeWhenBothHavePiannoli() {
        val internal = withPiannoli(tempDir())
        val sd = withPiannoli(tempDir())
        val volumes = listOf(
            "Internal Storage" to internal.absolutePath + "/",
            "SD card" to sd.absolutePath + "/",
            "Custom" to "",
        )
        assertEquals(1, coordinator.existingPiannoliVolumeIndex(volumes))
    }

    @Test fun returnsNullWhenNoExistingPiannoli() {
        val volumes = listOf(
            "Internal Storage" to tempDir().absolutePath + "/",
            "Custom" to "",
        )
        assertNull(coordinator.existingPiannoliVolumeIndex(volumes))
    }

    @Test fun ignoresCustomEntryWithEmptyPath() {
        val volumes = listOf(
            "Internal Storage" to tempDir().absolutePath + "/",
            "Custom" to "",
        )
        assertNull(coordinator.existingPiannoliVolumeIndex(volumes))
    }
}
