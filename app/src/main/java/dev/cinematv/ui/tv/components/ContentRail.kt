package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun ContentRail(title: String, items: List<MediaItem>, model: CinemaViewModel, portrait: Boolean = false, continueWatching: Boolean = false, entryRequester: FocusRequester? = null) {
    if (items.isEmpty()) return
    val layout = LocalTvLayout.current
    val region = "rail:$title"
    val state = rememberTvListState(model, region)
    val scope = rememberCoroutineScope()
    val registry = LocalTvFocusRegistry.current
    val effects = LocalTvEffects.current
    val route = model.focusRoute
    val focusOwner = LocalNavigationFocusOwner.current
    val navigationHasFocus by rememberUpdatedState(LocalNavigationHasFocus.current)
    val saved = remember { model.focusCoordinator.restore(model.focusRoute, region) }
    val itemKeys = items.map { it.presentationKey }
    val currentKeys by rememberUpdatedState(itemKeys)
    RegisterLazyFocusRegion(model, region, state, itemKeys)
    var activeKey by remember { mutableStateOf(saved ?: itemKeys.first()) }
    val traversal = remember(route, region) { RailFocusTraversal(activeKey) }
    val active = itemKeys.indexOf(activeKey).coerceAtLeast(0)
    var movement by remember { mutableStateOf<Job?>(null) }
    fun cancelMovement() {
        traversal.cancel(activeKey)
        movement?.cancel()
    }
    LaunchedEffect(route, itemKeys) {
        // A recommendation refresh must not finish a queued move to an index
        // that now represents a different movie.
        cancelMovement()
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp)) {
        Text(title, color = TvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = layout.gutter, bottom = 4.dp))
        LazyRow(state = state, modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(items.size, key = { items[it].presentationKey }) { index ->
                val item = items[index]
                val target = rememberFocusTarget(model, region, item.presentationKey)
                FocusMediaCard(item, onFocus = {
                    activeKey = item.presentationKey
                    if (!traversal.requestingFocus) cancelMovement()
                    model.onMediaFocus(item, region)
                }, onClick = { model.open(item) },
                    portrait = portrait, continueWatching = continueWatching,
                    modifier = Modifier.focusRequester(target)
                        .then(if (entryRequester != null && index == active) Modifier.focusRequester(entryRequester) else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false
                            else if (event.key !in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                                // Vertical navigation, activation and Back retain their
                                // existing owners, but invalidate an unfinished move.
                                cancelMovement()
                                false
                            }
                            else {
                                val move = traversal.move(currentKeys, activeKey,
                                    if (event.key == Key.DirectionRight) 1 else -1)
                                if (move != null) {
                                    movement?.cancel()
                                    fun ownership() = FocusRestorationGuard(route, model.focusCoordinator.last(route),
                                        focusOwner, focusOwner?.hasFocus ?: navigationHasFocus)
                                    var guard = ownership()
                                    val isCurrent = {
                                        traversal.isCurrent(move, currentKeys) &&
                                            guard.isCurrent(model.focusRoute, model.focusCoordinator.last(route),
                                                focusOwner?.hasFocus ?: navigationHasFocus)
                                    }
                                    val request = {
                                        if (!isCurrent()) false else traversal.requestFocus {
                                            registry.request(route, region, move.targetKey)
                                        }.also { granted -> if (granted) guard = ownership() }
                                    }
                                    val visible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == move.targetKey }
                                    val needsScroll = visible == null || visible.offset < state.layoutInfo.viewportStartOffset ||
                                        visible.offset + visible.size > state.layoutInfo.viewportEndOffset
                                    // The native event transfers attached focus now, before
                                    // launching a coroutine or awaiting animation/frames.
                                    val transferred = request()
                                    if (transferred && !needsScroll) traversal.finish(move, activeKey)
                                    else movement = scope.launch {
                                        try {
                                            coroutineScope {
                                                val scroll = if (needsScroll) launch {
                                                    if (isCurrent()) {
                                                        val anchor = currentKeys.indexOf(move.anchorKey)
                                                        if (anchor >= 0) {
                                                            if (effects.reducedMotion) state.scrollToItem(anchor)
                                                            else state.animateScrollToItem(anchor)
                                                        }
                                                    }
                                                } else null
                                                try {
                                                    val granted = transferred || withTimeoutOrNull(2_000) {
                                                        awaitRailFocusTarget(isCurrent, request) { withFrameNanos { } }
                                                    } == true
                                                    if (granted) while (scroll?.isActive == true && isCurrent()) {
                                                        withFrameNanos { }
                                                    }
                                                } finally { scroll?.cancel() }
                                            }
                                        } finally { traversal.finish(move, activeKey) }
                                    }
                                }
                                true
                            }
                        }.width(if (portrait) layout.posterWidth else layout.landscapeWidth))
            }
        }
    }
}
