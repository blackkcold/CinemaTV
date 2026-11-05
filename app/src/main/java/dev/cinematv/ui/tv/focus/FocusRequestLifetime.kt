package dev.cinematv.ui.tv.focus

/** UI-thread ownership of requests which have already entered one focus target. */
internal class FocusRequestLifetime {
    private var focused = false
    private val requests = linkedMapOf<Any, () -> Unit>()

    fun register(cancel: () -> Unit): Any = Any().also { requests[it] = cancel }

    fun unregister(token: Any) { requests.remove(token) }

    fun updateFocus(isFocused: Boolean) {
        val lostFocus = focused && !isFocused
        focused = isFocused
        if (lostFocus) cancelPending()
    }

    fun reset() {
        focused = false
        cancelPending()
    }

    private fun cancelPending() {
        // A cancellation can synchronously unregister itself. Clear the old
        // collection first so it cannot remove a newer request from a callback.
        val previous = requests.values.toList()
        requests.clear()
        previous.forEach { it() }
    }
}
