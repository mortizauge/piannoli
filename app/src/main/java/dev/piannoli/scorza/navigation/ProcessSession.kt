package dev.piannoli.scorza.navigation

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessSession @Inject constructor() {
    internal val initializationMutex = Mutex()
    @Volatile
    private var initializedLibrary: Pair<String, String>? = null
    @Volatile
    internal var bookmark: BrowseBookmark? = null

    fun isInitialized(root: String, romDirectory: String): Boolean =
        initializedLibrary == (root to romDirectory)

    internal fun initialized(root: String, romDirectory: String) {
        initializedLibrary = root to romDirectory
    }
}
