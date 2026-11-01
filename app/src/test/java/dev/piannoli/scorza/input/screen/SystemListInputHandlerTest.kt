package dev.piannoli.scorza.input.screen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.R
import dev.piannoli.scorza.model.Platform
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel.ListItem
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemListInputHandlerTest {
    @Test fun `global keyboard captures menu origin before submitting or cancelling`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nav = NavigationController()
        val games = mockk<GameListViewModel>(relaxed = true)
        val handler = SystemListInputHandler(
            context, nav, CoroutineScope(Dispatchers.Unconfined), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), games, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true),
        )
        handler.onR1()
        verify(exactly = 1) { games.beginSearch(fromGameList = false) }
        assertTrue(nav.dialogState.value is DialogState.RenameInput)
        assertTrue(!nav.beginLauncherSearch())
    }

    @Test fun `every platform and category opens options with choose artwork`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nav = NavigationController()
        val systems = mockk<SystemListViewModel>(relaxed = true)
        val handler = SystemListInputHandler(
            context, nav, CoroutineScope(Dispatchers.Unconfined), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            systems, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true),
        )
        val items = listOf(
            ListItem.RecentlyPlayedItem, ListItem.FavoritesItem, ListItem.CollectionsFolder,
            ListItem.CollectionItem(7, "My collection", 2),
            ListItem.ToolsFolder("Tools", 1), ListItem.PortsFolder("Ports", 1),
            ListItem.PlatformItem(Platform("GB", "Game Boy", null)),
        )
        items.forEach { item ->
            every { systems.getSelectedItem() } returns item
            nav.dialogState.value = DialogState.None
            handler.onStart()
            val menu = nav.dialogState.value
            assertTrue("No options menu for $item", menu is DialogState.ContextMenu)
            assertTrue("No artwork picker for $item",
                context.getString(R.string.choose_artwork) in (menu as DialogState.ContextMenu).options)
        }
    }
}
