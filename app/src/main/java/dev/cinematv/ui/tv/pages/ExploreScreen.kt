package dev.cinematv.ui.tv.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.shared.*
import dev.cinematv.ui.tv.components.FocusMediaCard
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun ExploreScreen(model: CinemaViewModel) {
    val items = (model.browseMovies + model.browseSeries).distinctBy { it.presentationKey }
    val layout = LocalTvLayout.current
    val effects = LocalTvEffects.current
    val saved = remember { model.focusCoordinator.restore(model.focusRoute, "coverflow") }
    var active by remember { mutableIntStateOf(items.indexOfFirst { it.presentationKey == saved }.coerceAtLeast(0)) }
    val list = rememberTvListState(model, "coverflow")
    val cardWidth = (layout.height * .29f).coerceIn(130.dp, 220.dp)
    val inset = (layout.width - cardWidth) / 2
    val entry = LocalContentFocus.current
    val nav = LocalNavigationFocus.current
    val registry = LocalTvFocusRegistry.current
    var requestMovement by remember { mutableStateOf(false) }
    RestorePageFocus(model)
    LaunchedEffect(active, items.size) {
        if (items.isNotEmpty()) {
            if (effects.reducedMotion) list.scrollToItem(active) else list.animateScrollToItem(active)
            if (requestMovement) {
                withFrameNanos { }; withFrameNanos { }
                items.getOrNull(active)?.let { registry.request(model.focusRoute, "coverflow", it.presentationKey) }
                requestMovement = false
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(top = layout.contentTop), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("下一段旅程，随心发现。", color = TvColors.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("左右探索 · 按确认键走进故事", color = TvColors.Secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        if (items.isEmpty()) {
            GlassAction("连接服务器", Modifier.focusRequester(entry).padding(top = 30.dp).width(160.dp).height(45.dp)) { model.navigate(dev.cinematv.Page.SETTINGS) }
        } else LazyRow(state = list, modifier = Modifier.fillMaxWidth().weight(1f).focusRestorer(),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = inset, vertical = 25.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            itemsIndexed(items, key = { _, item -> item.presentationKey }) { index, item ->
                val center = index == active
                val tilt by animateFloatAsState(if (effects.reducedMotion) 0f else when { index < active -> 12f; index > active -> -12f; else -> 0f }, tween(effects.focusMillis), label = "coverflow-tilt")
                val scale by animateFloatAsState(if (center) 1f else .90f, tween(effects.focusMillis), label = "coverflow-scale")
                val target = rememberFocusTarget(model, "coverflow", item.presentationKey)
                FocusMediaCard(item, onFocus = { active = index; model.onMediaFocus(item, "coverflow") }, onClick = { model.open(item) }, portrait = true, showCaption = false,
                    modifier = Modifier.width(cardWidth).focusRequester(target)
                        .then(if (index == active) Modifier.focusRequester(entry) else Modifier)
                        .focusProperties { up = nav }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key !in listOf(Key.DirectionLeft, Key.DirectionRight)) false
                            else {
                                val next = (active + if (event.key == Key.DirectionRight) 1 else -1).coerceIn(0, items.lastIndex)
                                if (next != active) { requestMovement = true; active = next }
                                true
                            }
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; rotationY = tilt; alpha = if (center) 1f else .67f; cameraDistance = 16f * density })
            }
        }
        Text(items.getOrNull(active)?.title.orEmpty(), color = TvColors.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text(items.getOrNull(active)?.metadataLine().orEmpty(), color = TvColors.Secondary, fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
    }
}
