package dev.cannoli.scorza.scraper

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.ui.components.KeyboardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

enum class ScraperField { QUERY, USERNAME, PASSWORD, API_KEY }

@ActivityScoped
class ScraperController @Inject constructor(
    @ActivityContext private val context: Context,
    @IoScope private val scope: CoroutineScope,
    private val nav: NavigationController,
    private val provider: ScraperProvider,
    private val artwork: ScrapedArtworkStore,
    private val games: GameListViewModel,
    private val steamGrid: SteamGridDbClient,
    private val settings: SettingsRepository,
) {
    private var useSteamGrid = true
    private var configuring = false
    private var apiKey: String
        get() = settings.steamGridApiKey
        set(value) { settings.steamGridApiKey = value }
    private var rom: ScrapeGame? = null
    private var query = ""
    private var username = ""
    private var password = ""
    private var systems: List<ScraperSystem> = emptyList()
    private val selectedSystems = mutableMapOf<String, ScraperSystem>()
    private var matches: List<ScraperMatch> = emptyList()
    private var actions: List<() -> Unit> = emptyList()
    private var job: Job? = null
    private var overwriteArtwork = false
    private data class MenuPage(
        val dialog: DialogState,
        val actions: List<() -> Unit>,
        val retainInHistory: Boolean,
    )
    private val history = mutableListOf<MenuPage>()
    private var currentPage: MenuPage? = null
    private var origin: DialogState = DialogState.None

    private fun begin() {
        job?.cancel()
        val dialog = nav.dialogState.value
        if (dialog !is DialogState.ScraperMenu && dialog !is DialogState.ScraperStatus &&
            dialog !is DialogState.ScraperInput && dialog != DialogState.ScraperOverwriteConfirm
        ) origin = dialog
        history.clear()
        currentPage = null
        actions = emptyList()
    }

    private fun rememberPage() {
        currentPage?.takeIf { it.retainInHistory }?.let { history.add(it.copy(dialog = nav.dialogState.value)) }
        currentPage = null
    }

    private fun show(
        dialog: DialogState,
        newActions: List<() -> Unit> = actions,
        replace: Boolean = false,
        retainInHistory: Boolean = dialog is DialogState.ScraperMenu || dialog is DialogState.ScraperInput,
    ) {
        if (!replace) rememberPage()
        actions = newActions
        currentPage = MenuPage(dialog, actions, retainInHistory)
        nav.dialogState.value = dialog
    }

    fun open(game: Rom, useSteamGrid: Boolean = true) {
        open(ScrapeGame.RomGame(game), useSteamGrid)
    }

    fun openPort(app: dev.cannoli.scorza.model.App) {
        open(ScrapeGame.PortGame(app), true)
    }

    private fun open(game: ScrapeGame, useSteamGrid: Boolean) {
        begin()
        this.useSteamGrid = useSteamGrid
        configuring = false
        rom = game
        query = game.displayName
        matches = emptyList()
        overwriteArtwork = false
        when {
            !useSteamGrid && !provider.configured -> message(R.string.scraper_not_configured)
            game.embeddedArtwork -> message(R.string.scraper_embedded_artwork)
            game.artFile != null -> show(DialogState.ScraperOverwriteConfirm)
            useSteamGrid && apiKey.isBlank() -> message(R.string.steamgrid_key_required)
            useSteamGrid -> search()
            else -> home()
        }
    }

    fun confirmOverwrite() {
        if (nav.dialogState.value != DialogState.ScraperOverwriteConfirm || rom == null) return
        overwriteArtwork = true
        when {
            useSteamGrid && apiKey.isBlank() -> message(R.string.steamgrid_key_required)
            useSteamGrid -> search()
            else -> home()
        }
    }

    fun openSystem(tag: String, displayName: String = tag) {
        open(ScrapeGame.SystemGame(tag, displayName, settings.systemArtworkType), true)
    }

    fun openSteamGridSetup() {
        begin()
        useSteamGrid = true
        configuring = true
        rom = null
        matches = emptyList()
        home()
    }

    private fun text(id: Int): String = context.getString(id)

    private fun menu(
        title: String,
        rows: List<Pair<String, () -> Unit>>,
        previews: List<String?> = emptyList(),
        replace: Boolean = false,
        retainInHistory: Boolean = true,
    ) {
        show(DialogState.ScraperMenu(title, rows.map { it.first }, previews = previews),
            rows.map { it.second }, replace, retainInHistory)
    }

    private fun home(replace: Boolean = false) {
        if (configuring) {
            menu(text(R.string.steamgrid_setup), listOf(
                "${text(R.string.steamgrid_api_key)}\t${text(if (apiKey.isEmpty()) R.string.scraper_not_set else R.string.scraper_set)}" to { edit(ScraperField.API_KEY) },
                text(R.string.steamgrid_open_api_page) to { openApiKeyPage() },
                text(R.string.steamgrid_paste_key) to { pasteApiKey() },
                text(R.string.steamgrid_forget_key) to {
                    apiKey = ""
                    home(replace = true)
                },
                text(R.string.scraper_close) to { close() },
            ), replace = replace)
            return
        }
        val game = rom ?: return
        if (useSteamGrid) {
            menu(context.getString(R.string.scraper_title, game.displayName), listOf(
                "${text(R.string.scraper_query)}\t$query" to { edit(ScraperField.QUERY) },
                text(R.string.scraper_search) to { search() },
                text(R.string.scraper_close) to { close() },
            ), replace = replace)
            return
        }
        menu(context.getString(R.string.scraper_title, game.displayName), listOf(
            "${text(R.string.scraper_query)}\t$query" to { edit(ScraperField.QUERY) },
            "${text(R.string.scraper_system)}\t${selectedSystems[game.platformTag]?.name ?: text(R.string.scraper_choose)}" to { chooseSystem() },
            "${text(R.string.scraper_username)}\t$username" to { edit(ScraperField.USERNAME) },
            "${text(R.string.scraper_password)}\t${if (password.isEmpty()) text(R.string.scraper_not_set) else text(R.string.scraper_set)}" to { edit(ScraperField.PASSWORD) },
            text(R.string.scraper_search) to { search() },
            text(R.string.scraper_forget_account) to {
                username = ""
                password = ""
                home(replace = true)
            },
            text(R.string.scraper_close) to { close() },
        ), replace = replace)
    }

    private fun edit(field: ScraperField) {
        val value = when (field) {
            ScraperField.QUERY -> query
            ScraperField.USERNAME -> username
            ScraperField.PASSWORD -> password
            ScraperField.API_KEY -> apiKey
        }
        show(DialogState.ScraperInput(field, KeyboardState(text = value, cursorPos = value.length)))
    }

    private fun openApiKeyPage() {
        edit(ScraperField.API_KEY)
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.steamgriddb.com/profile/preferences/api"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            message(R.string.steamgrid_browser_unavailable)
        }
    }

    private fun pasteApiKey() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val value = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        if (value.isNullOrEmpty()) {
            message(R.string.steamgrid_clipboard_empty)
            return
        }
        show(DialogState.ScraperInput(
            ScraperField.API_KEY, KeyboardState(text = value, cursorPos = value.length),
        ))
    }

    fun submit(input: DialogState.ScraperInput) {
        when (input.field) {
            ScraperField.QUERY -> query = input.currentName.trim()
            ScraperField.USERNAME -> username = input.currentName.trim()
            ScraperField.PASSWORD -> password = input.currentName
            ScraperField.API_KEY -> apiKey = input.currentName.trim()
        }
        if (currentPage?.dialog is DialogState.ScraperInput) back()
        home(replace = true)
    }

    fun confirm(menu: DialogState.ScraperMenu) {
        nav.dialogState.value = menu
        actions.getOrNull(menu.selectedIndex)?.invoke()
    }

    fun back() {
        job?.cancel()
        val previous = history.removeLastOrNull()
        if (previous == null) {
            close()
        } else {
            actions = previous.actions
            currentPage = previous
            nav.dialogState.value = previous.dialog
        }
    }

    private fun close() {
        job?.cancel()
        rom = null
        matches = emptyList()
        actions = emptyList()
        history.clear()
        currentPage = null
        configuring = false
        nav.dialogState.value = origin
    }

    private fun account(): ScraperAccount {
        if (username.isBlank() || password.isEmpty()) throw ScraperException(ScraperError.ACCOUNT_REQUIRED)
        return ScraperAccount(username, password)
    }

    private fun chooseSystem() {
        runRequest {
            val account = account()
            val available = systems.ifEmpty { provider.systems(account) }
            withContext(Dispatchers.Main) {
                systems = available
                menu(text(R.string.scraper_system), available.map { system ->
                    system.name to {
                        rom?.let { selectedSystems[it.platformTag] = system }
                        home()
                    }
                })
            }
        }
    }

    private fun search() {
        if (rom == null) return
        if (query.isBlank()) {
            message(R.string.scraper_query_required)
            return
        }
        if (useSteamGrid) {
            if (apiKey.isBlank()) {
                message(R.string.steamgrid_key_required)
                return
            }
            val searchQuery = query
            val key = apiKey
            runRequest {
                var found = steamGrid.search(key, searchQuery)
                coroutineContext.ensureActive()
                val cleaned = ScrapeTitleMatcher.cleanedQuery(searchQuery)
                if (found.isEmpty() && settings.scrapeFailBehavior.retry && cleaned.isNotBlank() && cleaned != searchQuery) {
                    found = steamGrid.search(key, cleaned)
                }
                withContext(Dispatchers.Main) {
                    if (found.isEmpty()) message(R.string.scraper_no_matches) else {
                        menu(text(R.string.steamgrid_results), found.map { match ->
                            "${match.name}\t#${match.id}" to { covers(match) }
                        } + listOf(text(R.string.scraper_edit_search) to { home() }))
                    }
                }
            }
            return
        }
        val game = rom ?: return
        val system = selectedSystems[game.platformTag]
        if (system == null) {
            chooseSystem()
            return
        }
        val searchQuery = query
        runRequest {
            val found = provider.search(account(), system, searchQuery)
            withContext(Dispatchers.Main) {
                matches = found
                if (found.isEmpty()) message(R.string.scraper_no_matches) else results()
            }
        }
    }

    private fun covers(match: SteamGridGame, page: Int = 0) {
        val key = apiKey
        runRequest {
            val system = rom as? ScrapeGame.SystemGame
            val found = if (system != null) steamGrid.systemArtwork(key, match.id, system.type, page)
                else steamGrid.covers(key, match.id, page)
            withContext(Dispatchers.Main) {
                if (found.covers.isEmpty()) {
                    message(R.string.scraper_no_artwork)
                } else {
                    val rows = found.covers.map { cover ->
                        "${cover.width} x ${cover.height}\t${cover.author}" to { downloadCover(cover) }
                    }.toMutableList()
                    val previews = mutableListOf<String?>()
                    previews.addAll(found.covers.map { it.thumbnail })
                    if (found.hasNextPage) {
                        rows.add(text(R.string.steamgrid_next_page) to { covers(match, page + 1) })
                        previews.add(null)
                    }
                    if (page > 0) {
                        rows.add(text(R.string.steamgrid_previous_page) to { covers(match, page - 1) })
                        previews.add(null)
                    }
                    rows.add(text(R.string.scraper_edit_search) to { home() })
                    menu(match.name, rows, previews)
                }
            }
        }
    }

    private fun downloadCover(cover: SteamGridCover) {
        val game = rom ?: return
        runRequest {
            val bytes = steamGrid.download(cover)
            save(game, bytes)
        }
    }

    private fun results() {
        menu(text(R.string.scraper_results), matches.map { match ->
            "${match.name}\t${match.system.name}" to { confirmMatch(match) }
        } + listOf(text(R.string.scraper_edit_search) to { home() }))
    }

    private fun confirmMatch(match: ScraperMatch) {
        if (match.artwork == null) {
            message(R.string.scraper_no_artwork)
            return
        }
        val game = rom ?: return
        menu("${match.name} (${match.system.name})", listOf(
            text(R.string.scraper_download) to {
                runRequest {
                    val bytes = provider.download(account(), match)
                    save(game, bytes)
                }
            },
            text(R.string.scraper_results) to { results() },
            text(R.string.scraper_close) to { close() },
        ), retainInHistory = false)
    }

    private suspend fun CoroutineScope.save(game: ScrapeGame, bytes: ByteArray) {
        ensureActive()
        game.install(artwork, bytes, overwriteArtwork)
        withContext(Dispatchers.Main) {
            if (game !is ScrapeGame.SystemGame) games.reload()
            origin = DialogState.None
            history.clear()
            currentPage = null
            val saved = DialogState.ScraperStatus(text(R.string.scraper_saved))
            show(saved)
            delay(3_000)
            if (nav.dialogState.value === saved) close()
        }
    }

    private fun message(id: Int) {
        show(DialogState.ScraperStatus(text(id)))
    }

    private fun runRequest(
        messageId: Int = if (useSteamGrid) R.string.steamgrid_working else R.string.scraper_working,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        if (job?.isActive == true) return
        rememberPage()
        val busy = DialogState.ScraperStatus(
            text(messageId), busy = true,
        )
        nav.dialogState.value = busy
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScraperException) {
                withContext(Dispatchers.Main) {
                    if (nav.dialogState.value === busy) message(errorString(e.error))
                }
            }
        }
    }

    private fun errorString(error: ScraperError): Int {
        if (useSteamGrid) {
            when (error) {
                ScraperError.ACCOUNT_REQUIRED -> return R.string.steamgrid_key_required
                ScraperError.AUTHENTICATION -> return R.string.steamgrid_authentication
                ScraperError.RATE_LIMIT -> return R.string.steamgrid_rate_limit
                ScraperError.UNAVAILABLE -> return R.string.steamgrid_unavailable
                ScraperError.NETWORK -> return R.string.steamgrid_network
                else -> {}
            }
        }
        return when (error) {
            ScraperError.NOT_CONFIGURED -> R.string.scraper_not_configured
            ScraperError.ACCOUNT_REQUIRED -> R.string.scraper_account_required
            ScraperError.AUTHENTICATION -> R.string.scraper_authentication
            ScraperError.RATE_LIMIT -> R.string.scraper_rate_limit
            ScraperError.DAILY_LIMIT -> R.string.scraper_daily_limit
            ScraperError.UNAVAILABLE -> R.string.scraper_unavailable
            ScraperError.INVALID_RESPONSE -> R.string.scraper_invalid_response
            ScraperError.NETWORK -> R.string.scraper_network
            ScraperError.NO_ARTWORK -> R.string.scraper_no_artwork
            ScraperError.EXISTING_ARTWORK -> R.string.scraper_existing_artwork
            ScraperError.STORAGE -> R.string.scraper_storage
        }
    }
}
