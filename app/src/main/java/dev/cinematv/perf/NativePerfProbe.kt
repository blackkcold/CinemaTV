package dev.cinematv.perf

/**
 * Inert measurement probe for the native TV performance lane.
 *
 * Production only pays a volatile read. The acceptance lane enables it and reads the
 * applied-state timeline so an input can be paired with the frame that carried the state
 * it caused, instead of "any frame that happens to come later".
 */
object NativePerfProbe {
    data class StateEvent(val seq: Long, val page: String, val focusKey: String?, val appliedNs: Long)

    @Volatile
    var enabled: Boolean = false

    private val lock = Any()
    private val events = ArrayList<StateEvent>(64)
    private var nextSeq = 0L

    fun reset() = synchronized(lock) {
        events.clear()
        nextSeq = 0L
    }

    /** Runs after composition apply; [appliedNs] precedes the frame that carries the state. */
    fun record(page: String, focusKey: String?) {
        if (!enabled) return
        val appliedNs = System.nanoTime()
        synchronized(lock) {
            events += StateEvent(nextSeq++, page, focusKey, appliedNs)
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
    }

    /** First state applied at or after [receivedNs]; null when this input changed nothing yet. */
    fun firstAfter(receivedNs: Long): StateEvent? = synchronized(lock) {
        events.firstOrNull { it.appliedNs >= receivedNs }
    }

    fun snapshot(): List<StateEvent> = synchronized(lock) { events.toList() }

    private const val MAX_EVENTS = 1024
}
