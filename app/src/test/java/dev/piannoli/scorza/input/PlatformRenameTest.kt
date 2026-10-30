package dev.piannoli.scorza.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.model.Platform
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.ContentMode
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.piannoli.ui.components.KeyboardState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformRenameTest {
    @Test fun `first rename persists before refreshing without a disk scan`() = TestScope().runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nav = NavigationController()
        val config = mockk<PlatformConfig>(relaxed = true)
        val systems = mockk<SystemListViewModel>(relaxed = true)
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.contentMode } returns ContentMode.PLATFORMS
        every { systems.getSelectedItem() } returns SystemListViewModel.ListItem.PlatformItem(
            Platform(tag = "GB", displayName = "Game Boy", coreName = null, gameCount = 1))
        var savedName = "Game Boy"
        every { config.setDisplayName("GB", any()) } answers { savedName = secondArg() }
        val actions = LauncherActions(
            context, this, settings, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), nav, systems,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
        val input = DialogState.RenameInput("Game Boy", keyboard = KeyboardState(text = "Handheld"))
        nav.dialogState.value = input
        actions.handleSystemListRename(input)
        runCurrent()
        assertEquals("Handheld", savedName)
        assertEquals(DialogState.None, nav.dialogState.value)
        verify(exactly = 1) {
            systems.scan(
                showRecentlyPlayed = any(), showFavorites = any(), contentMode = any(),
                fghCollectionId = any(), toolsName = any(), portsName = any(),
                scanDisk = false, reconcileOrphans = false, onProgress = any(), onReady = any(),
            )
        }
    }
}
