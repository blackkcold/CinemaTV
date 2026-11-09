package dev.cinematv.ui.tv.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.ConnectionAction
import dev.cinematv.ConnectionCopy
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.shared.*
import dev.cinematv.ui.tv.components.CatalogPagingControls
import dev.cinematv.ui.tv.components.TvChip
import dev.cinematv.ui.tv.components.TvDialogFrame
import dev.cinematv.ui.tv.components.TvEmptyState
import dev.cinematv.ui.tv.components.TvErrorState
import dev.cinematv.ui.tv.components.TvLoadingState
import dev.cinematv.ui.tv.components.TvPageHeader
import dev.cinematv.ui.tv.components.FocusMediaCard
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun ExploreScreen(model: CinemaViewModel) {
    val items = (model.browseMovies + model.browseSeries).distinctBy { it.presentationKey }
    val layout = LocalTvLayout.current
    val effects = LocalTvEffects.current
    val saved = remember { model.focusCoordinator.restore(model.focusRoute, "coverflow") }
    // Appending movies inserts before series. Retain the selected work's key,
    // not its old numeric position, while the paging panel has focus.
    var activeKey by remember { mutableStateOf(saved) }
    val active = items.indexOfFirst { it.presentationKey == activeKey }.coerceAtLeast(0)
    val list = rememberTvListState(model, "coverflow")
    val cardWidth = (layout.height * .29f).coerceIn(130.dp, 220.dp)
    val inset = (layout.width - cardWidth) / 2
    val entry = LocalContentFocus.current
    val nav = LocalNavigationFocus.current
    val registry = LocalTvFocusRegistry.current
    RegisterLazyFocusRegion(model, "coverflow", list, items.map { it.presentationKey })
    val pagingOpener = rememberFocusTarget(model, "catalog-panel", "open")
    var pagingOpen by remember { mutableStateOf(false) }
    var pagingWasOpened by remember { mutableStateOf(false) }
    var requestMovement by remember { mutableStateOf(false) }
    RestorePageFocus(model)
    val coverflowBookmark = remember(model.focusRoute) { model.focusCoordinator.last(model.focusRoute) }
    var initialCoverflowFocusDone by remember(model.focusRoute) { mutableStateOf(false) }
    LaunchedEffect(model.focusRoute, items.isEmpty()) {
        if (!initialCoverflowFocusDone && items.isNotEmpty() &&
            (coverflowBookmark == null || coverflowBookmark.region != "coverflow")) {
            withFrameNanos { }; withFrameNanos { }
            runCatching { entry.requestFocus() }
            initialCoverflowFocusDone = true
        }
    }
    LaunchedEffect(pagingOpen) {
        if (!pagingOpen && pagingWasOpened) { withFrameNanos { }; runCatching { pagingOpener.requestFocus() } }
    }
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
    Column(Modifier.fillMaxSize().padding(top = layout.contentTop).testTag("tv-page-explore"), horizontalAlignment = Alignment.CenterHorizontally) {
        TvPageHeader("下一段旅程，随心发现。", "左右探索 · 按确认键走进故事", modifier = Modifier.padding(horizontal = layout.gutter))
        if (items.isEmpty()) {
            val state = model.connectionState
            val action = ConnectionCopy.action(state)
            val retryable = state is dev.cinematv.ConnectionState.Unreachable ||
                state is dev.cinematv.ConnectionState.Incompatible
            when {
                model.busy -> TvLoadingState(
                    "正在载入发现…",
                    Modifier.padding(horizontal = layout.gutter, vertical = 28.dp),
                )
                retryable -> TvErrorState(
                    title = "发现暂时无法载入",
                    detail = ConnectionCopy.summary(state),
                    onRetry = { model.refreshCatalog() },
                    modifier = Modifier.padding(horizontal = layout.gutter, vertical = 28.dp),
                )
                else -> TvEmptyState(
                    title = "还没有可探索的作品",
                    detail = ConnectionCopy.summary(state),
                    modifier = Modifier.padding(horizontal = layout.gutter, vertical = 28.dp),
                    action = {
                        TvChip(if (action == ConnectionAction.SEARCH) "开始搜索" else "连接影视服务",
                            modifier = Modifier.focusRequester(entry)) {
                            model.navigate(if (action == ConnectionAction.SEARCH) dev.cinematv.Page.SEARCH else dev.cinematv.Page.SETTINGS)
                        }
                    },
                )
            }
        } else LazyRow(state = list, modifier = Modifier.testTag("discovery-coverflow").fillMaxWidth().weight(1f).focusRestorer(),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = inset, vertical = 25.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            itemsIndexed(items, key = { _, item -> item.presentationKey }) { index, item ->
                val center = index == active
                val tilt by animateFloatAsState(if (effects.reducedMotion) 0f else when { index < active -> 12f; index > active -> -12f; else -> 0f }, tween(effects.focusMillis), label = "coverflow-tilt")
                val scale by animateFloatAsState(if (center) 1f else .90f, tween(effects.focusMillis), label = "coverflow-scale")
                val target = rememberFocusTarget(model, "coverflow", item.presentationKey)
                FocusMediaCard(item, onFocus = { activeKey = item.presentationKey; model.onMediaFocus(item, "coverflow", index) }, onClick = { model.open(item) }, portrait = true, showCaption = false,
                    modifier = Modifier.width(cardWidth).focusRequester(target)
                        .then(if (index == active) Modifier.focusRequester(entry) else Modifier)
                        .focusProperties { up = nav; down = pagingOpener }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key !in listOf(Key.DirectionLeft, Key.DirectionRight)) false
                            else {
                                val next = (active + if (event.key == Key.DirectionRight) 1 else -1).coerceIn(0, items.lastIndex)
                                if (next != active) { requestMovement = true; activeKey = items[next].presentationKey }
                                true
                            }
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; rotationY = tilt; alpha = if (center) 1f else .67f; cameraDistance = 16f * density })
            }
        }
        // Reuse the caption band rather than reduce the coverflow's vertical budget.
        // A short LazyRow would clip the offscreen-rendered, translucent side cards.
        Box(Modifier.fillMaxWidth().padding(horizontal = layout.gutter)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(items.getOrNull(active)?.title.orEmpty(), color = TvColors.Text, fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(items.getOrNull(active)?.metadataLine().orEmpty(), color = TvColors.Secondary, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
            }
            GlassAction("目录与加载", Modifier.testTag("catalog-panel-open").align(Alignment.TopEnd)
                .focusRequester(pagingOpener).focusProperties { up = entry }.width(140.dp).height(36.dp),
                onFocus = { model.focusCoordinator.remember(model.focusRoute, "catalog-panel", "open") }) {
                pagingWasOpened = true; pagingOpen = true
            }
        }
    }
    if (pagingOpen) {
        val close = remember { FocusRequester() }
        TvDialogFrame(onDismissRequest = { pagingOpen = false }, maxWidth = 600.dp) {
            Text("目录状态与加载", color = TvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            GlassAction("关闭", Modifier.testTag("catalog-panel-close").focusRequester(close).width(125.dp).height(36.dp)) {
                pagingOpen = false
            }
            CatalogPagingControls(model, false)
            CatalogPagingControls(model, true)
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { close.requestFocus() } }
        }
    }
}
