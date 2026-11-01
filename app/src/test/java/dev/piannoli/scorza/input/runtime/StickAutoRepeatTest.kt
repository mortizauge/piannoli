package dev.piannoli.scorza.input.runtime

import android.view.MotionEvent
import android.os.Looper
import dev.piannoli.scorza.input.ListNavigation
import dev.piannoli.scorza.input.ScreenInputHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class StickAutoRepeatTest {

    private lateinit var dispatcher: InputDispatcher
    private lateinit var autoRepeat: StickAutoRepeat

    @Before fun setup() {
        dispatcher = mockk(relaxed = true)
        autoRepeat = StickAutoRepeat(dispatcher)
    }

    private fun motion(stickX: Float = 0f, stickY: Float = 0f): MotionEvent {
        val ev = mockk<MotionEvent>()
        every { ev.getAxisValue(MotionEvent.AXIS_X) } returns stickX
        every { ev.getAxisValue(MotionEvent.AXIS_Y) } returns stickY
        return ev
    }

    @Test fun `analog hold clamps until neutral and a new tilt permits wrapping`() {
        for (delta in listOf(-1, 1)) {
            val realDispatcher = InputDispatcher(PortRouter(), ActiveMappingHolder())
            val repeat = StickAutoRepeat(realDispatcher)
            var index = 1
            var calls = 0
            var feedback = 0
            val screen = object : ScreenInputHandler {
                private fun move() { calls++; index = ListNavigation.move(index, delta, 4) }
                override fun onUp() = move()
                override fun onDown() = move()
            }
            realDispatcher.wireToRegistry(screenResolver = { screen }, onMenuSound = { feedback++ })
            try {
                repeat.handleMotion(motion(stickY = delta * 0.8f))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertEquals(if (delta < 0) 0 else 3, index)
                assertEquals(if (delta < 0) 1 else 2, calls)
                assertEquals(calls, feedback)
                repeat.handleMotion(motion())
                repeat.handleMotion(motion(stickY = delta * 0.8f))
                assertEquals(if (delta < 0) 3 else 0, index)
                assertEquals(if (delta < 0) 2 else 3, calls)
                assertEquals(calls, feedback)
            } finally {
                repeat.stop()
            }
        }
    }

    @Test fun `analog-only stick transition fires immediately when dispatcher did not handle`() {
        autoRepeat.handleMotion(motion(stickY = 0.7f), dispatcherHandled = false)
        verify(exactly = 1) { dispatcher.onDown() }
    }

    @Test fun `digital-bound stick transition does not double-fire when dispatcher already handled it`() {
        autoRepeat.handleMotion(motion(stickY = 0.7f), dispatcherHandled = true)
        verify(exactly = 0) { dispatcher.onDown() }
    }

    @Test fun `dispatcher-handled transition still updates heldDir so next sustained frame does not fire`() {
        autoRepeat.handleMotion(motion(stickY = 0.7f), dispatcherHandled = true)
        autoRepeat.handleMotion(motion(stickY = 0.8f), dispatcherHandled = false)
        verify(exactly = 0) { dispatcher.onDown() }
    }

    @Test fun `release back to neutral after dispatcher-handled hold does not fire`() {
        autoRepeat.handleMotion(motion(stickY = 0.7f), dispatcherHandled = true)
        autoRepeat.handleMotion(motion(stickY = 0f), dispatcherHandled = false)
        verify(exactly = 0) { dispatcher.onDown() }
        verify(exactly = 0) { dispatcher.onUp() }
    }
}
