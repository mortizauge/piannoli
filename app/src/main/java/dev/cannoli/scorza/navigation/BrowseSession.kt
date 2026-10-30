package dev.cannoli.scorza.navigation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.ErrorLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@Serializable
data class BrowsePosition(val id: String? = null, val index: Int = 0, val scroll: Int = 0)

@Serializable
data class CollectionBrowseParent(val id: Long, val index: Int, val scroll: Int)

@Serializable
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

@Serializable
internal data class BrowseBookmark(
    val root: String,
    val romDirectory: String,
    val system: BrowsePosition,
    val game: GameListBookmark? = null,
)

@ActivityScoped
class BrowseSession @Inject constructor(
    @ApplicationContext context: Context,
    private val paths: CannoliPathsProvider,
    private val nav: NavigationController,
    private val games: GameListViewModel,
    private val systems: SystemListViewModel,
) {
    private val preferences = context.getSharedPreferences("browse_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save() {
        val inGames = LauncherScreen.GameList in nav.screenStack
        if (!inGames && nav.currentScreen != LauncherScreen.SystemList) return
        val bookmark = BrowseBookmark(
            paths.root.absolutePath, paths.romDir.absolutePath, systems.browsePosition(),
            if (inGames) games.bookmark() else null,
        )
        // Commit before Android can reclaim the launcher while the emulator is foregrounded.
        if (!preferences.edit().putString("bookmark", json.encodeToString(bookmark)).commit()) {
            ErrorLog.write("Failed to save browsing position before game launch")
        }
    }

    internal fun pending(): BrowseBookmark? {
        val encoded = preferences.getString("bookmark", null) ?: return null
        val bookmark = try {
            json.decodeFromString<BrowseBookmark>(encoded)
        } catch (e: SerializationException) {
            ErrorLog.error("Invalid saved browsing position", e)
            clear()
            return null
        }
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
        if (!preferences.edit().remove("bookmark").commit()) {
            ErrorLog.write("Failed to clear saved browsing position")
        }
    }
}
