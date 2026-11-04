package dev.cinematv.ui.tv.focus

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import dev.cinematv.CinemaViewModel
import kotlinx.coroutines.withTimeoutOrNull

/** Only live composition owns requesters. The ViewModel retains logical keys. */
class TvFocusRegistry {
    private val targets = mutableMapOf<Triple<String, String, String>, FocusRequester>()
    private val lazyRegions = mutableMapOf<Pair<String, String>, suspend (String) -> String?>()
    fun attach(route: String, region: String, key: String, requester: FocusRequester) {
        targets[Triple(route, region, key)] = requester
    }
    fun detach(route: String, region: String, key: String) { targets.remove(Triple(route, region, key)) }
    fun request(route: String, region: String, key: String): Boolean =
        targets[Triple(route, region, key)]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
    fun attachLazyRegion(route: String, region: String, prepare: suspend (String) -> String?) {
        lazyRegions[route to region] = prepare
    }
    fun detachLazyRegion(route: String, region: String) { lazyRegions.remove(route to region) }
    suspend fun restore(model: CinemaViewModel): Boolean {
        val route = model.focusRoute
        val last = model.focusCoordinator.last(route) ?: return false
        var itemKey = last.itemKey
        // Saved scroll offsets belong to the old ordering. Resolve the logical
        // target before requesting focus, including items not yet composed.
        lazyRegions[route to last.region]?.let { prepare ->
            itemKey = withTimeoutOrNull(2_000) {
                val preparedKey = prepare(itemKey) ?: return@withTimeoutOrNull null
                withFrameNanos { }; withFrameNanos { }
                preparedKey
            } ?: return false
        }
        return request(route, last.region, itemKey)
    }
}

val LocalTvFocusRegistry = staticCompositionLocalOf { TvFocusRegistry() }
val LocalNavigationFocus = staticCompositionLocalOf { FocusRequester() }
val LocalContentFocus = staticCompositionLocalOf { FocusRequester() }
val LocalNavigationHasFocus = compositionLocalOf { false }

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
        registry.attachLazyRegion(route, region) { key ->
            // A removed title (for example, marked not interested) falls back
            // inside this rail rather than waiting for an impossible target.
            val target = resolveLazyFocusTarget(currentKeys, key)
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

@Composable
fun RestorePageFocus(model: CinemaViewModel, ready: Boolean = true) {
    val fallback = LocalContentFocus.current
    val registry = LocalTvFocusRegistry.current
    val navigationHasFocus = LocalNavigationHasFocus.current
    val navigationAtEntry = remember(model.focusRoute) { navigationHasFocus }
    LaunchedEffect(model.page, ready) {
        if (ready && !navigationAtEntry) {
            // Wait for lazy items and their focus targets to attach after route entry.
            withFrameNanos { }; withFrameNanos { }
            if (!registry.restore(model)) runCatching { fallback.requestFocus() }
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
