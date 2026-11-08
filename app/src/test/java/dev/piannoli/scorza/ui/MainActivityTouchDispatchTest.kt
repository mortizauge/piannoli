package dev.piannoli.scorza.ui

import android.app.Application
import android.view.MotionEvent
import android.view.Window
import kotlinx.coroutines.flow.MutableStateFlow
import dev.piannoli.scorza.MainActivity
import dev.piannoli.scorza.ui.screens.DialogState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityTouchDispatchTest {
    @Test fun `normal launcher forwards the whole gesture to its window`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.nav = mockk {
            every { dialogState } returns MutableStateFlow<DialogState>(DialogState.None)
        }
        val window = mockk<Window>()
        every { window.superDispatchTouchEvent(any()) } returns true
        ReflectionHelpers.setField(activity, "mWindow", window)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 80f, 160f, 0)
        val move = MotionEvent.obtain(0, 16, MotionEvent.ACTION_MOVE, 80f, 100f, 0)
        val up = MotionEvent.obtain(0, 32, MotionEvent.ACTION_UP, 80f, 100f, 0)
        try {
            activity.dispatchTouchEvent(down)
            activity.dispatchTouchEvent(move)
            activity.dispatchTouchEvent(up)
            verifySequence {
                window.superDispatchTouchEvent(down)
                window.superDispatchTouchEvent(move)
                window.superDispatchTouchEvent(up)
            }
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
        }
    }
}
