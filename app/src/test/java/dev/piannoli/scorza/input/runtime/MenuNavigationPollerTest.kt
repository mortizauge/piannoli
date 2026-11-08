package dev.piannoli.scorza.input.runtime

import android.os.Looper
import android.view.KeyEvent
import dev.piannoli.scorza.input.CanonicalButton
import dev.piannoli.scorza.input.ListNavigation
import dev.piannoli.scorza.input.ScreenInputHandler
import dev.piannoli.scorza.navigation.NavigationController
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MenuNavigationPollerTest {
    @Test fun `poller repeats clamp in both directions independently of stale key repeat count`() {
        for ((direction, delta) in listOf(CanonicalButton.BTN_UP to -1, CanonicalButton.BTN_DOWN to 1)) {
            val router = mockk<PortRouter>()
            var held = true
            every { router.isCanonicalPressedAt(any(), any()) } answers {
                held && firstArg<Int>() == 0 && secondArg<CanonicalButton>() == direction
            }
            val dispatcher = InputDispatcher(router, ActiveMappingHolder())
            var index = 1
            var calls = 0
            var feedback = 0
            val screen = object : ScreenInputHandler {
                private fun move() { calls++; index = ListNavigation.move(index, delta, 4) }
                override fun onUp() = move()
                override fun onDown() = move()
            }
            dispatcher.wireToRegistry(screenResolver = { screen }, onMenuSound = { feedback++ })
            val move = if (delta < 0) dispatcher.onUp else dispatcher.onDown
            val nav = NavigationController()
            nav.lastKeyRepeatCount = 0
            val poller = MenuNavigationPoller(router, dispatcher)
            try {
                move()
                poller.start()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                val boundary = if (delta < 0) 0 else 3
                assertEquals(boundary, index)
                assertEquals(if (delta < 0) 1 else 2, calls)
                assertEquals(calls, feedback)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertEquals(if (delta < 0) 1 else 2, calls)
                assertEquals(calls, feedback)
                held = false
                poller.wakeOnInput()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
                nav.lastKeyRepeatCount = 99
                held = true
                move()
                assertEquals(if (delta < 0) 3 else 0, index)
                assertEquals(if (delta < 0) 2 else 3, calls)
                assertEquals(calls, feedback)
            } finally {
                poller.stop()
            }
        }
    }

    @Test fun `idle and stopped navigation schedule no polling and input wakes repeat immediately`() {
        val router = mockk<PortRouter>()
        every { router.mappingFor(any()) } returns null
        every { router.evaluatorFor(any()) } returns null
        var held = false
        var reads = 0
        every { router.isCanonicalPressedAt(any(), any()) } answers {
            reads++
            held && firstArg<Int>() == 0 && secondArg<CanonicalButton>() == CanonicalButton.BTN_DOWN
        }
        val dispatcher = InputDispatcher(router, ActiveMappingHolder())
        var repeats = 0
        dispatcher.onDown = { repeats++ }
        val poller = MenuNavigationPoller(router, dispatcher)
        try {
            poller.start()
            shadowOf(Looper.getMainLooper()).idle()
            val idleReads = reads
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(idleReads, reads)
            held = true
            dispatcher.handleKeyboardDirectionForTest(999, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(279))
            assertEquals(1, repeats)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(2, repeats)
            held = false
            dispatcher.handleKeyboardDirectionForTest(999, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
            shadowOf(Looper.getMainLooper()).idle()
            val releasedReads = reads
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(releasedReads, reads)
            assertEquals(2, repeats)
            poller.stop()
            assertEquals(null, dispatcher.onNavigationStateChanged)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(releasedReads, reads)
        } finally {
            poller.stop()
        }
    }
}
