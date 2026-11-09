package dev.cinematv.ui.tv.pages

import dev.cinematv.ui.icons.CinemaSymbol
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.data.LibraryStatus
import dev.cinematv.data.EpisodePlaybackAction
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.shared.*
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import dev.cinematv.ui.tv.components.TvAboutSection
import dev.cinematv.ui.tv.components.TvChip
import dev.cinematv.ui.tv.components.TvEmptyState
import dev.cinematv.ui.tv.components.TvDialogFrame
import dev.cinematv.ui.tv.components.TvEmptyState
import dev.cinematv.ui.tv.components.TvLoadingState
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun DetailScreen(model: CinemaViewModel) {
    val libraryVersion = model.libraryVersion
    val item = model.selected
    if (item == null) {
        // A restored DETAIL route can outlive its selection; never leave a blank page.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TvEmptyState(
                title = "作品信息未加载",
                detail = "返回列表并重新选择一部作品",
                action = { TvChip("返回") { model.back() } },
            )
        }
        return
    }
    val resumeIndex = item.lastEpisode.coerceIn(0, (item.episodes.size - 1).coerceAtLeast(0))
    val resumeProgress = model.episodeProgress(resumeIndex)
    val layout = LocalTvLayout.current
    val entry = LocalContentFocus.current
    val primary = rememberFocusTarget(model, "actions", "play")
    val shelf = rememberFocusTarget(model, "actions", "shelf")
    val state = rememberTvListState(model, "detail-page")
    var picker by remember { mutableStateOf(false) }
    var pickerWasOpened by remember { mutableStateOf(false) }
    val registry = LocalTvFocusRegistry.current
    val episodeRowStart = 2 + (if (model.workNavigation?.visible == true) 1 else 0) +
        (if (model.alternatives.isNotEmpty()) 1 else 0) + 1
    DisposableEffect(model.focusRoute, state, item.episodes.size, episodeRowStart) {
        registry.attachLazyRegion(model.focusRoute, "episodes") { key, fallbackIndex ->
            if (item.episodes.isEmpty()) null else {
                val episode = (key.toIntOrNull() ?: fallbackIndex)
                    .coerceIn(0, item.episodes.lastIndex)
                state.scrollToItem(episodeRowStart + episode / 4)
                episode.toString()
            }
        }
        onDispose { registry.detachLazyRegion(model.focusRoute, "episodes") }
    }
    RestorePageFocus(model)
    // A first DETAIL entry has no bookmark; land on the primary action once the
    // hero item is actually composed. Returning to a saved action/episode keeps
    // RestorePageFocus in charge.
    val detailBookmark = remember(model.focusRoute) { model.focusCoordinator.last(model.focusRoute) }
    var initialDetailFocusDone by remember(model.focusRoute) { mutableStateOf(false) }
    LaunchedEffect(item.presentationKey) {
        if (!initialDetailFocusDone && (detailBookmark == null || detailBookmark.region == "actions")) {
            while (model.busy) withFrameNanos { }
            withFrameNanos { }; withFrameNanos { }
            runCatching { entry.requestFocus() }
            initialDetailFocusDone = true
        }
    }
    LaunchedEffect(picker) {
        if (!picker && pickerWasOpened) { withFrameNanos { }; runCatching { shelf.requestFocus() } }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("tv-page-detail"), contentPadding = PaddingValues(bottom = 45.dp)) {
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(start = layout.gutter, end = layout.gutter, top = 28.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)) {
                GlassAction("← 返回", Modifier.width(96.dp).height(36.dp), icon = CinemaSymbol.Back) { model.back() }
                Spacer(Modifier.height((layout.height * .07f).coerceAtLeast(15.dp)))
                Text(item.title, color = TvColors.Text, fontSize = 44.sp, lineHeight = 50.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.76f))
                Text(item.metadataLine().ifBlank { if (item.isTv) "剧集" else "电影" }, color = TvColors.Accent, fontSize = 14.sp)
                TvAboutSection(
                    description = item.description.ifBlank {
                        if (model.busy) "正在读取作品详情…" else "暂无作品简介"
                    },
                    modifier = Modifier.fillMaxWidth(.73f),
                    facts = buildList {
                        item.source?.name?.takeIf(String::isNotBlank)?.let { add("片源" to it) }
                        model.resolutionStatus.takeIf(String::isNotBlank)?.let { add("状态" to it) }
                        if (model.selectedRecommendationReason.startsWith("因为") ||
                            model.selectedRecommendationReason.startsWith("来自"))
                            add("推荐" to model.selectedRecommendationReason)
                        item.catalogAttribution.takeIf(String::isNotBlank)?.let { add("来源" to it) }
                    },
                )
                // Return from the full-width rows below to the action the user
                // actually left, rather than whichever button is nearest their center.
                Row(Modifier.padding(vertical = 6.dp).focusRestorer(primary).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassAction(if (resumeProgress?.completed == true) "▶ 重新播放" else if (item.positionMs > 0) "▶ 继续播放" else "▶ 立即播放",
                        Modifier.focusRequester(entry).focusRequester(primary).width(150.dp).height(45.dp), prominent = true, icon = CinemaSymbol.Play,
                        enabled = item.episodes.isNotEmpty(),
                        onFocus = { model.focusCoordinator.remember(model.focusRoute, "actions", "play") }) {
                        model.playEpisode(resumeIndex, if (resumeProgress?.completed == true) EpisodePlaybackAction.RESTART else EpisodePlaybackAction.RESUME)
                    }
                    if (resumeProgress != null && !resumeProgress.completed && resumeProgress.positionMs > 0) {
                        GlassAction("从头播放", Modifier.width(115.dp).height(45.dp)) { model.playEpisode(resumeIndex, EpisodePlaybackAction.RESTART) }
                    }
                    GlassAction(if (model.repo.isFavorite(item)) "已收藏 ✓" else "＋ 收藏",
                        Modifier.width(115.dp).height(45.dp), icon = CinemaSymbol.Bookmark, iconSelected = model.repo.isFavorite(item)) { model.toggleFavorite() }
                    GlassAction("更换片源", Modifier.width(122.dp).height(45.dp)) { model.findAlternatives() }
                    GlassAction("片库状态", Modifier.focusRequester(shelf).width(122.dp).height(45.dp),
                        onFocus = { model.focusCoordinator.remember(model.focusRoute, "actions", "shelf") }) { pickerWasOpened = true; picker = true }
                }
                if (item.positionMs > 0) Text("上次观看至 第 ${item.lastEpisode + 1} 集 · ${item.positionMs / 60000} 分钟", color = TvColors.Secondary, fontSize = 12.sp)
            }
        }
        item(key = "manual-work-association") {
            Box(Modifier.padding(horizontal = layout.gutter, vertical = 8.dp)) { ManualWorkAssociationPanel(model, tv = true) }
        }
        if (model.workNavigation?.visible == true) item(key = "work-navigation") {
            Box(Modifier.padding(horizontal = layout.gutter, vertical = 16.dp)) {
                WorkNavigationPanel(model, tv = true)
            }
        }
        if (model.alternatives.isNotEmpty()) item(key = "sources") {
            Column(Modifier.padding(top = 26.dp)) {
                Text("可用片源", color = TvColors.Text, fontSize = 20.sp, modifier = Modifier.padding(start = layout.gutter))
                LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(model.alternatives.distinctBy { it.presentationKey }, key = { it.presentationKey }) { option ->
                        GlassAction(option.source?.name ?: "片源", Modifier.width(160.dp).height(42.dp)) { model.open(option) }
                    }
                }
            }
        }
        item(key = "episode-heading") {
            Text(if (item.episodes.size > 1) "${item.seasonNumber?.let { "第 $it 季 · " }.orEmpty()}剧集 · ${item.episodes.size} 集" else "播放列表", color = TvColors.Text,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = layout.gutter, top = 30.dp, bottom = 17.dp))
        }
        if (item.episodes.isEmpty()) item(key = "episodes-empty") {
            if (model.busy) {
                TvLoadingState("正在获取播放列表…",
                    Modifier.padding(horizontal = layout.gutter, vertical = 16.dp))
            } else {
                TvEmptyState(
                    title = "当前片源没有可播放地址",
                    detail = "换一个片源，或稍后重试",
                    modifier = Modifier.padding(horizontal = layout.gutter, vertical = 12.dp),
                    action = { TvChip("更换片源") { model.findAlternatives() } },
                )
            }
        }
        // Four columns keep every episode label readable; the old 3..7 estimate produced
        // seven cramped chips on a 1080p panel.
        val columns = 4
        items((item.episodes.size + columns - 1) / columns, key = { "episode-row:$it" }) { row ->
            val firstEpisode = remember { FocusRequester() }
            val entryPolicy = if (row == 0) Modifier.focusProperties {
                onEnter = {
                    // Enter the playlist at episode one from the preceding section.
                    // Keep normal column alignment when coming UP from a later row.
                    if (requestedFocusDirection == FocusDirection.Down) firstEpisode.requestFocus()
                }
            }.focusGroup() else Modifier
            // The entry requester is only consulted once this lazy row is composed.
            // Work/season/source controls above retain their normal traversal order.
            Row(Modifier.padding(horizontal = layout.gutter, vertical = 7.dp).then(entryPolicy),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(columns) { col ->
                    val index = row * columns + col
                    if (index < item.episodes.size) {
                        val target = rememberFocusTarget(model, "episodes", index.toString())
                        GlassAction(model.episodeLabel(index),
                            Modifier.weight(1f).height(43.dp).focusRequester(target)
                                .then(if (index == 0) Modifier.focusRequester(firstEpisode) else Modifier),
                            selected = index == item.lastEpisode,
                            onFocus = { model.focusCoordinator.remember(model.focusRoute, "episodes", index.toString(), index) }) { model.playEpisode(index) }
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    if (picker) {
        val cancel = remember { FocusRequester() }
        TvDialogFrame(onDismissRequest = { picker = false }, maxWidth = 480.dp) {
            Text("加入片库", color = TvColors.Text,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            LibraryStatus.entries.forEach { status ->
                GlassAction(status.title, Modifier.fillMaxWidth().height(44.dp),
                    selected = model.repo.libraryStatus(item) == status) {
                    model.setLibraryStatus(status); picker = false
                }
            }
            if (model.hasLibraryStatus(item)) {
                GlassAction("移除片库状态", Modifier.fillMaxWidth().height(44.dp)) {
                    model.removeLibraryStatus()
                    picker = false
                }
            }
            GlassAction("取消", Modifier.focusRequester(cancel).fillMaxWidth().height(44.dp)) { picker = false }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { cancel.requestFocus() } }
        }
    }

}
