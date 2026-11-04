package dev.cinematv.ui.tv.focus

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import dev.cinematv.CinemaViewModel

/** Only live composition owns requesters. The ViewModel retains logical keys. */
class TvFocusRegistry {
    private val targets = mutableMapOf<Triple<String, String, String>, FocusRequester>()
    fun attach(route: String, region: String, key: String, requester: FocusRequester) {
        targets[Triple(route, region, key)] = requester
    }
    fun detach(route: String, region: String, key: String) { targets.remove(Triple(route, region, key)) }
    fun restore(model: CinemaViewModel): Boolean {
        val last = model.focusCoordinator.last(model.focusRoute) ?: return false
        val target = targets[Triple(model.focusRoute, last.region, last.itemKey)] ?: return false
        return runCatching { target.requestFocus() }.getOrDefault(false)
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
fun RestorePageFocus(model: CinemaViewModel, ready: Boolean = true) {
    val fallback = LocalContentFocus.current
    val registry = LocalTvFocusRegistry.current
    val navigationHasFocus = LocalNavigationHasFocus.current
    LaunchedEffect(model.page, ready) {
        if (ready && !navigationHasFocus) {
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
