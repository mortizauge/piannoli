package dev.piannoli.ui.components

import android.os.Handler
import android.os.Looper

/** Repeats only a deletion that succeeds; callers own release and lifecycle cancellation. */
class DeleteKeyRepeat(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var pending: Runnable? = null

    fun start(delete: () -> Boolean) {
        stop()
        val repeat = object : Runnable {
            override fun run() {
                if (pending !== this) return
                if (delete()) {
                    if (pending === this) handler.postDelayed(this, INTERVAL_MS)
                } else {
                    stop()
                }
            }
        }
        pending = repeat
        if (delete()) {
            if (pending === repeat) handler.postDelayed(repeat, DELAY_MS)
        } else {
            stop()
        }
    }

    fun stop() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    companion object {
        const val DELAY_MS = 400L
        const val INTERVAL_MS = 60L
    }
}
