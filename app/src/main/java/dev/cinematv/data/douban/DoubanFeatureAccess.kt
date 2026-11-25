package dev.cinematv.data.douban

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A generation is an authority token, not merely a snapshot of the developer toggle. */
class DoubanFeatureAccess(channel: String, initiallyEnabled: Boolean = false) {
    private val dev = channel == "dev"
    private val lock = Any()
    private var generation = 0L
    private val mutable = MutableStateFlow(dev && initiallyEnabled)
    val enabled: StateFlow<Boolean> = mutable.asStateFlow()
    val isEnabled: Boolean get() = synchronized(lock) { mutable.value }
    private val listeners = linkedSetOf<() -> Unit>()

    fun enable() = synchronized(lock) { if (dev) mutable.value = true }
    fun revoke() {
        val callbacks = synchronized(lock) {
            generation++
            mutable.value = false
            listeners.toList()
        }
        // Network cancellation must never run while holding the persistence/publication barrier.
        callbacks.forEach { runCatching { it() } }
    }
    fun capture(): Long = synchronized(lock) { requireEnabled(); generation }
    fun check(ticket: Long) = synchronized(lock) { requireEnabled(); if (ticket != generation) throw DoubanFeatureDisabledException() }
    fun <T> guard(ticket: Long, block: () -> T): T = synchronized(lock) { check(ticket); block() }
    fun onRevoked(listener: () -> Unit): AutoCloseable {
        synchronized(lock) { listeners += listener }
        return AutoCloseable { synchronized(lock) { listeners -= listener } }
    }
    private fun requireEnabled() { if (!mutable.value) throw DoubanFeatureDisabledException() }
    companion object { val DENIED = DoubanFeatureAccess("stable") }
}

class DoubanFeatureDisabledException : CancellationException("Douban developer experiment is disabled or its operation was revoked")
