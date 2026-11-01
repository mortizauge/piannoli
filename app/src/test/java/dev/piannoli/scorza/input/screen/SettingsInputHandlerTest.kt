package dev.piannoli.scorza.input.screen

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.db.AppsRepository
import dev.piannoli.scorza.di.AppFonts
import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.input.ListNavigation
import dev.piannoli.scorza.model.App
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.scraper.ScrapedArtworkStore
import dev.piannoli.scorza.settings.ContentMode
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsInputHandlerTest {
    private val dispatcher = StandardTestDispatcher()
    private val nav = NavigationController()
    private val apps = mockk<AppsRepository>(relaxed = true)
    private val artwork = mockk<ScrapedArtworkStore>(relaxed = true)
    private val actions = mockk<LauncherActions>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val packages = mockk<PackageManager>(relaxed = true)
    private val games = mockk<GameListViewModel>(relaxed = true)

    @Test fun `Manage Apps opens localized picker backed by unchanged app type`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(appContext)
        try {
            val viewModel = SettingsViewModel(settings, AppFonts(appContext), appContext, mockk(relaxed = true))
            val input = handler(viewModel, settings)
            every { context.getString(dev.piannoli.scorza.R.string.setting_manage_tools) } returns
                appContext.getString(dev.piannoli.scorza.R.string.setting_manage_tools)
            viewModel.load()
            nav.push(LauncherScreen.Settings)
            viewModel.enterSubCategory("library", dev.piannoli.scorza.R.string.settings_library)
            viewModel.selectItem("manage_tools")
            assertEquals("Manage Apps", appContext.getString(viewModel.getSelectedItem()!!.labelRes))
            input.onConfirm()
            val picker = nav.currentScreen as LauncherScreen.AppPicker
            assertEquals("Manage Apps", picker.title)
            assertEquals("tools", picker.type)
            verify(exactly = 1) { apps.all(AppType.TOOL) }
        } finally {
            settings.shutdown()
        }
    }

    @Test fun `held settings navigation clamps categories and sublists then fresh presses wrap`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(appContext)
        val viewModel = SettingsViewModel(settings, AppFonts(appContext), appContext, mockk(relaxed = true))
        val input = handler(viewModel, settings)
        viewModel.load()
        for (inSubList in listOf(false, true)) {
            if (inSubList) viewModel.enterSubCategory("library", dev.piannoli.scorza.R.string.settings_library)
            val count = if (inSubList) viewModel.state.value.items.size else viewModel.state.value.categories.size
            fun index() = if (inSubList) viewModel.state.value.selectedIndex else viewModel.state.value.categoryIndex
            assertTrue(count > 1)
            ListNavigation.withRepeat(true) {
                repeat(count + 3) { input.onDown() }
                assertEquals(count - 1, index())
            }
            input.onDown()
            assertEquals(0, index())
            ListNavigation.withRepeat(true) { repeat(3) { input.onUp() } }
            assertEquals(0, index())
            input.onUp()
            assertEquals(count - 1, index())
            viewModel.moveSelection(-count * 2)
            assertEquals(0, index())
        }
    }

    private fun handler(
        settingsViewModel: SettingsViewModel = mockk(relaxed = true),
        settings: SettingsRepository = mockk(relaxed = true),
    ): SettingsInputHandler {
        every { context.packageManager } returns packages
        return SettingsInputHandler(
            nav = nav,
            ioScope = CoroutineScope(dispatcher),
            settings = settings,
            platformConfig = mockk(relaxed = true),
            installedCoreService = mockk(relaxed = true),
            globalOverrides = mockk(relaxed = true),
            appsRepository = apps,
            setupCoordinator = mockk(relaxed = true),
            inputTesterController = mockk(relaxed = true),
            updateManager = mockk(relaxed = true),
            intentAuditor = mockk(relaxed = true),
            settingsViewModel = settingsViewModel,
            gameListViewModel = games,
            launcherActions = actions,
            activityActions = mockk(relaxed = true),
            emulatorMappingBuilder = mockk(relaxed = true),
            context = context,
            rommStore = mockk(relaxed = true),
            piannoliPaths = mockk(relaxed = true),
            scraper = mockk(relaxed = true),
            artworkStore = artwork,
        )
    }

    @Test fun `hidden games recovery is always available in Library`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(appContext)
        val viewModel = SettingsViewModel(settings, AppFonts(appContext), appContext, mockk(relaxed = true))
        viewModel.load()
        for (mode in ContentMode.entries) {
            settings.contentMode = mode
            viewModel.enterSubCategory("library", dev.piannoli.scorza.R.string.settings_library)
            viewModel.selectItem("manage_hidden_games")
            assertEquals("manage_hidden_games", viewModel.enterSelected())
            assertFalse(viewModel.getSelectedItem()!!.disabled)
        }
    }

    @Test fun `opening hidden games pushes after loading and preserves Library selection and cancel snapshot`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(appContext)
        settings.showHiddenFiles = false
        val viewModel = spyk(SettingsViewModel(settings, AppFonts(appContext), appContext, mockk(relaxed = true)))
        viewModel.load()
        val handler = handler(viewModel, settings)
        nav.push(LauncherScreen.Settings)
        viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "library" })
        handler.onConfirm()
        viewModel.selectItem("hidden_files")
        viewModel.cycleSelected(1)
        assertTrue(settings.showHiddenFiles)
        viewModel.selectItem("manage_hidden_games")
        val libraryState = viewModel.state.value
        var onReady: (() -> Unit)? = null
        every { games.loadHiddenGames(any()) } answers { onReady = firstArg() }

        handler.onConfirm()
        assertTrue(nav.navigating)
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
        handler.onConfirm()
        handler.onBack()
        verify(exactly = 1) { games.loadHiddenGames(any()) }
        assertEquals(libraryState, viewModel.state.value)

        onReady!!()
        assertFalse(nav.navigating)
        assertEquals(listOf(LauncherScreen.SystemList, LauncherScreen.Settings, LauncherScreen.GameList), nav.screenStack.toList())
        nav.pop()
        viewModel.load()
        assertEquals(LauncherScreen.Settings, nav.currentScreen)
        assertEquals(libraryState, viewModel.state.value)
        assertTrue(settings.showHiddenFiles)
        verify(exactly = 0) { viewModel.save() }
        verify(exactly = 0) { viewModel.cancel() }
        viewModel.cancel()
        assertFalse(settings.showHiddenFiles)
    }

    @Test fun `managing tools and ports copies icons only for newly added apps`() = runTest(dispatcher) {
        val handler = handler()
        for (type in AppType.entries) {
            val existing = App(1, type, "Existing", "dev.existing")
            val custom = App(2, type, "Custom", "dev.custom", artFile = File("custom.png"))
            val removed = App(3, type, "Removed", "dev.removed")
            every { apps.all(type) } returns listOf(existing, custom, removed)
            every { apps.upsert(type, "New", "dev.new") } returns 4L
            val icon = ColorDrawable(android.graphics.Color.RED)
            every { packages.getApplicationIcon("dev.new") } returns icon
            val state = LauncherScreen.AppPicker(
                type = if (type == AppType.TOOL) "tools" else "ports",
                title = "Manage",
                apps = listOf("Existing", "Custom", "New"),
                packages = listOf("dev.existing", "dev.custom", "dev.new"),
                checkedIndices = setOf(0, 1, 2),
            )
            nav.push(state)
            handler.confirmAppPicker(state)
            runCurrent()
            verify(exactly = 1) { artwork.installAppIcon(App(4, type, "New", "dev.new"), icon) }
            verify(exactly = 1) { artwork.installAppIcon(any(), any()) }
            verify(exactly = 0) { packages.getApplicationIcon("dev.existing") }
            verify(exactly = 0) { packages.getApplicationIcon("dev.custom") }
            verify(exactly = 1) { apps.delete(removed.id) }
            io.mockk.clearMocks(apps, artwork, packages, answers = false)
        }
        verify(exactly = 2) { actions.rescanSystemList() }
    }

    @Test fun `unavailable app icon reports failure but keeps the new shortcut`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val handler = handler()
            every { apps.all(AppType.TOOL) } returns emptyList()
            every { packages.getApplicationIcon("dev.gone") } throws PackageManager.NameNotFoundException()
            val state = LauncherScreen.AppPicker(
                type = "tools", title = "Manage Apps", apps = listOf("Gone"),
                packages = listOf("dev.gone"), checkedIndices = setOf(0),
            )
            nav.push(state)
            handler.confirmAppPicker(state)
            runCurrent()
            verify(exactly = 1) { apps.upsert(AppType.TOOL, "Gone", "dev.gone") }
            verify(exactly = 0) { artwork.installAppIcon(any(), any()) }
            verify(exactly = 1) { actions.rescanSystemList() }
            assertTrue(nav.dialogState.value is DialogState.LaunchError)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
