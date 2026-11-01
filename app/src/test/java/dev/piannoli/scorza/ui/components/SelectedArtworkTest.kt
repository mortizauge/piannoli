package dev.piannoli.scorza.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.piannoli.scorza.settings.ArtScale
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectedArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val workerScope = CoroutineScope(SupervisorJob())
    private val decoded: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val cache = ListArtworkCache(
        decoder = { request: ArtworkRequest -> decoded += request.path; request.path },
        sizeOf = String::length,
        scope = workerScope,
        dispatcher = Dispatchers.IO,
    )
    private val paths = listOf("a", "b", "c")
    private val active = MutableStateFlow(true)

    @After fun cancelWorker() = workerScope.cancel()

    private fun window(selected: Int, revision: Long = 0L) = artworkWindow(paths.size, selected) {
        ArtworkRequest(paths[it], revision, 100, 100, ArtScale.FIT)
    }

    private fun awaitText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun `prefetched neighbour shows on first composition and selection changes keep the list cache`() {
        val selected = mutableIntStateOf(0)
        val displayed = mutableListOf<String?>()
        compose.setContent {
            val art = rememberListArtwork("list", window(selected.intValue), active, cache)
            SideEffect { displayed.add(art) }
            Text(art ?: "placeholder")
        }
        awaitText("a")
        compose.waitUntil(5_000) { decoded.size == 3 }
        compose.runOnIdle { displayed.clear(); selected.intValue = 2 }
        compose.onNodeWithText("c").assertExists()
        compose.runOnIdle {
            assertTrue(displayed.isNotEmpty())
            assertTrue(displayed.all { it == "c" })
        }
        assertEquals(listOf("a", "b", "c"), decoded.toList())
    }

    @Test fun `changing list identity or leaving the list drops its artwork`() {
        val list = mutableStateOf("first")
        val shown = mutableStateOf(true)
        compose.setContent {
            if (shown.value) Text(rememberListArtwork(list.value, window(0), active, cache) ?: "placeholder")
        }
        awaitText("a")
        compose.waitUntil(5_000) { decoded.size == 3 }
        compose.runOnIdle { list.value = "second" }
        compose.waitForIdle()
        compose.waitUntil(5_000) { decoded.size == 6 }
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle { assertNull(cache.peek(window(0).selected!!)) }
    }

    @Test fun `hidden artwork and paused UI start no decode`() {
        val list = mutableStateOf<String?>(null)
        compose.setContent { Text(rememberListArtwork(list.value, window(1), active, cache) ?: "placeholder") }
        compose.onNodeWithText("placeholder").assertExists()
        compose.runOnIdle { active.value = false; list.value = "list" }
        compose.onNodeWithText("placeholder").assertExists()
        assertEquals(0, decoded.size)
        compose.runOnIdle { active.value = true }
        awaitText("b")
        compose.runOnIdle { list.value = null }
        compose.onNodeWithText("placeholder").assertExists()
    }
}