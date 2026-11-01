package dev.piannoli.scorza.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.After
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

    @After fun cancelWorker() = workerScope.cancel()

    @Test fun `cached selection replaces previous art on first composition without placeholder`() {
        val path = mutableStateOf("first")
        val active = MutableStateFlow(true)
        val decoded = AtomicInteger()
        val displayed = mutableListOf<String?>()
        val loader = SelectionArtworkLoader(
            decoder = { value: String -> decoded.incrementAndGet(); value },
            sizeOf = String::length,
            scope = workerScope,
            dispatcher = Dispatchers.IO,
        )
        compose.setContent {
            val art by rememberSelectedArtwork(path.value, 0L, true, active, loader)
            SideEffect { displayed.add(art) }
            Text(art ?: "placeholder")
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("first").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { path.value = "second" }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("second").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { displayed.clear(); path.value = "first" }
        compose.onNodeWithText("first").assertExists()
        compose.runOnIdle {
            assertTrue(displayed.isNotEmpty())
            assertTrue(displayed.all { it == "first" })
            active.value = false
            path.value = "second"
        }
        compose.onNodeWithText("placeholder").assertExists()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithText("second").assertExists()
        assertEquals(2, decoded.get())
    }

    @Test fun `selection and revision changes clear old art even while inactive`() {
        val path = mutableStateOf<String?>("first")
        val revision = mutableStateOf(0L)
        val active = MutableStateFlow(true)
        val decoded = AtomicInteger()
        val loader = SelectionArtworkLoader(
            decoder = { value: String -> "$value-${decoded.incrementAndGet()}" },
            sizeOf = String::length,
            scope = workerScope,
            dispatcher = Dispatchers.IO,
        )
        compose.setContent {
            val art by rememberSelectedArtwork(path.value, revision.value, true, active, loader)
            Text(art ?: "placeholder")
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("first-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { active.value = false; path.value = "second" }
        compose.onNodeWithText("placeholder").assertExists()
        compose.onNodeWithText("first-1").assertDoesNotExist()
        compose.runOnIdle { active.value = true }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("second-2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { active.value = false; revision.value++ }
        compose.onNodeWithText("placeholder").assertExists()
        compose.runOnIdle { active.value = true }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("second-3").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { path.value = null }
        compose.onNodeWithText("placeholder").assertExists()
        assertEquals(3, decoded.get())
    }

    @Test fun `hidden artwork does not load and disabling clears loaded image`() {
        val enabled = mutableStateOf(false)
        val active = MutableStateFlow(true)
        val decoded = AtomicInteger()
        val loader = SelectionArtworkLoader(
            decoder = { value: String -> decoded.incrementAndGet(); value },
            sizeOf = String::length,
            scope = workerScope,
            dispatcher = Dispatchers.IO,
        )
        compose.setContent {
            val art by rememberSelectedArtwork("art", 0L, enabled.value, active, loader)
            Text(art ?: "placeholder")
        }
        compose.onNodeWithText("placeholder").assertExists()
        assertEquals(0, decoded.get())
        compose.runOnIdle { enabled.value = true }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("art").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("placeholder").assertExists()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("art").assertExists()
        assertEquals(1, decoded.get())
    }
}
