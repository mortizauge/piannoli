package dev.piannoli.scorza.scanner

/**
 * Keep event-driven watchers armed while the launcher is away, but coalesce their work until
 * it is interactive again. Explicit scans do not pass through this gate.
 */
internal class DeferredScanRequests(private val enqueue: (String) -> Unit) {
    private var active = false
    private val pending = linkedSetOf<String>()

    @Synchronized
    fun setActive(active: Boolean) {
        this.active = active
        if (active) {
            pending.forEach(enqueue)
            pending.clear()
        }
    }

    @Synchronized
    fun request(tag: String) {
        if (active) enqueue(tag) else pending.add(tag)
    }

    @Synchronized
    fun clear() = pending.clear()
}
