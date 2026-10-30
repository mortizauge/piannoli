package dev.piannoli.scorza.input.screen

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import dev.piannoli.scorza.db.AppsRepository
import dev.piannoli.scorza.input.LauncherActions
import dev.piannoli.scorza.model.App
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.navigation.LauncherScreen
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.scraper.ScrapedArtworkStore
import dev.piannoli.scorza.ui.screens.DialogState
import io.mockk.every
import io.mockk.mockk
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

    private fun handler(): SettingsInputHandler {
        every { context.packageManager } returns packages
        return SettingsInputHandler(
            nav = nav,
            ioScope = CoroutineScope(dispatcher),
            settings = mockk(relaxed = true),
            platformConfig = mockk(relaxed = true),
            installedCoreService = mockk(relaxed = true),
            globalOverrides = mockk(relaxed = true),
            appsRepository = apps,
            setupCoordinator = mockk(relaxed = true),
            inputTesterController = mockk(relaxed = true),
            updateManager = mockk(relaxed = true),
            intentAuditor = mockk(relaxed = true),
            settingsViewModel = mockk(relaxed = true),
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
                type = "tools", title = "Manage Tools", apps = listOf("Gone"),
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
