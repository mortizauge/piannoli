package dev.cannoli.scorza.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.cannoli.scorza.BuildConfig
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.settings.ArtScale
import dev.cannoli.scorza.settings.BatteryDisplay
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.settings.KeyboardType
import dev.cannoli.scorza.settings.ScrapeFailBehavior
import dev.cannoli.scorza.settings.TextSize
import dev.cannoli.scorza.settings.TimeFormat
import dev.cannoli.scorza.util.FontNameParser
import dev.cannoli.scorza.util.sortedNatural
import dev.cannoli.scorza.di.AppFonts
import dev.cannoli.ui.BULLET
import dev.cannoli.ui.theme.hexToColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@ActivityScoped
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val appFonts: AppFonts,
    @ApplicationContext private val context: Context,
    private val rommStore: dev.cannoli.scorza.romm.RommConnectionStore,
) {
    private var cannoliRoot: java.io.File? = null
    private var packageManager: PackageManager? = null
    private var appPackageName: String? = null
    private var collectionsRepository: CollectionsRepository? = null

    val isTelevision: Boolean
        get() = dev.cannoli.scorza.util.DeviceType.isTv(context)

    private fun isDefaultLauncher(): Boolean {
        val pm = packageManager ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == appPackageName
    }

    data class FontOption(val key: String, val label: String, val fontFamily: FontFamily, val typeface: android.graphics.Typeface?)

    private var fontOptions: List<FontOption> = buildFontOptions()

    private fun buildFontOptions(): List<FontOption> = buildList {
        add(FontOption("default", "Default", appFonts.mplus1Code, appFonts.mplus1CodeTypeface))
        add(FontOption("the_og", "The OG", appFonts.bpReplay, appFonts.bpReplayTypeface))
        val fontsDir = cannoliRoot?.let { java.io.File(it, "Config/Fonts") }
        val exts = setOf("ttf", "otf")
        val customFiles = fontsDir?.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in exts }
            ?: emptyList()
        for (file in customFiles.sortedNatural { it.name }) {
            val typeface = try { android.graphics.Typeface.createFromFile(file) } catch (_: Exception) { null } ?: continue
            val family = FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
            val label = FontNameParser.getFamilyName(file) ?: file.nameWithoutExtension
            add(FontOption(file.name, label, family, typeface))
        }
    }

    private fun resolveFont(): FontFamily {
        val chosen = fontOptions.firstOrNull { it.key == settings.font } ?: return appFonts.mplus1Code
        val sample = dev.cannoli.scorza.i18n.LanguageCatalog.byTag(settings.language)?.coverageSample
        if (sample != null) {
            val typeface = chosen.typeface
            if (typeface == null || !dev.cannoli.scorza.i18n.FontCoverage.covers(typeface, sample)) {
                return appFonts.mplus1Code
            }
        }
        return chosen.fontFamily
    }

    data class SettingsItem(
        val key: String,
        @param:StringRes val labelRes: Int,
        val labelText: String? = null,
        @param:StringRes val valueRes: Int? = null,
        val valueText: String? = null,
        val isEditable: Boolean = false,
        val canCycle: Boolean = true,
        val swatchColor: Color? = null,
        val disabled: Boolean = false
    )

    data class Category(
        val key: String,
        @param:StringRes val labelRes: Int
    )

    var raPassword: String = ""

    var updateInfo: dev.cannoli.scorza.updater.UpdateInfo? = null
        set(value) {
            field = value
            reloadCategories()
        }

    data class CategoryParent(val key: String, @param:StringRes val label: Int?, val selectedIndex: Int)

    data class State(
        val categories: List<Category> = emptyList(),
        val categoryIndex: Int = 0,
        val activeCategory: String? = null,
        val categoryParents: List<CategoryParent> = emptyList(),
        @param:StringRes val activeCategoryLabel: Int? = null,
        val items: List<SettingsItem> = emptyList(),
        val remappingAction: dev.cannoli.ui.MenuAction? = null,
        val selectedIndex: Int = 0
    ) {
        val inSubList: Boolean get() = activeCategory != null
    }

    data class AppSettings(
        val use24h: Boolean = false,
        val backgroundImagePath: String? = null,
        val backgroundTint: Int = 0,
        val textSize: TextSize = TextSize.DEFAULT,

        val fontFamily: FontFamily = FontFamily.Default,
        val languageTag: String = "",
        val title: String = "",
        val colorHighlight: Color = Color.White,
        val colorText: Color = Color.White,
        val colorHighlightText: Color = Color.Black,
        val colorAccent: Color = Color.White,
        val colorTitle: Color = Color.White,
        val colorBackground: Color = Color.Black,
        val colorStatusBar: Color = Color.White,
        val showWifi: Boolean = true,
        val showBluetooth: Boolean = true,
        val showVpn: Boolean = false,
        val showClock: Boolean = true,
        val batteryDisplay: BatteryDisplay = BatteryDisplay.DEFAULT,
        val showUpdate: Boolean = true,
        val showKitchen: Boolean = true,
        val showDownloads: Boolean = true,
        val swapPlayResume: Boolean = false,
        val mainMenuQuit: Boolean = false,
        val keyboardType: KeyboardType = KeyboardType.PIANNOLI,
        val piannoliBindings: Map<dev.cannoli.ui.MenuAction, dev.cannoli.ui.MenuAction> = emptyMap(),
        val artWidth: Int = 40,
        val artRadius: Int = 25,
        val systemArtScale: ArtScale = ArtScale.FIT,
        val systemArtWidth: Int = 40,
        val systemArtRadius: Int = 0,
        val systemArtworkType: dev.cannoli.scorza.settings.SystemArtworkType = dev.cannoli.scorza.settings.SystemArtworkType.LOGO,
        val artScale: ArtScale = ArtScale.DEFAULT,
        val contentMode: ContentMode = ContentMode.PLATFORMS,
        val fghCollectionId: Long? = null,
        val fghCollectionDisplayName: String? = null,
        val portraitMarginPx: Int = 0,
        val screenGeometryWidth: Int = 100,
        val screenGeometryHeight: Int = 100,
        val screenGeometryX: Int = 0,
        val screenGeometryY: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _appSettings = MutableStateFlow(readAppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings

    private fun readAppSettings() = AppSettings(
        use24h = settings.timeFormat == TimeFormat.TWENTY_FOUR_HOUR,
        backgroundImagePath = settings.backgroundImagePath,
        backgroundTint = settings.backgroundTint,
        textSize = settings.textSize,
        fontFamily = resolveFont(),
        languageTag = settings.language,
        title = settings.title,
        colorHighlight = hexToColor(settings.colorHighlight) ?: Color.White,
        colorText = hexToColor(settings.colorText) ?: Color.White,
        colorHighlightText = hexToColor(settings.colorHighlightText) ?: Color.Black,
        colorAccent = hexToColor(settings.colorAccent) ?: Color.White,
        colorTitle = hexToColor(settings.colorTitle) ?: Color.White,
        colorBackground = hexToColor(settings.colorBackground) ?: Color.Black,
        colorStatusBar = hexToColor(settings.colorStatusBar) ?: Color.White,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        batteryDisplay = settings.batteryDisplay,
        showUpdate = settings.showUpdate,
        showKitchen = settings.showKitchen,
        showDownloads = settings.showDownloads,
        swapPlayResume = settings.swapPlayResume,
        mainMenuQuit = settings.mainMenuQuit,
        keyboardType = settings.keyboardType,
        piannoliBindings = settings.piannoliBindings,
        artWidth = settings.artWidth,
        artRadius = settings.artRadius,
        systemArtScale = settings.systemArtScale,
        systemArtWidth = settings.systemArtWidth,
        systemArtRadius = settings.systemArtRadius,
        systemArtworkType = settings.systemArtworkType,
        artScale = settings.artScale,
        contentMode = settings.contentMode,
        fghCollectionId = settings.fghCollectionId,
        fghCollectionDisplayName = settings.fghCollectionId?.let { id ->
            collectionsRepository?.byId(id)?.displayName
        },
        portraitMarginPx = settings.portraitMarginPx,
        screenGeometryWidth = settings.screenGeometryWidth,
        screenGeometryHeight = settings.screenGeometryHeight,
        screenGeometryX = settings.screenGeometryX,
        screenGeometryY = settings.screenGeometryY,
    )

    private val allCategories = listOf(
        Category("general", R.string.settings_general),
        Category("display", R.string.settings_display),
        Category("library", R.string.settings_library),
        Category("input", R.string.settings_input),
        Category("emulation", R.string.settings_emulation),
        Category("integrations", R.string.settings_integrations),
        Category("advanced", R.string.settings_advanced),
        Category("about", R.string.settings_about),
    ) + if (BuildConfig.DEBUG) listOf(Category("debug", R.string.settings_debug)) else emptyList()

    private fun detectInstalledRaPackages(): List<String> {
        val pm = packageManager ?: return listOf(settings.retroArchPackage)
        return pm.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it.startsWith("com.retroarch") || it.startsWith("dev.cannoli.ricotta") }
    }

    private data class SettingsSnapshot(
        val keyboardType: KeyboardType,
        val piannoliBindings: Map<dev.cannoli.ui.MenuAction, dev.cannoli.ui.MenuAction>,
        val scrapeFailBehavior: ScrapeFailBehavior,
        val navigationSound: Boolean,
        val controllerNotifications: Boolean,
        val showHiddenFiles: Boolean,
        val textSize: TextSize,
        val font: String,
        val title: String,
        val timeFormat: TimeFormat,
        val bgImage: String?,
        val bgTint: Int,
        val colorHighlight: String,
        val colorText: String,
        val colorHighlightText: String,
        val colorAccent: String,
        val colorTitle: String,
        val colorBackground: String,
        val colorStatusBar: String,
        val swapPlayResume: Boolean,
        val showWifi: Boolean,
        val showBluetooth: Boolean,
        val showVpn: Boolean,
        val showClock: Boolean,
        val batteryDisplay: BatteryDisplay,
        val showRecentlyPlayed: Boolean,
        val showFavorites: Boolean,
        val contentMode: ContentMode,
        val fghCollectionId: Long?,
        val sdRoot: String,
        val romDirectory: String,
        val raPackage: String,
        val toolsName: String,
        val portsName: String,
        val releaseChannel: String,
        val artWidth: Int,
        val artRadius: Int,
        val systemArtScale: ArtScale,
        val systemArtWidth: Int,
        val systemArtRadius: Int,
        val systemArtworkType: dev.cannoli.scorza.settings.SystemArtworkType,
        val artScale: ArtScale,
        val portraitMarginPx: Int,
        val screenGeometryWidth: Int,
        val screenGeometryHeight: Int,
        val screenGeometryX: Int,
        val screenGeometryY: Int,
    )

    private var snapshot: SettingsSnapshot? = null

    fun load() {
        val current = _state.value
        if (current.inSubList) {
            // Lock/unlock or any onResume mid-settings: refresh values without wiping nav
            // state. Keep the existing cancel snapshot so revert still points at pre-edit
            // values rather than the just-resumed state.
            val cat = current.activeCategory ?: return
            val items = buildItemsForCategory(cat)
            _state.update { it.copy(categories = buildCategoryList(), items = items) }
            _appSettings.value = readAppSettings()
            return
        }
        snapshot = captureSettings()
        _state.value = State(categories = buildCategoryList(), categoryIndex = 0)
        _appSettings.value = readAppSettings()
    }

    fun reinitialize(root: java.io.File, pm: PackageManager, pkgName: String, cr: CollectionsRepository? = null) {
        cannoliRoot = root
        packageManager = pm
        appPackageName = pkgName
        if (cr != null) collectionsRepository = cr
        fontOptions = buildFontOptions()
        if (isTelevision && !settings.batteryDisplaySet) settings.batteryDisplay = BatteryDisplay.HIDE
        load()
    }

    private fun reloadCategories() {
        val current = _state.value
        if (current.inSubList) return
        _state.update { it.copy(categories = buildCategoryList()) }
    }

    private fun buildCategoryList(): List<Category> = buildList {
        addAll(allCategories)
    }

    fun save() {
        snapshot = captureSettings()
    }

    fun cancel() {
        snapshot?.let { restoreSettings(it) }
        _appSettings.value = readAppSettings()
    }

    fun moveSelection(delta: Int) {
        _state.update { current ->
            if (current.inSubList) {
                if (current.items.isEmpty()) return@update current
                val size = current.items.size
                val raw = current.selectedIndex + delta
                val newIndex = ((raw % size) + size) % size
                current.copy(selectedIndex = newIndex)
            } else {
                if (current.categories.isEmpty()) return@update current
                val size = current.categories.size
                val raw = current.categoryIndex + delta
                val newIndex = ((raw % size) + size) % size
                current.copy(categoryIndex = newIndex)
            }
        }
    }

    fun setCategoryIndex(index: Int) {
        _state.update { it.copy(categoryIndex = index) }
    }

    fun enterCategory(): Boolean {
        val current = _state.value
        if (current.inSubList) return false
        val cat = current.categories.getOrNull(current.categoryIndex) ?: return false
        if (cat.key == "display") fontOptions = buildFontOptions()
        val items = buildItemsForCategory(cat.key)
        _state.update {
            it.copy(activeCategory = cat.key, activeCategoryLabel = cat.labelRes, items = items, selectedIndex = 0)
        }
        return true
    }

    fun refreshSubList() {
        val current = _state.value
        val cat = current.activeCategory ?: return
        val items = buildItemsForCategory(cat)
        _state.update { it.copy(items = items, selectedIndex = it.selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    }

    fun selectItem(key: String) {
        val index = _state.value.items.indexOfFirst { it.key == key }
        if (index >= 0) _state.update { it.copy(selectedIndex = index) }
    }

    fun refreshAppSettings() {
        _appSettings.value = readAppSettings()
    }

    fun refreshItemsAndSettings() {
        val catKey = _state.value.activeCategory
        if (catKey != null) {
            val newItems = buildItemsForCategory(catKey)
            _state.update { it.copy(items = newItems, selectedIndex = it.selectedIndex.coerceAtMost((newItems.size - 1).coerceAtLeast(0))) }
        }
        _appSettings.value = readAppSettings()
    }

    private fun reclampGeometryOffsets() {
        val maxX = (100 - settings.screenGeometryWidth) / 2
        val maxY = (100 - settings.screenGeometryHeight) / 2
        settings.screenGeometryX = settings.screenGeometryX.coerceIn(-maxX, maxX)
        settings.screenGeometryY = settings.screenGeometryY.coerceIn(-maxY, maxY)
    }

    fun resetScreenGeometry() {
        settings.screenGeometryWidth = 100
        settings.screenGeometryHeight = 100
        settings.screenGeometryX = 0
        settings.screenGeometryY = 0
        refreshItemsAndSettings()
    }

    fun enterSubCategory(key: String, @StringRes labelRes: Int, initialIndex: Int = 0) {
        val current = _state.value
        val items = buildItemsForCategory(key)
        val safeInitial = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        _state.update {
            val parent = current.activeCategory?.let { category ->
                CategoryParent(category, current.activeCategoryLabel, current.selectedIndex)
            }
            it.copy(
                activeCategory = key,
                categoryParents = current.categoryParents + listOfNotNull(parent),
                activeCategoryLabel = labelRes,
                items = items,
                selectedIndex = safeInitial,
            )
        }
    }

    fun fghPickerInitialIndex(): Int {
        val ids = fghCollections().map { it.id }
        val cur = settings.fghCollectionId ?: return 0
        return ids.indexOf(cur).coerceAtLeast(0)
    }

    fun selectFghCollectionId(id: Long?) {
        settings.fghCollectionId = id
        _appSettings.value = readAppSettings()
    }

    /** Drops back to the category list outright, however deep the user was. The active category
     *  outlives the screen, so without this a flow that ends elsewhere leaves settings reopening
     *  inside whichever sub-list it was abandoned in. */
    fun resetToCategoryList() {
        _state.update {
            it.copy(
                activeCategory = null,
                categoryParents = emptyList(),
                activeCategoryLabel = null,
                items = emptyList(),
                selectedIndex = 0,
            )
        }
    }

    fun exitSubList(): Boolean {
        val current = _state.value
        if (!current.inSubList) return false
        val parent = current.categoryParents.lastOrNull()
        if (parent != null) {
            val items = buildItemsForCategory(parent.key)
            _state.update {
                it.copy(
                    activeCategory = parent.key,
                    categoryParents = current.categoryParents.dropLast(1),
                    activeCategoryLabel = parent.label,
                    items = items,
                    selectedIndex = parent.selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                )
            }
        } else {
            _state.update {
                it.copy(activeCategory = null, activeCategoryLabel = null, items = emptyList(), selectedIndex = 0)
            }
        }
        return true
    }

    fun cycleSelected(direction: Int, repeatCount: Int = 0) {
        val current = _state.value
        if (!current.inSubList) return
        val item = current.items.getOrNull(current.selectedIndex) ?: return

        if (item.key.startsWith("remap:") || current.remappingAction != null) return
        when (item.key) {
            "remap_reset" -> settings.piannoliBindings = emptyMap()
            "keyboard_type" -> settings.keyboardType =
                if (settings.keyboardType == KeyboardType.PIANNOLI) KeyboardType.SYSTEM else KeyboardType.PIANNOLI
            "scrape_fail_behavior" -> {
                val entries = ScrapeFailBehavior.entries
                val index = entries.indexOf(settings.scrapeFailBehavior)
                settings.scrapeFailBehavior = entries[Math.floorMod(index + direction, entries.size)]
            }
            "navigation_sound" -> settings.navigationSound = !settings.navigationSound
            "controller_notifications" -> settings.controllerNotifications = !settings.controllerNotifications
            "text_size" -> {
                val entries = TextSize.entries
                val cur = entries.indexOf(settings.textSize).coerceAtLeast(0)
                settings.textSize = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            "font" -> {
                val cur = fontOptions.indexOfFirst { it.key == settings.font }.coerceAtLeast(0)
                settings.font = fontOptions[((cur + direction) % fontOptions.size + fontOptions.size) % fontOptions.size].key
            }
            "language" -> {
                val tags = dev.cannoli.scorza.i18n.LanguageCatalog.ALL.map { it.tag }
                val cur = tags.indexOf(settings.language).coerceAtLeast(0)
                settings.language = tags[((cur + direction) % tags.size + tags.size) % tags.size]
            }
            "show_clock" -> {
                if (!settings.showClock) {
                    settings.showClock = true
                    settings.timeFormat = if (direction > 0) TimeFormat.TWELVE_HOUR else TimeFormat.TWENTY_FOUR_HOUR
                } else if (settings.timeFormat == TimeFormat.TWELVE_HOUR && direction > 0) {
                    settings.timeFormat = TimeFormat.TWENTY_FOUR_HOUR
                } else if (settings.timeFormat == TimeFormat.TWENTY_FOUR_HOUR && direction < 0) {
                    settings.timeFormat = TimeFormat.TWELVE_HOUR
                } else {
                    settings.showClock = false
                }
            }
            "art_width", "system_art_width" -> {
                val system = item.key == "system_art_width"
                val steps = (35..65 step 5) + 0
                val cur = steps.indexOf(if (system) settings.systemArtWidth else settings.artWidth).coerceAtLeast(0)
                val next = steps[Math.floorMod(cur + direction, steps.size)]
                if (system) settings.systemArtWidth = next else settings.artWidth = next
            }
            "art_radius" -> settings.artRadius = (settings.artRadius + direction).coerceIn(0, 100)
            "system_art_radius" -> settings.systemArtRadius = (settings.systemArtRadius + direction).coerceIn(0, 100)
            "system_artwork_type" -> {
                val types = dev.cannoli.scorza.settings.SystemArtworkType.entries
                settings.systemArtworkType = types[Math.floorMod(types.indexOf(settings.systemArtworkType) + direction, types.size)]
            }
            "art_scale", "system_art_scale" -> {
                val system = item.key == "system_art_scale"
                val entries = ArtScale.entries
                val cur = entries.indexOf(if (system) settings.systemArtScale else settings.artScale).coerceAtLeast(0)
                val next = entries[Math.floorMod(cur + direction, entries.size)]
                if (system) settings.systemArtScale = next else settings.artScale = next
            }
            "bg_image" -> cycleBackgroundImage(direction)
            "bg_tint" -> {
                val cur = settings.backgroundTint
                val next = cur + direction * 10
                settings.backgroundTint = when {
                    next > 90 -> 0
                    next < 0 -> 90
                    else -> next
                }
            }
            "swap_play_resume" -> settings.swapPlayResume = !settings.swapPlayResume
            "content_mode" -> {
                val entries = ContentMode.entries
                val cur = entries.indexOf(settings.contentMode).coerceAtLeast(0)
                settings.contentMode = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            "fgh_collection" -> {
                val ids = fghCollections().map { it.id }
                if (ids.isNotEmpty()) {
                    val cur = ids.indexOf(settings.fghCollectionId).coerceAtLeast(0)
                    val next = ((cur + direction) % ids.size + ids.size) % ids.size
                    settings.fghCollectionId = ids[next]
                }
            }
            "show_recently_played" -> settings.showRecentlyPlayed = !settings.showRecentlyPlayed
            "show_favorites" -> settings.showFavorites = !settings.showFavorites
            "scan_library" -> settings.scanLibraryAutomatically = !settings.scanLibraryAutomatically
            "hidden_files" -> settings.showHiddenFiles = !settings.showHiddenFiles
            "show_wifi" -> settings.showWifi = !settings.showWifi
            "show_bluetooth" -> settings.showBluetooth = !settings.showBluetooth
            "show_vpn" -> settings.showVpn = !settings.showVpn
            "show_kitchen" -> settings.showKitchen = !settings.showKitchen
            "show_downloads" -> settings.showDownloads = !settings.showDownloads
            "show_battery" -> {
                val entries = BatteryDisplay.entries
                val cur = entries.indexOf(settings.batteryDisplay).coerceAtLeast(0)
                settings.batteryDisplay = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            "show_update" -> settings.showUpdate = !settings.showUpdate
            "main_menu_quit" -> settings.mainMenuQuit = !settings.mainMenuQuit
            "always_save_on_quit" -> settings.alwaysSaveOnQuit = !settings.alwaysSaveOnQuit
            "portrait_margin" -> {
                val step = when {
                    repeatCount == 0 -> 1
                    repeatCount < 10 -> 10
                    else -> 25
                }
                settings.portraitMarginPx = (settings.portraitMarginPx + direction * step).coerceAtLeast(0)
            }
            "screen_geo_width" -> {
                val step = if (repeatCount == 0) 1 else 5
                settings.screenGeometryWidth = (settings.screenGeometryWidth + direction * step).coerceIn(50, 100)
                reclampGeometryOffsets()
            }
            "screen_geo_height" -> {
                val step = if (repeatCount == 0) 1 else 5
                settings.screenGeometryHeight = (settings.screenGeometryHeight + direction * step).coerceIn(50, 100)
                reclampGeometryOffsets()
            }
            "screen_geo_x" -> {
                val step = if (repeatCount == 0) 1 else 5
                val maxX = (100 - settings.screenGeometryWidth) / 2
                settings.screenGeometryX = (settings.screenGeometryX + direction * step).coerceIn(-maxX, maxX)
            }
            "screen_geo_y" -> {
                val step = if (repeatCount == 0) 1 else 5
                val maxY = (100 - settings.screenGeometryHeight) / 2
                settings.screenGeometryY = (settings.screenGeometryY + direction * step).coerceIn(-maxY, maxY)
            }
            "kitchen_code_bypass" -> {
                settings.kitchenCodeBypass = !settings.kitchenCodeBypass
                dev.cannoli.scorza.server.KitchenManager.setCodeBypass(settings.kitchenCodeBypass)
            }
            "experimental_features" -> {
                settings.experimentalFeatures = !settings.experimentalFeatures
            }
            "ra_package" -> {
                val pkgs = detectInstalledRaPackages()
                if (pkgs.isNotEmpty()) {
                    val cur = pkgs.indexOf(settings.retroArchPackage).coerceAtLeast(0)
                    settings.retroArchPackage = pkgs[((cur + direction) % pkgs.size + pkgs.size) % pkgs.size]
                }
            }
            "release_channel" -> {
                val channels = dev.cannoli.scorza.updater.ReleaseChannel.entries
                val cur = channels.indexOfFirst { it.name == settings.releaseChannel }.coerceAtLeast(0)
                settings.releaseChannel = channels[((cur + direction) % channels.size + channels.size) % channels.size].name
            }
            "romm_allow_self_signed" -> rommStore.allowSelfSigned = !rommStore.allowSelfSigned
        }

        val catKey = current.activeCategory ?: return
        val newItems = buildItemsForCategory(catKey)
        _state.update { it.copy(items = newItems, selectedIndex = it.selectedIndex.coerceAtMost((newItems.size - 1).coerceAtLeast(0))) }
        _appSettings.value = readAppSettings()
    }

    fun enterSelected(): String? {
        val current = _state.value
        if (!current.inSubList) return null
        val item = current.items.getOrNull(current.selectedIndex) ?: return null

        return if (item.isEditable) {
            item.key
        } else {
            null
        }

    }

    fun startRemapping() {
        val item = getSelectedItem() ?: return
        if (!item.key.startsWith("remap:")) return
        _state.update { it.copy(remappingAction = dev.cannoli.ui.MenuAction.valueOf(item.key.removePrefix("remap:"))) }
    }

    fun cancelRemapping() {
        _state.update { it.copy(remappingAction = null) }
    }

    fun assignRemapping(button: dev.cannoli.ui.MenuAction) {
        val action = _state.value.remappingAction ?: return
        if (button !in dev.cannoli.ui.MenuAction.remappable) return
        settings.piannoliBindings = dev.cannoli.ui.MenuAction.swap(settings.piannoliBindings, action, button)
        _state.update { it.copy(remappingAction = null, items = buildItemsForCategory("remap_piannoli")) }
        _appSettings.value = readAppSettings()
    }

    fun getSelectedItem(): SettingsItem? {
        val current = _state.value
        if (!current.inSubList) return null
        return current.items.getOrNull(current.selectedIndex)
    }

    fun getSelectedItemDisplayValue(): String {
        val item = getSelectedItem() ?: return ""
        return item.valueText ?: ""
    }

    private fun cycleBackgroundImage(direction: Int = 1) {
        val root = cannoliRoot ?: return
        val wallpapersDir = java.io.File(root, "Wallpapers")
        val imageExtensions = setOf("png", "jpg", "jpeg")
        val images = wallpapersDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in imageExtensions }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (images.isEmpty()) {
            settings.backgroundImagePath = null
            return
        }

        val currentPath = settings.backgroundImagePath
        val currentIndex = images.indexOfFirst { it.absolutePath == currentPath }

        val newIndex = if (currentIndex == -1) {
            if (direction > 0) 0 else images.lastIndex
        } else {
            val raw = currentIndex + direction
            if (raw < 0 || raw >= images.size) -1 else raw
        }

        settings.backgroundImagePath = if (newIndex < 0) null else images[newIndex].absolutePath
    }

    fun refreshActiveCategory() {
        val catKey = _state.value.activeCategory ?: return
        _state.update { it.copy(items = buildItemsForCategory(catKey)) }
    }

    fun getColorEntries(): List<dev.cannoli.scorza.ui.screens.ColorEntry> {
        val names = mapOf(
            "color_accent" to R.string.setting_color_accent,
            "color_background" to R.string.setting_color_background,
            "color_highlight" to R.string.setting_color_highlight,
            "color_highlight_text" to R.string.setting_color_highlight_text,
            "color_status_bar" to R.string.setting_color_status_bar,
            "color_text" to R.string.setting_color_text,
            "color_title" to R.string.setting_color_title
        )
        return names.map { (key, labelRes) ->
            val hex = getColorHex(key)
            val color = hexToColor(hex)
            dev.cannoli.scorza.ui.screens.ColorEntry(
                key = key,
                labelRes = labelRes,
                hex = hex,
                color = dev.cannoli.ui.theme.colorToArgbLong(color ?: androidx.compose.ui.graphics.Color.White)
            )
        }
    }

    fun getColorHex(key: String): String = when (key) {
        "color_highlight" -> settings.colorHighlight
        "color_text" -> settings.colorText
        "color_highlight_text" -> settings.colorHighlightText
        "color_accent" -> settings.colorAccent
        "color_title" -> settings.colorTitle
        "color_background" -> settings.colorBackground
        "color_status_bar" -> settings.colorStatusBar
        else -> "#FFFFFF"
    }

    fun setColor(key: String, hex: String) {
        when (key) {
            "color_highlight" -> settings.colorHighlight = hex
            "color_text" -> settings.colorText = hex
            "color_highlight_text" -> settings.colorHighlightText = hex
            "color_accent" -> settings.colorAccent = hex
            "color_title" -> settings.colorTitle = hex
            "color_background" -> settings.colorBackground = hex
            "color_status_bar" -> settings.colorStatusBar = hex
        }
        val catKey = _state.value.activeCategory ?: return
        _state.update { it.copy(items = buildItemsForCategory(catKey)) }
        _appSettings.value = readAppSettings()
    }

    private fun captureSettings() = SettingsSnapshot(
        keyboardType = settings.keyboardType,
        piannoliBindings = settings.piannoliBindings,
        scrapeFailBehavior = settings.scrapeFailBehavior,
        navigationSound = settings.navigationSound,
        controllerNotifications = settings.controllerNotifications,
        showHiddenFiles = settings.showHiddenFiles,
        textSize = settings.textSize,
        font = settings.font,
        title = settings.title,
        timeFormat = settings.timeFormat,
        bgImage = settings.backgroundImagePath,
        bgTint = settings.backgroundTint,
        colorHighlight = settings.colorHighlight,
        colorText = settings.colorText,
        colorHighlightText = settings.colorHighlightText,
        colorAccent = settings.colorAccent,
        colorTitle = settings.colorTitle,
        colorBackground = settings.colorBackground,
        colorStatusBar = settings.colorStatusBar,
        swapPlayResume = settings.swapPlayResume,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        batteryDisplay = settings.batteryDisplay,
        showRecentlyPlayed = settings.showRecentlyPlayed,
        showFavorites = settings.showFavorites,
        contentMode = settings.contentMode,
        fghCollectionId = settings.fghCollectionId,
        sdRoot = settings.sdCardRoot,
        romDirectory = settings.romDirectory,
        raPackage = settings.retroArchPackage,
        toolsName = settings.toolsName,
        portsName = settings.portsName,
        releaseChannel = settings.releaseChannel,
        artWidth = settings.artWidth,
        artRadius = settings.artRadius,
        systemArtScale = settings.systemArtScale,
        systemArtWidth = settings.systemArtWidth,
        systemArtRadius = settings.systemArtRadius,
        systemArtworkType = settings.systemArtworkType,
        artScale = settings.artScale,
        portraitMarginPx = settings.portraitMarginPx,
        screenGeometryWidth = settings.screenGeometryWidth,
        screenGeometryHeight = settings.screenGeometryHeight,
        screenGeometryX = settings.screenGeometryX,
        screenGeometryY = settings.screenGeometryY,
    )

    private fun restoreSettings(snap: SettingsSnapshot) {
        settings.keyboardType = snap.keyboardType
        settings.piannoliBindings = snap.piannoliBindings
        settings.scrapeFailBehavior = snap.scrapeFailBehavior
        settings.navigationSound = snap.navigationSound
        settings.controllerNotifications = snap.controllerNotifications
        settings.showHiddenFiles = snap.showHiddenFiles
        settings.textSize = snap.textSize
        settings.font = snap.font
        settings.title = snap.title
        settings.timeFormat = snap.timeFormat
        settings.backgroundImagePath = snap.bgImage
        settings.backgroundTint = snap.bgTint
        settings.colorHighlight = snap.colorHighlight
        settings.colorText = snap.colorText
        settings.colorHighlightText = snap.colorHighlightText
        settings.colorAccent = snap.colorAccent
        settings.colorTitle = snap.colorTitle
        settings.colorBackground = snap.colorBackground
        settings.colorStatusBar = snap.colorStatusBar
        settings.swapPlayResume = snap.swapPlayResume
        settings.showWifi = snap.showWifi
        settings.showBluetooth = snap.showBluetooth
        settings.showVpn = snap.showVpn
        settings.showClock = snap.showClock
        settings.batteryDisplay = snap.batteryDisplay
        settings.showRecentlyPlayed = snap.showRecentlyPlayed
        settings.showFavorites = snap.showFavorites
        settings.contentMode = snap.contentMode
        settings.fghCollectionId = snap.fghCollectionId
        settings.sdCardRoot = snap.sdRoot
        settings.romDirectory = snap.romDirectory
        settings.retroArchPackage = snap.raPackage
        settings.toolsName = snap.toolsName
        settings.portsName = snap.portsName
        settings.releaseChannel = snap.releaseChannel
        settings.artWidth = snap.artWidth
        settings.artRadius = snap.artRadius
        settings.systemArtScale = snap.systemArtScale
        settings.systemArtWidth = snap.systemArtWidth
        settings.systemArtRadius = snap.systemArtRadius
        settings.systemArtworkType = snap.systemArtworkType
        settings.artScale = snap.artScale
        settings.portraitMarginPx = snap.portraitMarginPx
        settings.screenGeometryWidth = snap.screenGeometryWidth
        settings.screenGeometryHeight = snap.screenGeometryHeight
        settings.screenGeometryX = snap.screenGeometryX
        settings.screenGeometryY = snap.screenGeometryY
    }

    private fun fghCollections(): List<CollectionsRepository.CollectionRow> {
        val cr = collectionsRepository ?: return emptyList()
        return cr.all().filter { it.type == CollectionType.STANDARD }
    }

    private fun onOff(value: Boolean) = if (value) R.string.value_on else R.string.value_off
    private fun showHide(value: Boolean) = if (value) R.string.value_show else R.string.value_hide
    private fun buildItemsForCategory(categoryKey: String): List<SettingsItem> = when (categoryKey) {
        "general" -> buildList {
            val langLabel = (dev.cannoli.scorza.i18n.LanguageCatalog.byTag(settings.language)
                ?: dev.cannoli.scorza.i18n.LanguageCatalog.ALL.first()).nativeName
            add(SettingsItem("language", R.string.setting_language, valueText = langLabel))
            add(SettingsItem("title", R.string.setting_title, valueText = settings.title.ifEmpty { null }, valueRes = if (settings.title.isEmpty()) R.string.value_none else null, isEditable = true))
            add(SettingsItem("swap_play_resume", R.string.setting_swap_play_resume, valueRes = onOff(settings.swapPlayResume)))
            add(SettingsItem("main_menu_quit", R.string.setting_main_menu_quit, valueRes = onOff(settings.mainMenuQuit)))
            if (!isTelevision) {
                val launcherLabel = if (isDefaultLauncher()) {
                    R.string.setting_change_default_launcher
                } else {
                    R.string.setting_set_default_launcher
                }
                add(SettingsItem("set_default_launcher", launcherLabel, isEditable = true))
            }
        }
        "display" -> buildList {
            add(SettingsItem("artwork", R.string.settings_artwork, isEditable = true))
            add(SettingsItem("colors", R.string.setting_colors, isEditable = true))
            add(SettingsItem("bg_image", R.string.setting_bg_image, valueText = settings.backgroundImagePath?.let { java.io.File(it).name }, valueRes = if (settings.backgroundImagePath == null) R.string.value_none else null))
            if (settings.backgroundImagePath != null) {
                val tintVal = settings.backgroundTint
                add(SettingsItem("bg_tint", R.string.setting_bg_tint, valueText = if (tintVal == 0) null else "$tintVal%", valueRes = if (tintVal == 0) R.string.value_off else null))
            }
            val currentFont = fontOptions.firstOrNull { it.key == settings.font } ?: fontOptions.first()
            add(SettingsItem("font", R.string.setting_font, valueText = currentFont.label))
            add(SettingsItem("text_size", R.string.setting_text_size, valueText = "${settings.textSize.sp}sp"))
            add(SettingsItem("status_bar", R.string.settings_status_bar, isEditable = true))
            val marginPx = settings.portraitMarginPx
            add(SettingsItem(
                "portrait_margin",
                R.string.setting_portrait_margin,
                valueText = if (marginPx == 0) null else "$marginPx px",
                valueRes = if (marginPx == 0) R.string.value_off else null
            ))
        }
        "library" -> buildList {
            add(SettingsItem("hidden_files", R.string.setting_hidden_files,
                valueRes = if (settings.showHiddenFiles) R.string.value_show else R.string.value_hide))
            val contentModeRes = when (settings.contentMode) {
                ContentMode.PLATFORMS -> R.string.value_platforms
                ContentMode.COLLECTIONS -> R.string.value_collections
                ContentMode.FIVE_GAME_HANDHELD -> R.string.value_five_game_handheld
            }
            add(SettingsItem("content_mode", R.string.setting_content_mode, valueRes = contentModeRes))
            if (settings.contentMode == ContentMode.FIVE_GAME_HANDHELD) {
                val rows = fghCollections()
                val curId = settings.fghCollectionId
                val effective = rows.firstOrNull { it.id == curId } ?: rows.firstOrNull()
                if (effective != null && effective.id != curId) {
                    settings.fghCollectionId = effective.id
                }
                add(SettingsItem(
                    "fgh_collection",
                    R.string.setting_fgh_collection,
                    valueText = effective?.displayName,
                    valueRes = if (effective == null) R.string.value_none else null,
                    isEditable = rows.isNotEmpty(),
                    canCycle = rows.isNotEmpty()
                ))
            }
            if (settings.contentMode != ContentMode.FIVE_GAME_HANDHELD) {
                add(SettingsItem("show_recently_played", R.string.setting_show_recently_played, valueRes = showHide(settings.showRecentlyPlayed)))
                add(SettingsItem("show_favorites", R.string.setting_show_favorites, valueRes = showHide(settings.showFavorites)))
            }
            if (settings.contentMode == ContentMode.PLATFORMS) {
            }
            add(SettingsItem("manage_ports", R.string.setting_manage_ports, isEditable = true))
            add(SettingsItem("manage_tools", R.string.setting_manage_tools, isEditable = true))
            val scanRes = if (settings.scanLibraryAutomatically) R.string.value_automatically else R.string.value_manually
            add(SettingsItem("scan_library", R.string.setting_scan_library, valueRes = scanRes))
            add(SettingsItem("sd_root", R.string.setting_sd_root, valueText = settings.sdCardRoot, isEditable = true))
            val romDir = settings.romDirectory
            add(SettingsItem("rom_directory", R.string.setting_rom_directory, valueText = romDir.ifEmpty { null }, valueRes = if (romDir.isEmpty()) R.string.value_cannoli_root else null, isEditable = true, canCycle = false))
        }
        "fgh_collection_picker" -> buildList {
            val rows = fghCollections()
            val curId = settings.fghCollectionId
            for (row in rows) {
                add(SettingsItem(
                    key = "fgh_pick:${row.id}",
                    labelRes = R.string.setting_fgh_collection,
                    labelText = row.displayName,
                    valueRes = if (row.id == curId) R.string.value_selected else null,
                    isEditable = true,
                    canCycle = false
                ))
            }
        }
        "colors" -> listOf(
            SettingsItem("color_background", R.string.setting_color_background, valueText = settings.colorBackground.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorBackground)),
            SettingsItem("color_text", R.string.setting_color_text, valueText = settings.colorText.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorText)),
            SettingsItem("color_status_bar", R.string.setting_color_status_bar, valueText = settings.colorStatusBar.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorStatusBar)),
            SettingsItem("color_highlight", R.string.setting_color_highlight, valueText = settings.colorHighlight.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorHighlight)),
            SettingsItem("color_highlight_text", R.string.setting_color_highlight_text, valueText = settings.colorHighlightText.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorHighlightText)),
            SettingsItem("color_accent", R.string.setting_color_accent, valueText = settings.colorAccent.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorAccent))
        )
        "status_bar" -> buildList {
            val batteryRes = when (settings.batteryDisplay) {
                BatteryDisplay.HIDE -> R.string.value_hide
                BatteryDisplay.PERCENT -> R.string.value_percent
                BatteryDisplay.ICON -> R.string.value_icon
            }
            add(SettingsItem("show_battery", R.string.setting_battery, valueRes = batteryRes))
            add(SettingsItem("show_bluetooth", R.string.setting_bluetooth, valueRes = showHide(settings.showBluetooth)))
            add(SettingsItem("show_clock", R.string.setting_clock, valueRes = if (!settings.showClock) R.string.value_hide else if (settings.timeFormat == TimeFormat.TWELVE_HOUR) R.string.value_12h else R.string.value_24h))
            add(SettingsItem("show_kitchen", R.string.setting_kitchen_running, valueRes = showHide(settings.showKitchen)))
            add(SettingsItem("show_downloads", R.string.setting_downloads_running, valueRes = showHide(settings.showDownloads)))
            add(SettingsItem("show_update", R.string.setting_updater, valueRes = showHide(settings.showUpdate)))
            add(SettingsItem("show_vpn", R.string.setting_vpn, valueRes = showHide(settings.showVpn)))
            add(SettingsItem("show_wifi", R.string.setting_wifi, valueRes = showHide(settings.showWifi)))
        }
        "screen_geometry" -> buildList {
            add(SettingsItem("screen_geo_width", R.string.setting_geo_width, valueText = "${settings.screenGeometryWidth}%"))
            add(SettingsItem("screen_geo_height", R.string.setting_geo_height, valueText = "${settings.screenGeometryHeight}%"))
            add(SettingsItem("screen_geo_x", R.string.setting_geo_hpos, valueText = (if (settings.screenGeometryX >= 0) "+" else "") + settings.screenGeometryX, disabled = settings.screenGeometryWidth >= 100))
            add(SettingsItem("screen_geo_y", R.string.setting_geo_vpos, valueText = (if (settings.screenGeometryY >= 0) "+" else "") + settings.screenGeometryY, disabled = settings.screenGeometryHeight >= 100))
        }
        "input" -> listOf(
            SettingsItem("remap_piannoli", R.string.remap_piannoli, isEditable = true),
            SettingsItem("keyboard_type", R.string.setting_keyboard, valueRes =
                if (settings.keyboardType == KeyboardType.SYSTEM) R.string.keyboard_system else R.string.keyboard_piannoli),
            SettingsItem("navigation_sound", R.string.setting_navigation_sound, valueRes = onOff(settings.navigationSound)),
            SettingsItem("controller_notifications", R.string.setting_controller_notifications, valueRes = onOff(settings.controllerNotifications)),
            SettingsItem("controllers", R.string.setting_controllers, isEditable = true),
            SettingsItem("shortcuts", R.string.setting_shortcuts, isEditable = true),
            SettingsItem("input_tester", R.string.setting_input_tester, isEditable = true)
        )
        "remap_piannoli" -> buildList {
            val labels = listOf(
                R.string.remap_confirm, R.string.remap_back, R.string.remap_north, R.string.remap_west,
                R.string.remap_start, R.string.remap_select,
            )
            dev.cannoli.ui.MenuAction.remappable.forEachIndexed { index, action ->
                add(SettingsItem("remap:${action.name}", labels[index],
                    valueText = (settings.piannoliBindings[action] ?: action).name, isEditable = true, canCycle = false))
            }
            add(SettingsItem("remap_reset", R.string.remap_reset, isEditable = true))
        }
        "emulation" -> buildList {
            add(SettingsItem("core_mapping", R.string.setting_emulator_mapping, isEditable = true))
            val pkgs = detectInstalledRaPackages()
            if (pkgs.isNotEmpty() && settings.retroArchPackage !in pkgs) {
                settings.retroArchPackage = pkgs.first()
            }
            add(SettingsItem("ra_package", R.string.setting_ra_package, valueText = if (pkgs.isEmpty()) null else settings.retroArchPackage, valueRes = if (pkgs.isEmpty()) R.string.value_none_installed else null, canCycle = pkgs.size > 1))
            if (pkgs.isNotEmpty()) {
                val pkgLabel = InstalledCoreService.getPackageLabel(settings.retroArchPackage)
                add(SettingsItem("installed_cores", R.string.setting_installed_cores, labelText = context.getString(R.string.setting_installed_cores, pkgLabel), isEditable = true))
            }
            add(SettingsItem("always_save_on_quit", R.string.setting_always_save_on_quit, valueRes = onOff(settings.alwaysSaveOnQuit)))
        }
        "integrations" -> buildList {
            add(SettingsItem("integrations_ra", R.string.settings_retroachievements, isEditable = true))
            add(SettingsItem("integrations_romm", R.string.settings_romm, isEditable = true))
        }
        "retroachievements" -> buildList {
            add(SettingsItem("ra_username", R.string.setting_ra_username, valueText = settings.raUsername.ifEmpty { null }, valueRes = if (settings.raUsername.isEmpty()) R.string.value_not_set else null, isEditable = true))
            add(SettingsItem("ra_password", R.string.setting_ra_password, valueText = if (raPassword.isEmpty()) null else BULLET.repeat(raPassword.length), valueRes = if (raPassword.isEmpty()) R.string.value_not_set else null, isEditable = true))
            if (settings.raUsername.isNotEmpty() && raPassword.isNotEmpty()) {
                add(SettingsItem("ra_login", R.string.setting_ra_login, isEditable = true))
            }
        }
        "artwork" -> buildList {
            add(SettingsItem("game_artwork", R.string.settings_game_artwork, isEditable = true))
            add(SettingsItem("system_artwork", R.string.setting_system_artwork, isEditable = true))
            val failBehaviorRes = when (settings.scrapeFailBehavior) {
                ScrapeFailBehavior.SKIP -> R.string.scrape_fail_skip
                ScrapeFailBehavior.BEST_MATCH -> R.string.scrape_fail_best_match
                ScrapeFailBehavior.RETRY_SKIP -> R.string.scrape_fail_retry_skip
                ScrapeFailBehavior.RETRY_BEST_MATCH -> R.string.scrape_fail_retry_best_match
            }
            add(SettingsItem("scrape_fail_behavior", R.string.setting_scrape_fail_behavior, valueRes = failBehaviorRes))
            add(SettingsItem("setup_scrapers", R.string.settings_setup_scrapers, isEditable = true))
        }
        "game_artwork", "system_artwork" -> buildList {
            val system = categoryKey == "system_artwork"
            val scale = if (system) settings.systemArtScale else settings.artScale
            val width = if (system) settings.systemArtWidth else settings.artWidth
            val radius = if (system) settings.systemArtRadius else settings.artRadius
            val artScaleRes = when (scale) {
                ArtScale.FIT -> R.string.value_fit
                ArtScale.ORIGINAL -> R.string.value_original
                ArtScale.FIT_WIDTH -> R.string.value_fit_width
                ArtScale.FIT_HEIGHT -> R.string.value_fit_height
            }
            add(SettingsItem(if (system) "system_art_scale" else "art_scale", R.string.setting_art_scale, valueRes = artScaleRes))
            add(SettingsItem(if (system) "system_art_width" else "art_width", R.string.setting_art_width,
                valueText = if (width == 0) null else "$width%", valueRes = if (width == 0) R.string.value_off else null))
            add(SettingsItem(if (system) "system_art_radius" else "art_radius", R.string.setting_art_radius, valueText = "$radius%"))
            if (system) add(SettingsItem("system_artwork_type", R.string.setting_system_artwork_type, valueRes =
                when (settings.systemArtworkType) {
                    dev.cannoli.scorza.settings.SystemArtworkType.LOGO -> R.string.system_artwork_logo
                    dev.cannoli.scorza.settings.SystemArtworkType.ICON -> R.string.system_artwork_icon
                    dev.cannoli.scorza.settings.SystemArtworkType.GRID -> R.string.system_artwork_grid
                }))
        }
        "setup_scrapers" -> listOf(
            SettingsItem("steamgrid_setup", R.string.steamgrid_setup, isEditable = true),
        )
        "romm" -> buildList {
            if (rommStore.token.isNullOrEmpty()) {
                add(SettingsItem("romm_host", R.string.setting_romm_host, valueText = rommStore.host.ifEmpty { null }, valueRes = if (rommStore.host.isEmpty()) R.string.value_not_set else null, isEditable = true, canCycle = false))
                add(SettingsItem("romm_allow_self_signed", R.string.setting_romm_allow_self_signed, valueRes = onOff(rommStore.allowSelfSigned)))
                if (rommStore.host.isNotEmpty()) {
                    add(SettingsItem("romm_pair", R.string.setting_romm_pair, isEditable = true, canCycle = false))
                    add(SettingsItem("romm_pair_code", R.string.setting_romm_pair_code, isEditable = true, canCycle = false))
                }
            }
        }
        "advanced" -> buildList {
            add(SettingsItem("logging", R.string.setting_logging, isEditable = true))
            add(SettingsItem("screen_geometry", R.string.setting_screen_geometry, isEditable = true))
            add(SettingsItem("regenerate_system_folders", R.string.setting_regenerate_system_folders, isEditable = true))
            add(SettingsItem("kitchen_code_bypass", R.string.setting_kitchen_code_bypass, valueRes = onOff(settings.kitchenCodeBypass)))
            add(SettingsItem("experimental_features", R.string.setting_experimental_features, valueRes = onOff(settings.experimentalFeatures)))
            add(SettingsItem(
                "release_channel",
                R.string.settings_release_channel,
                valueText = dev.cannoli.scorza.updater.ReleaseChannel.fromString(settings.releaseChannel).label
            ))
        }
        "debug" -> listOf(
            SettingsItem("audit_emulator_intents", R.string.setting_audit_emulator_intents, isEditable = true),
            SettingsItem("icon_gallery", R.string.setting_icon_gallery, isEditable = true),
        )
        else -> emptyList()
    }
}
