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
import kotlinx.coroutines.launch
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
    val saved = remember { model.focusCoordinator.restore(model.focusRoute, region) }
    val itemKeys = items.map { it.presentationKey }
    RegisterLazyFocusRegion(model, region, state, itemKeys)
    var activeKey by remember { mutableStateOf(saved ?: itemKeys.first()) }
    var requestedKey by remember { mutableStateOf(activeKey) }
    val active = itemKeys.indexOf(activeKey).coerceAtLeast(0)
    var moving by remember { mutableStateOf(false) }
    var movement by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(itemKeys) {
        // A recommendation refresh must not finish a queued move to an index
        // that now represents a different movie.
        generation++
        movement?.cancel()
        moving = false
        requestedKey = activeKey
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
                    if (!moving) requestedKey = item.presentationKey
                    model.onMediaFocus(item, region)
                }, onClick = { model.open(item) },
                    portrait = portrait, continueWatching = continueWatching,
                    modifier = Modifier.focusRequester(target)
                        .then(if (entryRequester != null && index == active) Modifier.focusRequester(entryRequester) else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key !in listOf(Key.DirectionLeft, Key.DirectionRight)) false
                            else {
                                val requested = itemKeys.indexOf(requestedKey).coerceAtLeast(0)
                                val next = (requested + if (event.key == Key.DirectionRight) 1 else -1).coerceIn(0, items.lastIndex)
                                if (next != requested) {
                                    requestedKey = itemKeys[next]; moving = true
                                    val token = ++generation
                                    movement?.cancel()
                                    movement = scope.launch {
                                        try {
                                            val visible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == next }
                                            if (visible == null || visible.offset < state.layoutInfo.viewportStartOffset || visible.offset + visible.size > state.layoutInfo.viewportEndOffset) {
                                                val anchor = if (next > active) (next - 2).coerceAtLeast(0) else next
                                                if (effects.reducedMotion) state.scrollToItem(anchor) else state.animateScrollToItem(anchor)
                                            }
                                            withFrameNanos { }; withFrameNanos { }
                                            registry.request(model.focusRoute, region, items[next].presentationKey)
                                        } finally { if (token == generation) moving = false }
                                    }
                                }
                                true
                            }
                        }.width(if (portrait) layout.posterWidth else layout.landscapeWidth))
            }
        }
    }
}
