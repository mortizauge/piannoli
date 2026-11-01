package dev.piannoli.scorza.input

/**
 * Repeat provenance is scoped to synchronous input dispatch, not the last Android key event:
 * controller pollers can repeat without receiving any further key events.
 */
object ListNavigation {
    private val repeating = ThreadLocal.withInitial { false }
    private val movement = ThreadLocal<Movement?>()

    val isRepeat: Boolean get() = repeating.get()

    class Movement {
        var usedList = false
            internal set
        var moved = false
            internal set
        var atBoundary = false
            internal set
    }

    fun observe(action: () -> Unit): Movement {
        val previous = movement.get()
        val result = Movement()
        movement.set(result)
        return try {
            action()
            result
        } finally {
            movement.set(previous)
        }
    }

    fun <T> withRepeat(isRepeat: Boolean, action: () -> T): T {
        val previous = repeating.get()
        repeating.set(isRepeat)
        return try {
            action()
        } finally {
            repeating.set(previous)
        }
    }

    fun move(index: Int, delta: Int, count: Int): Int {
        if (count <= 0) return recordMove(index, 0, delta, count)
        val target = index.toLong() + delta
        val next = if (!repeating.get() && (delta == -1 || delta == 1)) {
            target.mod(count.toLong()).toInt()
        } else {
            target.coerceIn(0L, count.toLong() - 1).toInt()
        }
        return recordMove(index, next, delta, count)
    }

    fun recordMove(index: Int, next: Int, delta: Int, count: Int): Int {
        movement.get()?.apply {
            usedList = true
            moved = moved || index != next
            atBoundary = delta != 0 && (count <= 0 ||
                if (delta < 0) next <= 0 else next >= count - 1)
        }
        return next
    }
}
