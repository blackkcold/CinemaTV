package dev.cinematv.ui.tv.focus

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class RailFocusMove(
    val generation: Long,
    val targetKey: String,
    val anchorKey: String,
    val itemKeys: List<String>,
)

/** Queue logical keys, never positions whose meaning can change during a scroll. */
internal class RailFocusTraversal(initialKey: String) {
    private var requestedKey = initialKey
    private var generation = 0L
    private var orderedKeys: List<String>? = null
    var requestingFocus = false
        private set

    fun move(itemKeys: List<String>, activeKey: String, direction: Int): RailFocusMove? {
        if (itemKeys.isEmpty()) return null
        if (orderedKeys != null && orderedKeys != itemKeys) cancel(activeKey)
        orderedKeys = itemKeys.toList()
        val active = itemKeys.indexOf(activeKey).coerceAtLeast(0)
        val requested = itemKeys.indexOf(requestedKey).takeIf { it >= 0 } ?: active
        val next = (requested + direction).coerceIn(itemKeys.indices)
        if (next == requested) return null
        requestedKey = itemKeys[next]
        val anchor = if (next > active) (next - 2).coerceAtLeast(0) else next
        return RailFocusMove(++generation, requestedKey, itemKeys[anchor], itemKeys.toList())
    }

    fun isCurrent(move: RailFocusMove, itemKeys: List<String>): Boolean =
        move.generation == generation && move.itemKeys == itemKeys

    fun cancel(activeKey: String) { generation++; requestedKey = activeKey }

    fun finish(move: RailFocusMove, activeKey: String) {
        if (move.generation == generation) requestedKey = activeKey
    }

    fun requestFocus(request: () -> Boolean): Boolean {
        requestingFocus = true
        return try { request() } finally { requestingFocus = false }
    }
}

/** Try before waiting: already composed targets must not depend on a rendered frame. */
internal suspend fun awaitRailFocusTarget(
    isCurrent: () -> Boolean,
    request: () -> Boolean,
    awaitFrame: suspend () -> Unit,
): Boolean {
    while (true) {
        coroutineContext.ensureActive()
        if (!isCurrent()) return false
        if (request()) return true
        awaitFrame()
    }
}
