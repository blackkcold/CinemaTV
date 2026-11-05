package dev.cinematv.ui.tv.focus

import dev.cinematv.FocusCoordinator
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class TvFocusSnapshot(
    val navigationHasFocus: Boolean,
    val navigationAcquisition: Long,
    val restoreRoute: String?,
    val restoreState: String,
    val restoreGeneration: Long,
    val restoreStartedAtNanos: Long?,
    val restoreRequestedAtNanos: Long?,
    val restoreFinishedAtNanos: Long?,
)

/** Updated by the root's focus callback, without waiting for recomposition. */
class TvNavigationFocusOwner(private val isAvailable: () -> Boolean = { true }) {
    private var focused = false
    internal var acquisition: Long = 0
        private set
    val available: Boolean get() = isAvailable()
    val hasFocus: Boolean get() = available && focused
    private var restoreGeneration = 0L
    private var restoreRoute: String? = null
    private var restoreState = "Idle"
    private var restoreStartedAtNanos: Long? = null
    private var restoreRequestedAtNanos: Long? = null
    private var restoreFinishedAtNanos: Long? = null

    fun update(hasFocus: Boolean) {
        if (hasFocus && !focused) acquisition++
        focused = hasFocus
    }

    /** Passive UI-thread diagnostics. No snapshot state or per-frame callbacks. */
    fun snapshot() = TvFocusSnapshot(hasFocus, acquisition, restoreRoute, restoreState,
        restoreGeneration, restoreStartedAtNanos, restoreRequestedAtNanos, restoreFinishedAtNanos)

    internal fun beginRestore(route: String): Long {
        restoreGeneration++
        restoreRoute = route
        restoreState = "Pending"
        restoreStartedAtNanos = System.nanoTime()
        restoreRequestedAtNanos = null
        restoreFinishedAtNanos = null
        return restoreGeneration
    }

    internal fun requestRestore(generation: Long) {
        if (generation == restoreGeneration) restoreRequestedAtNanos = System.nanoTime()
    }

    internal fun finishRestore(generation: Long, result: String) {
        if (generation == restoreGeneration) {
            restoreState = result
            restoreFinishedAtNanos = System.nanoTime()
        }
    }
}

/** An entry restoration loses ownership as soon as the user chooses a target. */
internal class FocusRestorationGuard(
    val route: String,
    val bookmark: FocusCoordinator.Bookmark?,
    private val navigation: TvNavigationFocusOwner?,
    private val navigationAtEntry: Boolean,
) {
    private val navigationAcquisition = navigation?.acquisition

    fun isCurrent(route: String, bookmark: FocusCoordinator.Bookmark?, navigationHasFocus: Boolean): Boolean =
        !navigationAtEntry && !navigationHasFocus && this.route == route &&
            // remember() creates a new bookmark even when the user returns to
            // the same item. Value equality would miss that newer focus choice.
            this.bookmark === bookmark && navigation?.acquisition == navigationAcquisition
}

internal enum class FocusRestoreResult { Restored, Missing, Aborted, Fallback }

private suspend fun restorationIsCurrent(isCurrent: () -> Boolean): Boolean {
    coroutineContext.ensureActive()
    return isCurrent()
}

/** Both frame waits and lazy preparation yield to native input. Recheck each one. */
internal suspend fun restoreFocusTarget(
    bookmark: FocusCoordinator.Bookmark?,
    prepare: (suspend (String) -> String?)?,
    awaitFrame: suspend () -> Unit,
    isCurrent: () -> Boolean,
    request: (region: String, itemKey: String) -> Boolean,
): FocusRestoreResult {
    if (!restorationIsCurrent(isCurrent)) return FocusRestoreResult.Aborted
    val target = bookmark ?: return FocusRestoreResult.Missing
    var itemKey = target.itemKey
    if (prepare != null) {
        val preparedKey = prepare(itemKey)
        if (!restorationIsCurrent(isCurrent)) return FocusRestoreResult.Aborted
        itemKey = preparedKey ?: return FocusRestoreResult.Missing
        repeat(2) {
            awaitFrame()
            if (!restorationIsCurrent(isCurrent)) return FocusRestoreResult.Aborted
        }
    }
    if (!restorationIsCurrent(isCurrent)) return FocusRestoreResult.Aborted
    return if (request(target.region, itemKey)) FocusRestoreResult.Restored else FocusRestoreResult.Missing
}

internal suspend fun restorePageFocusWhenCurrent(
    awaitFrame: suspend () -> Unit,
    isCurrent: () -> Boolean,
    restore: suspend () -> FocusRestoreResult,
    fallback: () -> Boolean,
): FocusRestoreResult {
    if (!restorationIsCurrent(isCurrent)) return FocusRestoreResult.Aborted
    repeat(2) {
        awaitFrame()
        if (!restorationIsCurrent(isCurrent)) return FocusRestoreResult.Aborted
    }
    // Aborting is not a missing target. In particular, never move from nav to
    // the hero fallback after a lazy restore was interrupted or timed out.
    val result = restore()
    if (result != FocusRestoreResult.Missing) return result
    if (!restorationIsCurrent(isCurrent)) return FocusRestoreResult.Aborted
    return if (fallback()) FocusRestoreResult.Fallback else FocusRestoreResult.Missing
}
