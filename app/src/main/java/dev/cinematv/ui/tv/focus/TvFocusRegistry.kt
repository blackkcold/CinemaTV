package dev.cinematv.ui.tv.focus

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import dev.cinematv.CinemaViewModel
import dev.cinematv.FocusCoordinator
import kotlinx.coroutines.withTimeoutOrNull

/** Only live composition owns requesters. The ViewModel retains logical keys. */
class TvFocusRegistry {
    private val targets = mutableMapOf<Triple<String, String, String>, FocusRequester>()
    private val lazyRegions = mutableMapOf<Pair<String, String>, suspend (String, Int) -> String?>()
    fun attach(route: String, region: String, key: String, requester: FocusRequester) {
        targets[Triple(route, region, key)] = requester
    }
    fun detach(route: String, region: String, key: String) { targets.remove(Triple(route, region, key)) }
    fun request(route: String, region: String, key: String): Boolean =
        targets[Triple(route, region, key)]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
    fun attachLazyRegion(route: String, region: String, prepare: suspend (String, Int) -> String?) {
        lazyRegions[route to region] = prepare
    }
    fun detachLazyRegion(route: String, region: String) { lazyRegions.remove(route to region) }
    internal suspend fun restore(route: String, bookmark: FocusCoordinator.Bookmark?, isCurrent: () -> Boolean,
        beforeRequest: () -> Unit = {}): FocusRestoreResult {
        // Saved scroll offsets belong to the old ordering. Resolve the logical
        // target before requesting focus, including items not yet composed.
        return withTimeoutOrNull(2_000) {
            restoreFocusTarget(bookmark, bookmark?.let { book -> lazyRegions[route to book.region]?.let { prepare -> { key -> prepare(key, book.position) } } },
                awaitFrame = { withFrameNanos { } }, isCurrent = isCurrent,
                request = { region, key -> beforeRequest(); request(route, region, key) })
        } ?: if (isCurrent()) FocusRestoreResult.Missing else FocusRestoreResult.Aborted
    }
}

val LocalTvFocusRegistry = staticCompositionLocalOf { TvFocusRegistry() }
val LocalNavigationFocus = staticCompositionLocalOf { FocusRequester() }
val LocalContentFocus = staticCompositionLocalOf { FocusRequester() }
val LocalNavigationHasFocus = compositionLocalOf { false }
val LocalNavigationFocusOwner = staticCompositionLocalOf<TvNavigationFocusOwner?> { null }
val LocalTvContentFocusOwner = staticCompositionLocalOf<TvNavigationFocusOwner?> { null }
val LocalTvFocusOwnerObserver = staticCompositionLocalOf<((TvNavigationFocusOwner) -> Unit)?> { null }

/** Passive, synchronous focus ownership. It neither consumes keys nor saves bookmarks. */
@Composable
fun Modifier.trackTvContentFocus(): Modifier {
    val owner = LocalTvContentFocusOwner.current ?: return this
    val acquisition = remember(owner) { ContentFocusAcquisition(owner::acquireContentFocus) }
    return onFocusChanged { acquisition.update(it.isFocused) }
}

@Composable
fun rememberFocusTarget(model: CinemaViewModel, region: String, key: String): FocusRequester {
    val requester = remember(region, key) { FocusRequester() }
    val registry = LocalTvFocusRegistry.current
    val route = model.focusRoute
    DisposableEffect(route, region, key) {
        registry.attach(route, region, key, requester)
        onDispose { registry.detach(route, region, key) }
    }
    return requester
}

@Composable
fun RegisterLazyFocusRegion(model: CinemaViewModel, region: String, state: LazyListState, itemKeys: List<String>) {
    val route = model.focusRoute
    val registry = LocalTvFocusRegistry.current
    val currentKeys by rememberUpdatedState(itemKeys)
    DisposableEffect(route, region, state, registry) {
        registry.attachLazyRegion(route, region) { key, fallbackIndex ->
            // A removed title (for example, marked not interested) falls back
            // to its old position inside this rail rather than waiting forever.
            val target = resolveLazyFocusTarget(currentKeys, key, fallbackIndex)
            if (target == null) null else {
                val visible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == target.itemKey }
                if (visible == null || visible.offset < state.layoutInfo.viewportStartOffset ||
                    visible.offset + visible.size > state.layoutInfo.viewportEndOffset) {
                    state.scrollToItem(target.index)
                }
                target.itemKey
            }
        }
        onDispose { registry.detachLazyRegion(route, region) }
    }
}

/** The same key-first / old-position-fallback contract for adaptive poster grids. */
@Composable
fun RegisterLazyFocusGridRegion(model: CinemaViewModel, region: String, state: LazyGridState, itemKeys: List<String>) {
    val route = model.focusRoute
    val registry = LocalTvFocusRegistry.current
    val currentKeys by rememberUpdatedState(itemKeys)
    DisposableEffect(route, region, state, registry) {
        registry.attachLazyRegion(route, region) { key, fallbackIndex ->
            val target = resolveLazyFocusTarget(currentKeys, key, fallbackIndex)
            if (target == null) null else {
                val visible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == target.itemKey }
                if (visible == null || visible.offset.y < state.layoutInfo.viewportStartOffset ||
                    visible.offset.y + visible.size.height > state.layoutInfo.viewportEndOffset) {
                    state.scrollToItem(target.index)
                }
                target.itemKey
            }
        }
        onDispose { registry.detachLazyRegion(route, region) }
    }
}

@Composable
fun RestorePageFocus(model: CinemaViewModel, ready: Boolean = true) {
    val fallback = LocalContentFocus.current
    val registry = LocalTvFocusRegistry.current
    val navigationOwner = LocalNavigationFocusOwner.current
    val navigationHasFocus by rememberUpdatedState(LocalNavigationHasFocus.current)
    val route = model.focusRoute
    val guard = remember(model, route, navigationOwner) {
        FocusRestorationGuard(route, model.focusCoordinator.last(route), navigationOwner,
            navigationOwner?.hasFocus ?: navigationHasFocus)
    }
    LaunchedEffect(model, route, ready, registry, fallback, guard) {
        if (ready) {
            val isCurrent = {
                guard.isCurrent(model.focusRoute, model.focusCoordinator.last(route),
                    navigationOwner?.hasFocus ?: navigationHasFocus)
            }
            // Wait for lazy items and their focus targets to attach after route entry.
            val generation = navigationOwner?.beginRestore(route)
            val beforeRequest = { if (generation != null) navigationOwner?.requestRestore(generation); Unit }
            var outcome = "Cancelled"
            try {
                outcome = restorePageFocusWhenCurrent(awaitFrame = { withFrameNanos { } }, isCurrent = isCurrent,
                    restore = { registry.restore(guard.route, guard.bookmark, isCurrent, beforeRequest) },
                    fallback = { beforeRequest(); runCatching { fallback.requestFocus() }.getOrDefault(false) }).name
            } finally {
                if (generation != null) navigationOwner?.finishRestore(generation, outcome)
            }
        }
    }
}

@Composable
fun rememberTvListState(model: CinemaViewModel, region: String): LazyListState {
    val route = model.focusRoute
    val saved = remember(route, region) { model.focusCoordinator.scroll(route, region) }
    val state = rememberLazyListState(saved.index, saved.offset)
    DisposableEffect(route, region, state) {
        onDispose {
            model.focusCoordinator.saveScroll(route, region, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    }
    return state
}
