package dev.piannoli.scorza.navigation

import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.ui.viewmodel.GameListViewModel
import dev.piannoli.scorza.ui.viewmodel.SystemListViewModel
import javax.inject.Inject

data class BrowsePosition(val id: String? = null, val index: Int = 0, val scroll: Int = 0)

data class CollectionBrowseParent(val id: Long, val index: Int, val scroll: Int)

data class GameListBookmark(
    val platformTag: String = "",
    val platformTags: List<String> = emptyList(),
    val breadcrumb: String = "",
    val collectionId: Long? = null,
    val collectionsList: Boolean = false,
    val globalSearch: String? = null,
    val search: String? = null,
    val position: BrowsePosition = BrowsePosition(),
    val folders: List<String> = emptyList(),
    val folderPositions: List<BrowsePosition> = emptyList(),
    val collectionParents: List<CollectionBrowseParent> = emptyList(),
    val collectionsPosition: BrowsePosition = BrowsePosition(),
    val collectionsItemCount: Int = 0,
)

internal data class BrowseBookmark(
    val root: String,
    val romDirectory: String,
    val system: BrowsePosition,
    val game: GameListBookmark? = null,
)

@ActivityScoped
class BrowseSession @Inject constructor(
    private val processSession: ProcessSession,
    private val paths: PiannoliPathsProvider,
    private val nav: NavigationController,
    private val games: GameListViewModel,
    private val systems: SystemListViewModel,
) {
    fun save() {
        val inGames = LauncherScreen.GameList in nav.screenStack
        if (!inGames && nav.currentScreen != LauncherScreen.SystemList) return
        processSession.bookmark = BrowseBookmark(
            paths.root.absolutePath, paths.romDir.absolutePath, systems.browsePosition(),
            if (inGames) games.bookmark() else null,
        )
    }

    internal fun pending(): BrowseBookmark? {
        val bookmark = processSession.bookmark ?: return null
        if (bookmark.root != paths.root.absolutePath || bookmark.romDirectory != paths.romDir.absolutePath) {
            clear()
            return null
        }
        return bookmark
    }

    internal fun restore(bookmark: BrowseBookmark, onReady: () -> Unit) {
        systems.restoreBrowsePosition(bookmark.system)
        nav.screenStack.clear()
        nav.push(LauncherScreen.SystemList)
        val game = bookmark.game
        if (game == null) {
            clear()
            onReady()
        } else {
            games.restoreBookmark(game) {
                nav.push(LauncherScreen.GameList)
                clear()
                onReady()
            }
        }
    }

    fun clear() {
        processSession.bookmark = null
    }
}
