package dev.piannoli.scorza.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RicottaPackageTest {
    @Test
    fun recognizesRicottaPackages() {
        assertTrue(RetroArchLauncher.isRicotta("dev.piannoli.ricotta"))
        assertTrue(RetroArchLauncher.isRicotta("dev.piannoli.ricotta.aarch64"))
    }

    @Test
    fun rejectsStockRetroArch() {
        assertFalse(RetroArchLauncher.isRicotta("com.retroarch"))
        assertFalse(RetroArchLauncher.isRicotta("com.retroarch.aarch64"))
        assertFalse(RetroArchLauncher.isRicotta(""))
    }
}
