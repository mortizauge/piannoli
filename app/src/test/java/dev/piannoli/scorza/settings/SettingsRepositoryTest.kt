package dev.piannoli.scorza.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.R
import dev.piannoli.scorza.di.AppFonts
import dev.piannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.piannoli.ui.MenuAction
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newRepo() = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    private fun writeSettingsJson(root: File, json: String) {
        File(root, "Config").apply { mkdirs() }
        File(root, "Config/settings.json").writeText(json)
    }

    @Test
    @Config(sdk = [28])
    fun `launcher remapping swaps live supports reset cancel and persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        val expected = MenuAction.swap(emptyMap(), MenuAction.CONFIRM, MenuAction.BACK)
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            settings.setupCompleted = true
            assertEquals(emptyMap<MenuAction, MenuAction>(), settings.piannoliBindings)
            val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
            viewModel.load()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "input" })
            viewModel.enterCategory()
            viewModel.selectItem("remap_piannoli")
            assertEquals("remap_piannoli", viewModel.enterSelected())
            viewModel.enterSubCategory("remap_piannoli", R.string.remap_piannoli)
            assertEquals(
                listOf("remap:CONFIRM", "remap:BACK", "remap:NORTH", "remap:WEST",
                    "remap:START", "remap:SELECT", "remap_reset"),
                viewModel.state.value.items.map { it.key },
            )
            viewModel.selectItem("remap:CONFIRM")
            viewModel.cycleSelected(1)
            assertEquals(emptyMap<MenuAction, MenuAction>(), settings.piannoliBindings)
            assertEquals("remap:CONFIRM", viewModel.enterSelected())
            viewModel.startRemapping()
            assertEquals(MenuAction.CONFIRM, viewModel.state.value.remappingAction)
            viewModel.assignRemapping(MenuAction.L1)
            assertEquals(MenuAction.CONFIRM, viewModel.state.value.remappingAction)
            viewModel.cancelRemapping()
            assertEquals(null, viewModel.state.value.remappingAction)
            assertEquals(emptyMap<MenuAction, MenuAction>(), settings.piannoliBindings)
            viewModel.startRemapping()
            viewModel.assignRemapping(MenuAction.BACK)
            assertEquals(null, viewModel.state.value.remappingAction)
            assertEquals(expected, settings.piannoliBindings)
            assertEquals(expected, viewModel.appSettings.value.piannoliBindings)
            viewModel.selectItem("remap:BACK")
            assertEquals("CONFIRM", viewModel.getSelectedItem()?.valueText)
            viewModel.save()
            viewModel.selectItem("remap_reset")
            viewModel.cycleSelected(1)
            assertEquals(emptyMap<MenuAction, MenuAction>(), settings.piannoliBindings)
            viewModel.cancel()
            assertEquals(expected, settings.piannoliBindings)
            assertEquals(expected, viewModel.appSettings.value.piannoliBindings)
        } finally {
            settings.shutdown()
        }
        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals(expected, reopened.piannoliBindings)
        } finally {
            reopened.shutdown()
        }
    }

    @Test fun `older mappings restore fixed controls while retaining supported swaps`() {
        val supported = MenuAction.swap(emptyMap(), MenuAction.CONFIRM, MenuAction.BACK)
        val old = MenuAction.swap(supported, MenuAction.START, MenuAction.L1)
        val json = org.json.JSONObject().apply {
            put("piannoli_bindings", org.json.JSONObject().apply {
                old.forEach { (action, button) -> put(action.name, button.name) }
            })
        }
        writeSettingsJson(tmp.root, json.toString())
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            assertEquals(supported, settings.piannoliBindings)
            settings.piannoliBindings = old
            assertEquals(supported, settings.piannoliBindings)
        } finally {
            settings.shutdown()
        }
    }

    @Test fun `invalid partial or duplicate launcher mappings do not remove navigation controls`() {
        writeSettingsJson(tmp.root, """{"piannoli_bindings":{"CONFIRM":"BACK","BACK":"BACK"}}""")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            assertEquals(emptyMap<MenuAction, MenuAction>(), settings.piannoliBindings)
        } finally {
            settings.shutdown()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `navigation sound defaults off toggles in input and supports cancel and persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            settings.setupCompleted = true
            assertFalse(settings.navigationSound)
            val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
            viewModel.load()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "input" })
            viewModel.enterCategory()
            viewModel.selectItem("navigation_sound")
            assertEquals(R.string.value_off, viewModel.getSelectedItem()?.valueRes)
            viewModel.cycleSelected(1)
            assertEquals(true, settings.navigationSound)
            assertEquals(R.string.value_on, viewModel.getSelectedItem()?.valueRes)
            viewModel.cancel()
            assertFalse(settings.navigationSound)
            settings.navigationSound = true
        } finally {
            settings.shutdown()
        }
        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals(true, reopened.navigationSound)
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `controller notifications default off toggle in input and support cancel and persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            settings.setupCompleted = true
            assertFalse(settings.controllerNotifications)
            val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
            viewModel.load()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "input" })
            viewModel.enterCategory()
            viewModel.selectItem("controller_notifications")
            assertEquals(R.string.value_off, viewModel.getSelectedItem()?.valueRes)
            viewModel.cycleSelected(1)
            assertEquals(true, settings.controllerNotifications)
            assertEquals(R.string.value_on, viewModel.getSelectedItem()?.valueRes)
            viewModel.cancel()
            assertFalse(settings.controllerNotifications)
            settings.controllerNotifications = true
        } finally {
            settings.shutdown()
        }
        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals(true, reopened.controllerNotifications)
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `system artwork types cycle logo icon grid and support cancel and persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            settings.setupCompleted = true
            assertEquals(SystemArtworkType.LOGO, settings.systemArtworkType)
            val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
            viewModel.load()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "library" })
            viewModel.enterCategory()
            viewModel.enterSubCategory("artwork", R.string.settings_artwork)
            viewModel.enterSubCategory("system_artwork", R.string.setting_system_artwork)
            viewModel.selectItem("system_artwork_type")
            viewModel.cycleSelected(1)
            assertEquals(SystemArtworkType.ICON, settings.systemArtworkType)
            assertEquals(SystemArtworkType.ICON, viewModel.appSettings.value.systemArtworkType)
            viewModel.cycleSelected(1)
            assertEquals(SystemArtworkType.GRID, settings.systemArtworkType)
            viewModel.cycleSelected(1)
            assertEquals(SystemArtworkType.LOGO, settings.systemArtworkType)
            viewModel.cycleSelected(-1)
            assertEquals(SystemArtworkType.GRID, settings.systemArtworkType)
            viewModel.cancel()
            assertEquals(SystemArtworkType.LOGO, settings.systemArtworkType)
            settings.systemArtworkType = SystemArtworkType.ICON
        } finally {
            settings.shutdown()
        }
        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals(SystemArtworkType.ICON, reopened.systemArtworkType)
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `system art controls are independent update live cancel and persist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        writeSettingsJson(tmp.root, """{"art_width":55,"art_radius":75,"art_scale":"FIT_HEIGHT"}""")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            settings.setupCompleted = true
            assertEquals(ArtScale.FIT, settings.systemArtScale)
            assertEquals(40, settings.systemArtWidth)
            assertEquals(0, settings.systemArtRadius)
            val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
            viewModel.load()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "library" })
            viewModel.enterCategory()
            viewModel.enterSubCategory("artwork", R.string.settings_artwork)
            assertEquals(listOf("game_artwork", "system_artwork", "scrape_fail_behavior", "setup_scrapers"),
                viewModel.state.value.items.map { it.key })
            viewModel.enterSubCategory("system_artwork", R.string.setting_system_artwork)
            viewModel.selectItem("system_art_scale")
            viewModel.cycleSelected(1)
            viewModel.selectItem("system_art_width")
            viewModel.cycleSelected(1)
            viewModel.selectItem("system_art_radius")
            viewModel.cycleSelected(1)
            assertEquals(ArtScale.ORIGINAL, viewModel.appSettings.value.systemArtScale)
            assertEquals(45, viewModel.appSettings.value.systemArtWidth)
            assertEquals(1, viewModel.appSettings.value.systemArtRadius)
            assertEquals(ArtScale.FIT_HEIGHT, settings.artScale)
            assertEquals(55, settings.artWidth)
            assertEquals(75, settings.artRadius)
            viewModel.cancel()
            assertEquals(ArtScale.FIT, settings.systemArtScale)
            assertEquals(40, settings.systemArtWidth)
            assertEquals(0, settings.systemArtRadius)
            settings.systemArtScale = ArtScale.FIT_WIDTH
            settings.systemArtWidth = 60
            settings.systemArtRadius = 101
        } finally {
            settings.shutdown()
        }
        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals(ArtScale.FIT_WIDTH, reopened.systemArtScale)
            assertEquals(60, reopened.systemArtWidth)
            assertEquals(100, reopened.systemArtRadius)
            assertEquals(55, reopened.artWidth)
            assertEquals(75, reopened.artRadius)
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `platform extension exclusions persist independently per system`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            settings.setupCompleted = true
            assertEquals(emptySet<String>(), settings.filteredExtensions("GB"))
            settings.setFilteredExtensions("gb", setOf("zip", "gb"))
            settings.setFilteredExtensions("GBA", setOf("gba"))
        } finally {
            settings.shutdown()
        }
        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals(setOf("zip", "gb"), reopened.filteredExtensions("GB"))
            assertEquals(setOf("gba"), reopened.filteredExtensions("gba"))
            reopened.setFilteredExtensions("GB", emptySet())
            assertEquals(emptySet<String>(), reopened.filteredExtensions("GB"))
            assertEquals(setOf("gba"), reopened.filteredExtensions("GBA"))
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `hidden files default to hide toggle in library cancel and persist while artwork is in appearance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            settings.setupCompleted = true
            assertFalse(settings.showHiddenFiles)
            val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
            viewModel.load()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "display" })
            viewModel.enterCategory()
            assertEquals(true, viewModel.state.value.items.any { it.key == "artwork" })
            viewModel.exitSubList()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "library" })
            viewModel.enterCategory()
            assertFalse(viewModel.state.value.items.any { it.key == "artwork" })
            viewModel.selectItem("hidden_files")
            assertEquals(R.string.value_hide, viewModel.getSelectedItem()?.valueRes)
            viewModel.cycleSelected(1)
            assertEquals(true, settings.showHiddenFiles)
            assertEquals(R.string.value_show, viewModel.getSelectedItem()?.valueRes)
            viewModel.cancel()
            assertFalse(settings.showHiddenFiles)
            settings.showHiddenFiles = true
        } finally {
            settings.shutdown()
        }
        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals(true, reopened.showHiddenFiles)
        } finally {
            reopened.shutdown()
        }
    }

    @Test fun `reload keeps un-flushed in-memory edits instead of reverting to disk`() {
        writeSettingsJson(tmp.root, """{"font":"old"}""")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals("old", settings.font)

        // Edit with the debounced save still pending; disk continues to hold "old".
        // reload() must not let loadFromDisk clobber the newer in-memory value.
        settings.font = "new"
        settings.reload()

        assertEquals("new", settings.font)
    }

    @Test
    @Config(sdk = [28])
    fun `keyboard defaults to Piannoli and system choice updates live cancels and persists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            settings.setupCompleted = true
            assertEquals(KeyboardType.PIANNOLI, settings.keyboardType)
            val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
            viewModel.load()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "input" })
            viewModel.enterCategory()
            viewModel.selectItem("keyboard_type")
            assertEquals(R.string.keyboard_piannoli, viewModel.getSelectedItem()?.valueRes)
            viewModel.cycleSelected(1)
            assertEquals(KeyboardType.SYSTEM, settings.keyboardType)
            assertEquals(KeyboardType.SYSTEM, viewModel.appSettings.value.keyboardType)
            assertEquals(R.string.keyboard_system, viewModel.getSelectedItem()?.valueRes)
            viewModel.cancel()
            assertEquals(KeyboardType.PIANNOLI, settings.keyboardType)
            assertEquals(KeyboardType.PIANNOLI, viewModel.appSettings.value.keyboardType)
            settings.keyboardType = KeyboardType.SYSTEM
        } finally {
            settings.shutdown()
        }
        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals(KeyboardType.SYSTEM, reopened.keyboardType)
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `scrape failure behavior defaults to retry skip cycles cancels and persists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            settings.setupCompleted = true
            assertEquals(ScrapeFailBehavior.RETRY_SKIP, settings.scrapeFailBehavior)
            val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
            viewModel.load()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "library" })
            viewModel.enterCategory()
            viewModel.enterSubCategory("artwork", R.string.settings_artwork)
            viewModel.selectItem("scrape_fail_behavior")
            assertEquals(R.string.scrape_fail_retry_skip, viewModel.getSelectedItem()?.valueRes)
            viewModel.cycleSelected(1)
            assertEquals(ScrapeFailBehavior.RETRY_BEST_MATCH, settings.scrapeFailBehavior)
            viewModel.cycleSelected(1)
            assertEquals(ScrapeFailBehavior.SKIP, settings.scrapeFailBehavior)
            viewModel.cycleSelected(1)
            assertEquals(ScrapeFailBehavior.BEST_MATCH, settings.scrapeFailBehavior)
            viewModel.cancel()
            assertEquals(ScrapeFailBehavior.RETRY_SKIP, settings.scrapeFailBehavior)
            settings.scrapeFailBehavior = ScrapeFailBehavior.RETRY_BEST_MATCH
        } finally {
            settings.shutdown()
        }
        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals(ScrapeFailBehavior.RETRY_BEST_MATCH, reopened.scrapeFailBehavior)
        } finally {
            reopened.shutdown()
        }
    }

    @Test fun `reload picks up external disk changes when nothing is pending`() {
        writeSettingsJson(tmp.root, """{"font":"old"}""")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals("old", settings.font)

        // No pending local edit: a clean reload should still surface a file an
        // external writer changed underneath us.
        writeSettingsJson(tmp.root, """{"font":"external"}""")
        settings.reload()

        assertEquals("external", settings.font)
    }

    @Test fun `language persists to json and mirrors to piannoli_locale`() {
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals("en", settings.language)

        settings.language = "fr-FR"
        assertEquals("fr-FR", settings.language)

        val mirror = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("piannoli_locale", Context.MODE_PRIVATE)
            .getString("language", "")
        assertEquals("fr-FR", mirror)
    }

    @Test fun `clearing language falls back to the English default`() {
        writeSettingsJson(tmp.root, """{"language":"fr-FR"}""")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals("fr-FR", settings.language)

        settings.language = ""
        assertEquals("en", settings.language)
    }

    @Test fun `SteamGridDB key persists privately across repository recreation and can be forgotten`() {
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        settings.steamGridApiKey = "persistent-key"
        settings.reload()
        settings.shutdown()

        val reopened = newRepo()
        try {
            reopened.sdCardRoot = tmp.root.absolutePath
            assertEquals("persistent-key", reopened.steamGridApiKey)
            assertFalse(File(tmp.root, "Config/settings.json").readText().contains("persistent-key"))
            reopened.steamGridApiKey = ""
            val afterForget = newRepo()
            try {
                assertEquals("", afterForget.steamGridApiKey)
            } finally {
                afterForget.shutdown()
            }
        } finally {
            reopened.shutdown()
        }
    }

    @Test fun `missing artwork settings use defaults while explicit square radius is preserved`() {
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            assertEquals(ArtScale.FIT, settings.artScale)
            assertEquals(40, settings.artWidth)
            assertEquals(25, settings.artRadius)
            writeSettingsJson(tmp.root, """{"art_radius":0}""")
            settings.reload()
            assertEquals(0, settings.artRadius)
        } finally {
            settings.shutdown()
        }
    }

    @Test fun `artwork radius loads from saved settings and clamps to supported range`() {
        writeSettingsJson(tmp.root, """{"art_radius":67}""")
        val settings = newRepo()
        try {
            settings.sdCardRoot = tmp.root.absolutePath
            assertEquals(67, settings.artRadius)
            settings.artRadius = -1
            assertEquals(0, settings.artRadius)
            settings.artRadius = 101
            assertEquals(100, settings.artRadius)
        } finally {
            settings.shutdown()
        }
    }

    @Test fun `artwork controls moved out of display and radius updates live with cancel support`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = newRepo()
        try {
            settings.artRadius = 50
            val viewModel = SettingsViewModel(settings, AppFonts(context), context, mockk(relaxed = true))
            viewModel.load()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "display" })
            viewModel.enterCategory()
            assertFalse(viewModel.state.value.items.any { it.key in listOf("art_scale", "art_width") })
            viewModel.exitSubList()
            viewModel.setCategoryIndex(viewModel.state.value.categories.indexOfFirst { it.key == "library" })
            viewModel.enterCategory()
            viewModel.enterSubCategory("artwork", R.string.settings_artwork)
            viewModel.enterSubCategory("game_artwork", R.string.settings_game_artwork)
            viewModel.selectItem("art_radius")
            viewModel.cycleSelected(1)
            assertEquals(51, settings.artRadius)
            assertEquals(51, viewModel.appSettings.value.artRadius)
            assertEquals("51%", viewModel.getSelectedItemDisplayValue())
            repeat(60) { viewModel.cycleSelected(1) }
            assertEquals(100, settings.artRadius)
            repeat(110) { viewModel.cycleSelected(-1) }
            assertEquals(0, settings.artRadius)
            viewModel.cancel()
            assertEquals(50, settings.artRadius)
            assertEquals(50, viewModel.appSettings.value.artRadius)
        } finally {
            settings.shutdown()
        }
    }
}
