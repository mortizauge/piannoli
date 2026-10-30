package dev.cannoli.scorza.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.components.LocalUiActive
import dev.cannoli.ui.components.MarqueeEffect
import dev.cannoli.ui.components.StatusBar
import dev.cannoli.ui.components.collectAsStateWhileActive
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiActivityTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun `background presentation unsubscribes and catches up without losing composition`() {
        val active = MutableStateFlow(true)
        val source = MutableStateFlow("before")
        compose.setContent {
            CompositionLocalProvider(LocalUiActive provides active) {
                val value by source.collectAsStateWhileActive()
                Text(value)
            }
        }
        compose.onNodeWithText("before").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { assertEquals(1, source.subscriptionCount.value); active.value = false }
        compose.runOnIdle { assertEquals(0, source.subscriptionCount.value); source.value = "after" }
        compose.onNodeWithText("before").assertExists()
        compose.runOnIdle { active.value = true }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("after").assertExists()
        compose.runOnIdle { assertEquals(1, source.subscriptionCount.value) }
    }

    @Test fun `marquee cancels on focus loss and resumes on return`() {
        val active = MutableStateFlow(false)
        lateinit var scroll: ScrollState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalUiActive provides active) {
                scroll = rememberScrollState()
                Box(Modifier.size(80.dp, 20.dp).horizontalScroll(scroll)) {
                    Spacer(Modifier.width(500.dp))
                }
                MarqueeEffect(scroll, active = true, initialDelayMs = 0)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("overflow=${scroll.maxValue}", scroll.maxValue > 0) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(0, scroll.value); active.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertTrue("scroll=${scroll.value}, max=${scroll.maxValue}", scroll.value > 0); active.value = false }
        compose.mainClock.advanceTimeByFrame()
        val pausedAt = compose.runOnIdle { scroll.value }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(pausedAt, scroll.value); active.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertTrue(scroll.value > 0) }
    }

    @Test fun `status observers are released on focus loss and registered again on return`() {
        val active = MutableStateFlow(false)
        val network = mockk<ConnectivityManager>(relaxed = true)
        val receivers = mutableSetOf<BroadcastReceiver>()
        val context = object : ContextWrapper(compose.activity) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) network else super.getSystemService(name)

            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
                if (receiver != null) receivers.add(receiver)
                return null
            }

            override fun unregisterReceiver(receiver: BroadcastReceiver) {
                receivers.remove(receiver)
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUiActive provides active, LocalContext provides context) {
                StatusBar()
            }
        }
        compose.runOnIdle { assertTrue(receivers.isEmpty()); active.value = true }
        compose.runOnIdle { assertEquals(2, receivers.size) }
        verify(exactly = 1) { network.registerDefaultNetworkCallback(any()) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { active.value = false }
        compose.runOnIdle { assertTrue(receivers.isEmpty()) }
        verify(exactly = 1) { network.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        compose.runOnIdle { active.value = true }
        compose.runOnIdle { assertEquals(2, receivers.size) }
        verify(exactly = 2) { network.registerDefaultNetworkCallback(any()) }
    }
}
