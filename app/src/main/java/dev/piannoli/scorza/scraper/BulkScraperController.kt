package dev.piannoli.scorza.scraper

import android.content.Context
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.R
import dev.piannoli.scorza.config.PlatformConfig
import dev.piannoli.scorza.db.RomsRepository
import dev.piannoli.scorza.di.IoScope
import dev.piannoli.scorza.db.AppsRepository
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.model.Platform
import dev.piannoli.scorza.navigation.NavigationController
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.settings.ScrapeFailBehavior
import dev.piannoli.scorza.ui.screens.DialogState
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
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

@ActivityScoped
class BulkScraperController @Inject constructor(
    @ActivityContext private val context: Context,
    @IoScope private val scope: CoroutineScope,
    private val nav: NavigationController,
    private val settings: SettingsRepository,
    private val roms: RomsRepository,
    private val platforms: PlatformConfig,
    private val steam: SteamGridDbClient,
    private val artwork: ScrapedArtworkStore,
    private val games: GameListViewModel,
    private val apps: AppsRepository,
) {
    private enum class Page { CONFIG, RUNNING, FINISHED }
    private var page = Page.CONFIG
    private var automatic = true
    private var includeSubfolders = true
    private var overwriteArtwork = false
    private var allPlatforms = false
    private var platform: String? = null
    private var systemPlatforms: List<Platform>? = null
    private var queue: List<ScrapeGame> = emptyList()
    private var index = 0
    private var saved = 0
    private var skipped = 0
    private var failed = 0
    private var key = ""
    private var failBehavior = ScrapeFailBehavior.DEFAULT
    private var actions: List<() -> Unit> = emptyList()
    private var job: Job? = null
    private var dismissJob: Job? = null
    private var returnToQuickMenu: () -> Unit = {}
    private data class MenuPage(val menu: DialogState.ScraperMenu, val actions: List<() -> Unit>)
    private val history = mutableListOf<MenuPage>()
    private val ioContext = scope.coroutineContext.minusKey(Job)

    fun open(platformTag: String? = null, onClose: () -> Unit) {
        open(platformTag, null, onClose)
    }

    fun openSystems(availablePlatforms: List<Platform>, onClose: () -> Unit) {
        open(null, availablePlatforms.toList(), onClose)
    }

    private fun open(platformTag: String?, availableSystems: List<Platform>?, onClose: () -> Unit) {
        job?.cancel()
        dismissJob?.cancel()
        returnToQuickMenu = onClose
        automatic = true
        includeSubfolders = true
        overwriteArtwork = false
        platform = platformTag
        systemPlatforms = availableSystems
        allPlatforms = platformTag == null
        configure()
    }

    fun confirm(menu: DialogState.ScraperMenu) {
        if (page == Page.CONFIG && menu.options.getOrNull(menu.selectedIndex)?.contains('\t') == true) return
        nav.dialogState.value = menu
        actions.getOrNull(menu.selectedIndex)?.invoke()
    }

    fun cycle(menu: DialogState.ScraperMenu) {
        if (page != Page.CONFIG || menu.options.getOrNull(menu.selectedIndex)?.contains('\t') != true) return
        actions.getOrNull(menu.selectedIndex)?.invoke()
        val updated = nav.dialogState.value as? DialogState.ScraperMenu ?: return
        nav.dialogState.value = updated.copy(selectedIndex = menu.selectedIndex)
    }

    fun back() {
        job?.cancel()
        dismissJob?.cancel()
        when (page) {
            Page.CONFIG -> if (nav.dialogState.value is DialogState.ScraperStatus) configure() else returnToQuickMenu()
            Page.RUNNING -> {
                val previous = history.removeLastOrNull()
                if (previous == null || history.isEmpty()) {
                    if (systemPlatforms == null) games.reload()
                    configure()
                    if (previous != null) nav.dialogState.value = previous.menu
                } else {
                    actions = previous.actions
                    nav.dialogState.value = previous.menu
                }
            }
            Page.FINISHED -> nav.dialogState.value = DialogState.None
        }
    }

    private fun menu(title: String, rows: List<Pair<String, () -> Unit>>, previews: List<String?> = emptyList()) {
        actions = rows.map { it.second }
        nav.dialogState.value = DialogState.ScraperMenu(title, rows.map { it.first }, previews = previews, bulk = true)
    }

    private fun configure() {
        page = Page.CONFIG
        history.clear()
        val title = if (systemPlatforms != null) context.getString(R.string.bulk_systems)
        else if (allPlatforms) context.getString(R.string.bulk_all)
        else context.getString(R.string.bulk_platform_games,
            if (platform.equals("PORTS", ignoreCase = true)) context.getString(dev.piannoli.ui.R.string.origin_ports)
            else platforms.getDisplayName(requireNotNull(platform)))
        menu(title, buildList {
            if (!allPlatforms) {
                add("${context.getString(R.string.bulk_subfolders)}\t${context.getString(if (includeSubfolders) R.string.value_on else R.string.value_off)}" to {
                    includeSubfolders = !includeSubfolders
                    configure()
                })
            }
            add("${context.getString(R.string.bulk_mode)}\t${context.getString(if (automatic) R.string.bulk_automatic else R.string.bulk_interactive)}" to {
                automatic = !automatic
                configure()
            })
            add("${context.getString(R.string.bulk_existing_artwork)}\t${context.getString(if (overwriteArtwork) R.string.scraper_overwrite else R.string.scrape_fail_skip)}" to {
                overwriteArtwork = !overwriteArtwork
                configure()
            })
            add(context.getString(R.string.bulk_start) to { start() })
        })
    }

    private fun start() {
        if (!allPlatforms && platform == null) {
            nav.dialogState.value = DialogState.ScraperStatus(context.getString(R.string.bulk_no_platforms), bulk = true)
            return
        }
        key = settings.steamGridApiKey
        if (key.isBlank()) {
            nav.dialogState.value = DialogState.ScraperStatus(context.getString(R.string.steamgrid_key_required), bulk = true)
            return
        }
        queue = emptyList()
        failBehavior = settings.scrapeFailBehavior
        index = 0
        saved = 0
        skipped = 0
        failed = 0
        page = Page.RUNNING
        request {
            val selectedPlatform = platform
            queue = withContext(ioContext) {
                val systems = systemPlatforms
                if (systems != null) {
                    val type = settings.systemArtworkType
                    val available = systems.map { it.tag to it.displayName } +
                        if (apps.all(AppType.PORT).isNotEmpty()) listOf("PORTS" to settings.portsName) else emptyList()
                    return@withContext available.map { (tag, name) ->
                        ScrapeGame.SystemGame(tag, name, type, artwork.systemArtworkFile(tag, type))
                    }
                }
                val tags = if (allPlatforms) roms.platformCounts().keys.sorted() else listOfNotNull(selectedPlatform)
                val romGames = tags.filterNot { it.equals("PORTS", ignoreCase = true) }
                    .flatMap { roms.romsForScraping(it, allPlatforms || includeSubfolders) }
                    .map { ScrapeGame.RomGame(it) }
                val portGames = if (allPlatforms || selectedPlatform.equals("PORTS", ignoreCase = true))
                    apps.all(AppType.PORT).map { ScrapeGame.PortGame(it) } else emptyList()
                romGames + portGames
            }.distinctBy { it.identity }.filter { overwriteArtwork || it.artFile == null }
            coroutineContext.ensureActive()
            advance()
        }
    }

    private suspend fun advance() {
        clearGameHistory()
        while (index < queue.size) {
            coroutineContext.ensureActive()
            val game = queue[index]
            if (game.embeddedArtwork) {
                skipped++
                index++
                continue
            }
            progress(game)
            var matches = withContext(ioContext) { steam.search(key, game.displayName) }
            coroutineContext.ensureActive()
            if (!automatic) {
                val cleaned = ScrapeTitleMatcher.cleanedQuery(game.displayName)
                if (matches.isEmpty() && failBehavior.retry && cleaned.isNotBlank() && cleaned != game.displayName) {
                    matches = withContext(ioContext) { steam.search(key, cleaned) }
                    coroutineContext.ensureActive()
                }
                menu(context.getString(if (systemPlatforms != null) R.string.bulk_system else R.string.bulk_game,
                    index + 1, queue.size, game.displayName),
                    matches.map { match -> "${match.name}\t#${match.id}" to { covers(match) } } + skipRow())
                return
            }
            val cover = automaticCover(game, matches)
            coroutineContext.ensureActive()
            if (cover == null) skipped++ else install(game, cover)
            index++
            delay(500)
        }

        finish(context.getString(R.string.bulk_complete), autoDismiss = true)
    }

    private suspend fun automaticCover(game: ScrapeGame, initial: List<SteamGridGame>): SteamGridCover? {
        val title = game.displayName
        val covers = mutableMapOf<Int, SteamGridCover?>()
        suspend fun coverFor(match: SteamGridGame?): SteamGridCover? {
            if (match == null) return null
            if (match.id !in covers) {
                covers[match.id] = withContext(ioContext) { artworkFor(game, match.id).covers.firstOrNull() }
                coroutineContext.ensureActive()
            }
            return covers[match.id]
        }
        coverFor(exactMatch(title, initial))?.let { return it }
        var candidates = initial
        val cleaned = ScrapeTitleMatcher.cleanedQuery(title)
        if (failBehavior.retry && cleaned.isNotBlank() && cleaned != title) {
            val retried = withContext(ioContext) { steam.search(key, cleaned) }
            coroutineContext.ensureActive()
            coverFor(exactMatch(title, retried))?.let { return it }
            candidates = (initial + retried).distinctBy { it.id }
        }
        return if (failBehavior.bestMatch) coverFor(ScrapeTitleMatcher.closest(title, candidates)) else null
    }

    private fun covers(match: SteamGridGame, coverPage: Int = 0) {
        request {
            val found = withContext(ioContext) { artworkFor(queue[index], match.id, coverPage) }
            coroutineContext.ensureActive()
            val rows = found.covers.map { cover ->
                "${cover.width} x ${cover.height}\t${cover.author}" to {
                    request {
                        install(queue[index], cover)
                        index++
                        clearGameHistory()
                        delay(500)
                        advance()
                    }
                }
            }.toMutableList()
            val previews = mutableListOf<String?>().apply { addAll(found.covers.map { it.thumbnail }) }
            if (found.hasNextPage) {
                rows.add(context.getString(R.string.steamgrid_next_page) to { covers(match, coverPage + 1) })
                previews.add(null)
            }
            if (coverPage > 0) {
                rows.add(context.getString(R.string.steamgrid_previous_page) to { covers(match, coverPage - 1) })
                previews.add(null)
            }
            rows.addAll(skipRow())
            previews.add(null)
            menu("${queue[index].displayName} - ${match.name}", rows, previews)
        }
    }

    private fun skipRow(): List<Pair<String, () -> Unit>> = listOf(
        context.getString(if (systemPlatforms != null) R.string.bulk_skip_system else R.string.bulk_skip_game) to {
            request {
                skipped++
                index++
                advance()
            }
        }
    )

    private fun clearGameHistory() {
        while (history.size > 1) history.removeAt(history.lastIndex)
    }

    private fun artworkFor(game: ScrapeGame, gameId: Int, page: Int = 0): SteamGridCovers =
        if (game is ScrapeGame.SystemGame) steam.systemArtwork(key, gameId, game.type, page)
        else steam.covers(key, gameId, page)

    private suspend fun install(game: ScrapeGame, cover: SteamGridCover) {
        val bytes = withContext(ioContext) { steam.download(cover) }
        coroutineContext.ensureActive()
        withContext(ioContext) {
            coroutineContext.ensureActive()
            try {
                game.install(artwork, bytes, overwriteArtwork)
                saved++
            } catch (e: ScraperException) {
                if (e.error != ScraperError.EXISTING_ARTWORK) throw e
                skipped++
            }
        }
    }

    private fun progress(game: ScrapeGame? = null) {
        nav.dialogState.value = DialogState.ScraperStatus(
            if (page != Page.RUNNING || queue.isEmpty()) context.getString(R.string.bulk_preparing)
            else context.getString(R.string.bulk_progress, index + 1, queue.size, game?.displayName.orEmpty(), saved, skipped),
            busy = true, bulk = true,
        )
    }

    private fun finish(reason: String, autoDismiss: Boolean = false) {
        dismissJob?.cancel()
        page = Page.FINISHED
        if (systemPlatforms == null) games.reload()
        val summary = DialogState.ScraperStatus(
            context.getString(R.string.bulk_summary, reason, saved, skipped, failed, (queue.size - index).coerceAtLeast(0)),
            bulk = true,
        )
        nav.dialogState.value = summary
        if (autoDismiss) {
            dismissJob = scope.launch(Dispatchers.Main) {
                delay(3_000)
                if (nav.dialogState.value === summary) nav.dialogState.value = DialogState.None
            }
        }
    }

    private fun request(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        (nav.dialogState.value as? DialogState.ScraperMenu)?.let { history.add(MenuPage(it, actions)) }
        progress(queue.getOrNull(index))
        job = scope.launch(Dispatchers.Main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScraperException) {
                if (page == Page.RUNNING) {
                    if (index < queue.size) { failed++; index++ }
                    finish(context.getString(R.string.bulk_stopped, context.getString(errorString(e.error))))
                } else {
                    nav.dialogState.value = DialogState.ScraperStatus(context.getString(errorString(e.error)), bulk = true)
                }
            }
        }
    }

    private fun errorString(error: ScraperError): Int = when (error) {
        ScraperError.ACCOUNT_REQUIRED -> R.string.steamgrid_key_required
        ScraperError.AUTHENTICATION -> R.string.steamgrid_authentication
        ScraperError.RATE_LIMIT -> R.string.steamgrid_rate_limit
        ScraperError.NETWORK -> R.string.steamgrid_network
        ScraperError.UNAVAILABLE -> R.string.steamgrid_unavailable
        ScraperError.STORAGE -> R.string.scraper_storage
        ScraperError.EXISTING_ARTWORK -> R.string.scraper_existing_artwork
        ScraperError.NO_ARTWORK -> R.string.scraper_no_artwork
        else -> R.string.scraper_invalid_response
    }

    internal fun exactMatch(title: String, matches: List<SteamGridGame>): SteamGridGame? {
        return ScrapeTitleMatcher.exact(title, matches)
    }
}
