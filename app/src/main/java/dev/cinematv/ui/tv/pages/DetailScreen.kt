package dev.cinematv.ui.tv.pages

import dev.cinematv.ui.icons.CinemaSymbol
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.data.LibraryStatus
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.shared.*
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun DetailScreen(model: CinemaViewModel) {
    val libraryVersion = model.libraryVersion
    val item = model.selected ?: return
    val layout = LocalTvLayout.current
    val entry = LocalContentFocus.current
    val primary = rememberFocusTarget(model, "actions", "play")
    val shelf = rememberFocusTarget(model, "actions", "shelf")
    val state = rememberTvListState(model, "page:${item.title}")
    var picker by remember { mutableStateOf(false) }
    var pickerWasOpened by remember { mutableStateOf(false) }
    RestorePageFocus(model)
    LaunchedEffect(picker) {
        if (!picker && pickerWasOpened) { withFrameNanos { }; runCatching { shelf.requestFocus() } }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 45.dp)) {
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(start = layout.gutter, end = layout.gutter, top = 28.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)) {
                GlassAction("← 返回", Modifier.width(96.dp).height(36.dp), icon = CinemaSymbol.Back) { model.back() }
                Spacer(Modifier.height((layout.height * .07f).coerceAtLeast(15.dp)))
                Text(item.title, color = TvColors.Text, fontSize = 44.sp, lineHeight = 50.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.76f))
                Text(item.metadataLine().ifBlank { if (item.isTv) "剧集" else "电影" }, color = TvColors.Accent, fontSize = 14.sp)
                Text(item.description.ifBlank { if (model.busy) "正在读取作品详情…" else "暂无作品简介" }, color = TvColors.Text.copy(alpha = .78f),
                    fontSize = 15.sp, lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.73f))
                if (model.resolutionStatus.isNotBlank()) Text(model.resolutionStatus, color = TvColors.Secondary, fontSize = 12.sp)
                if (item.catalogAttribution.isNotBlank()) Text(item.catalogAttribution, color = TvColors.Secondary, fontSize = 11.sp)
                if (model.selectedRecommendationReason.startsWith("因为") || model.selectedRecommendationReason.startsWith("来自"))
                    Text(model.selectedRecommendationReason, color = TvColors.Accent, fontSize = 12.sp)
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassAction(if (item.positionMs > 0) "▶ 继续播放" else "▶ 立即播放",
                        Modifier.focusRequester(entry).focusRequester(primary).width(150.dp).height(45.dp), prominent = true, icon = CinemaSymbol.Play,
                        onFocus = { model.focusCoordinator.remember(model.focusRoute, "actions", "play") }) {
                        model.playEpisode(item.lastEpisode.coerceIn(0, (item.episodes.size - 1).coerceAtLeast(0)))
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
        if (item.episodes.isEmpty()) item {
            Text(if (model.busy) "正在获取播放列表…" else "当前片源没有可播放地址，可尝试更换片源。", color = TvColors.Secondary,
                fontSize = 15.sp, modifier = Modifier.padding(horizontal = layout.gutter))
        }
        val columns = ((layout.width - layout.gutter * 2).value / 116).toInt().coerceIn(3, 7)
        items((item.episodes.size + columns - 1) / columns, key = { "episode-row:$it" }) { row ->
            Row(Modifier.padding(horizontal = layout.gutter, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(columns) { col ->
                    val index = row * columns + col
                    if (index < item.episodes.size) {
                        val target = rememberFocusTarget(model, "episodes", index.toString())
                        GlassAction(model.episodeLabel(index),
                            Modifier.weight(1f).height(43.dp).focusRequester(target), selected = index == item.lastEpisode,
                            onFocus = { model.focusCoordinator.remember(model.focusRoute, "episodes", index.toString()) }) { model.playEpisode(index) }
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    if (picker) {
        val cancel = remember { FocusRequester() }
        Dialog(onDismissRequest = { picker = false }, properties = DialogProperties(
            dismissOnBackPress = true, usePlatformDefaultWidth = false
        )) {
            CinemaGlassSurface(Modifier.width(350.dp), radius = 26.dp) {
                Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("加入片库", color = TvColors.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    LibraryStatus.entries.forEach { status ->
                        GlassAction(status.title, Modifier.fillMaxWidth().height(43.dp),
                            selected = model.repo.libraryStatus(item) == status) { model.setLibraryStatus(status); picker = false }
                    }
                    if (model.hasLibraryStatus(item)) {
                        GlassAction("移除片库状态", Modifier.fillMaxWidth().height(43.dp)) {
                            model.removeLibraryStatus()
                            picker = false
                        }
                    }
                    GlassAction("取消", Modifier.focusRequester(cancel).fillMaxWidth().height(40.dp)) { picker = false }
                }
            }
            LaunchedEffect(Unit) { withFrameNanos { }; cancel.requestFocus() }
        }
    }
}
