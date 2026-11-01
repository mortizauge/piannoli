package dev.piannoli.scorza.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.piannoli.scorza.scraper.SystemArtwork
import dev.piannoli.scorza.settings.SystemArtworkType
import dev.piannoli.scorza.ui.screens.SystemListScreen
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel.AppSettings
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel.ListItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp")
class CategoryArtworkTest {
    @get:Rule val compose = createComposeRule()
    private val state = MutableStateFlow(SystemListViewModel.State(
        items = listOf(ListItem.FavoritesItem, ListItem.RecentlyPlayedItem, ListItem.ToolsFolder("Apps", 0)),
        isLoading = false,
    ))
    private val model = mockk<SystemListViewModel>(relaxed = true).also {
        every { it.state } returns state
        every { it.systemArtworkFile(any(), any()) } returns null
    }
    private val files = mutableListOf<File>()
    private val gates = mutableListOf<CountDownLatch>()

    @After fun cleanUp() {
        gates.forEach { it.countDown() }
        unmockkStatic(BitmapFactory::class)
        files.forEach { it.delete() }
    }

    @Test fun `category only home reserves artwork in both layouts for every artwork type`() {
        val type = mutableStateOf(SystemArtworkType.LOGO)
        val handheld = mutableStateOf(false)
        compose.setContent {
            SystemListScreen(model, systemArtworkType = type.value, fiveGameHandheld = handheld.value)
        }
        for (layout in listOf(false, true)) {
            for (artworkType in SystemArtworkType.entries) {
                for ((index, label) in listOf("Favorites", "Recently Played", "Apps").withIndex()) {
                    compose.runOnIdle {
                        handheld.value = layout
                        type.value = artworkType
                        state.value = state.value.copy(selectedIndex = index)
                    }
                    compose.onNodeWithContentDescription(label).assertIsDisplayed()
                }
            }
        }
    }

    @Test fun `zero system artwork width hides all category defaults`() {
        val width = mutableStateOf(0)
        compose.setContent { SystemListScreen(model, systemArtWidth = width.value) }
        for ((index, label) in listOf("Favorites", "Recently Played", "Apps").withIndex()) {
            compose.runOnIdle { state.value = state.value.copy(selectedIndex = index) }
            compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
        }
        verify(exactly = 0) { model.systemArtworkFile(any(), any()) }
        compose.runOnIdle { width.value = 40 }
        compose.onNodeWithContentDescription("Apps").assertIsDisplayed()
    }

    @Test fun `hidden system artwork suppresses every category default and restores immediately for all types`() {
        val settings = mutableStateOf(AppSettings(showSystemArtwork = false))
        compose.setContent {
            SystemListScreen(model, systemArtWidth = settings.value.effectiveSystemArtWidth,
                systemArtworkType = settings.value.systemArtworkType)
        }
        for (type in SystemArtworkType.entries) {
            for ((index, label) in listOf("Favorites", "Recently Played", "Apps").withIndex()) {
                compose.runOnIdle {
                    settings.value = settings.value.copy(showSystemArtwork = false, systemArtworkType = type)
                    state.value = state.value.copy(selectedIndex = index)
                }
                compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
                compose.runOnIdle { settings.value = settings.value.copy(showSystemArtwork = true) }
                compose.onNodeWithContentDescription(label).assertIsDisplayed()
            }
        }
    }

    @Test fun `ports and collections do not receive category defaults`() {
        state.value = state.value.copy(items = listOf(
            ListItem.PortsFolder("Ports", 0), ListItem.CollectionsFolder, ListItem.CollectionItem(1, "Collection", 0),
        ))
        compose.setContent { SystemListScreen(model) }
        for (index in state.value.items.indices) {
            compose.runOnIdle { state.value = state.value.copy(selectedIndex = index) }
            for (label in listOf("Favorites", "Recently Played", "Apps", "Ports", "Collections", "Collection")) {
                compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
            }
        }
    }

    @Test fun `assigned custom image suppresses default while pending and after decode until deletion`() {
        checkCustomImagePriority(corrupt = false)
    }

    @Test fun `corrupt assigned image remains assigned and missing file restores default`() {
        checkCustomImagePriority(corrupt = true)
    }

    @Test fun `renamed apps keep legacy custom artwork identity and restore grid after deletion`() {
        checkCustomImagePriority(corrupt = false, apps = true)
    }

    @Test fun `corrupt apps artwork suppresses grid until deletion`() {
        checkCustomImagePriority(corrupt = true, apps = true)
    }

    private fun checkCustomImagePriority(corrupt: Boolean, apps: Boolean = false) {
        val label = if (apps) "Utilities" else "Favorites"
        val tag = if (apps) "@tools" else "@favorites"
        if (apps) {
            state.value = state.value.copy(items = listOf(ListItem.ToolsFolder(label, 0)))
        }
        val file = File("category-artwork-test-${System.nanoTime()}.png").absoluteFile.also { files.add(it) }
        every { model.systemArtworkFile(tag, SystemArtworkType.LOGO) } returns file
        val type = mutableStateOf(SystemArtworkType.LOGO)
        val settings = mutableStateOf(AppSettings())
        val gate = CountDownLatch(1).also { gates.add(it) }
        val started = AtomicInteger()
        val decoded = AtomicInteger()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeFile(file.absolutePath, any()) } answers {
            val options = secondArg<BitmapFactory.Options>()
            if (options.inJustDecodeBounds) {
                options.outWidth = 16
                options.outHeight = 16
                null
            } else {
                started.incrementAndGet()
                gate.await(10, TimeUnit.SECONDS)
                decoded.incrementAndGet()
                if (corrupt) null else bitmap
            }
        }
        compose.setContent {
            SystemListScreen(model, systemArtworkType = type.value,
                systemArtWidth = settings.value.effectiveSystemArtWidth)
        }
        compose.onNodeWithContentDescription(label).assertIsDisplayed()
        compose.runOnIdle {
            settings.value = settings.value.copy(showSystemArtwork = false)
            file.writeText("assigned image")
            SystemArtwork.notifyChanged()
        }
        compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
        assertEquals(0, started.get())
        compose.runOnIdle { settings.value = settings.value.copy(showSystemArtwork = true) }
        compose.waitUntil(5_000) { started.get() > 0 }
        compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
        gate.countDown()
        compose.waitUntil(5_000) { decoded.get() > 0 }
        compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
        compose.runOnIdle { settings.value = settings.value.copy(showSystemArtwork = false) }
        compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
        compose.runOnIdle { settings.value = settings.value.copy(showSystemArtwork = true) }
        assertEquals(1, decoded.get())
        for (artworkType in listOf(SystemArtworkType.ICON, SystemArtworkType.GRID)) {
            compose.runOnIdle { type.value = artworkType }
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        compose.runOnIdle { type.value = SystemArtworkType.LOGO }
        compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
        compose.runOnIdle { file.delete(); SystemArtwork.notifyChanged() }
        compose.onNodeWithContentDescription(label).assertIsDisplayed()
    }
}
