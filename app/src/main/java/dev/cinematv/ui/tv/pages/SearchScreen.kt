package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.data.SearchFilters
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.tv.components.TvChip
import dev.cinematv.ui.tv.components.TvEmptyState
import dev.cinematv.ui.tv.components.TvErrorState
import dev.cinematv.ui.tv.components.TvChipRow
import dev.cinematv.ui.tv.components.TvField
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.components.SearchPagingControl
import dev.cinematv.ui.tv.components.FocusMediaCard
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun SearchScreen(model: CinemaViewModel) {
    val layout = LocalTvLayout.current
    val entry = LocalContentFocus.current
    val navigation = LocalNavigationFocus.current
    val saved = remember { model.focusCoordinator.scroll(model.focusRoute, "results") }
    val grid = rememberLazyGridState(saved.index, saved.offset)
    val route = model.focusRoute
    DisposableEffect(grid) { onDispose { model.focusCoordinator.saveScroll(route, "results", grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) } }
    RegisterLazyFocusGridRegion(model, "search-grid", grid, model.results.map { it.presentationKey })
    val input = rememberFocusTarget(model, "search", "input")
    val submit = { if (model.query.isNotBlank()) model.search() }
    RestorePageFocus(model)
    val searchBookmark = remember(model.focusRoute) { model.focusCoordinator.last(model.focusRoute) }
    var initialSearchFocusDone by remember(model.focusRoute) { mutableStateOf(false) }
    LaunchedEffect(model.focusRoute) {
        if (!initialSearchFocusDone && (searchBookmark == null || searchBookmark.region == "search")) {
            withFrameNanos { }; withFrameNanos { }
            runCatching { entry.requestFocus() }
            initialSearchFocusDone = true
        }
    }
    Column(Modifier.fillMaxSize().padding(top = layout.contentTop)) {
        Column(Modifier.padding(horizontal = layout.gutter)) {
            Text("想看的，在这里。", color = TvColors.Text, fontSize = 32.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvField(model.query, { model.query = it }, "片名、别名或演员", Modifier.weight(1f),
                    tag = "search-field", onSubmit = submit,
                    shellModifier = Modifier.focusRequester(entry).focusRequester(input)
                        .onFocusChanged { if (it.hasFocus) model.focusCoordinator.remember(model.focusRoute, "search", "input") }
                        .focusProperties { up = navigation })
                GlassAction("搜索", Modifier.width(110.dp).height(52.dp), prominent = true,
                    enabled = model.query.isNotBlank(), onClick = submit)
            }
        }
        if (model.searchHistory.isNotEmpty()) TvChipRow(
            contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 15.dp)) {
            items(model.searchHistory, key = { it }) { term ->
                TvChip(term, modifier = Modifier.widthIn(min = 90.dp, max = 200.dp)) { model.query = term; submit() }
            }
        } else Spacer(Modifier.height(18.dp))
        TvChipRow(contentPadding = PaddingValues(horizontal = layout.gutter)) {
            item {
                TvChip(when (model.searchFilters.isTv) { true -> "剧集"; false -> "电影"; null -> "全部类型" }, modifier = Modifier.width(110.dp)) {
                    model.updateSearchFilters(model.searchFilters.copy(isTv = when (model.searchFilters.isTv) { null -> false; false -> true; true -> null }))
                }
            }
            item { TvChip(model.searchFilters.year ?: "全部年份", modifier = Modifier.width(110.dp)) { model.cycleSearchYear() } }
            item { TvChip("TMDB ≥ 8", modifier = Modifier.width(120.dp), selected = model.searchFilters.ratingSource == "TMDB") {
                model.updateSearchFilters(model.searchFilters.copy(minRating = 8.0, ratingSource = "TMDB", doubanTopOnly = false))
            } }
            item { TvChip("豆瓣 ≥ 8", modifier = Modifier.width(120.dp), selected = model.searchFilters.ratingSource == "Douban" && !model.searchFilters.doubanTopOnly) {
                model.updateSearchFilters(model.searchFilters.copy(minRating = 8.0, ratingSource = "Douban", doubanTopOnly = false))
            } }
            item { TvChip("豆瓣 TOP250", modifier = Modifier.width(145.dp), selected = model.searchFilters.doubanTopOnly) {
                model.updateSearchFilters(model.searchFilters.copy(minRating = null, ratingSource = "Douban", doubanTopOnly = true))
            } }
            item { TvChip("重置筛选", modifier = Modifier.width(110.dp)) { model.updateSearchFilters(SearchFilters()) } }
        }
        if (model.searchFilters.ratingSource == "Douban") TvChipRow(
            contentPadding = PaddingValues(horizontal = layout.gutter)) {
            items(dev.cinematv.data.douban.public.DoubanPublicCatalog.uiTags, key = { it }) { tag ->
                val selected = model.searchFilters.doubanTag == tag
                TvChip(tag, selected = selected, modifier = Modifier.widthIn(min = 86.dp, max = 130.dp)) {
                    model.selectDoubanTag(if (selected) null else tag)
                }
            }
        }
        Text(if (model.searchFilters.ratingSource == "Douban") "豆瓣榜单与评分来自公开页面，个人使用；登录豆瓣账号可读取个人片单。" else model.searchStage,
            color = TvColors.Secondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 5.dp))
        if (model.catalogStatus.isNotBlank()) Text(model.catalogStatus, color = TvColors.Secondary,
            fontSize = 11.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 4.dp))
        if (model.catalogAttribution.isNotBlank()) Text(model.catalogAttribution, color = TvColors.Secondary,
            fontSize = 10.sp, modifier = Modifier.padding(horizontal = layout.gutter))
        Text(if (model.busy || model.chartLoading) "正在搜索 · 已找到 ${model.results.size} 部作品" else if (model.results.isNotEmpty()) "找到 ${model.results.size} 部作品" else "从一个片名，开始一段旅程。",
            color = TvColors.Secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 6.dp))
        if (model.results.isEmpty() && !model.busy && !model.chartLoading) {
            if (model.searchError.isNotBlank()) {
                TvErrorState("搜索失败", model.searchError,
                    onRetry = { if (model.query.isNotBlank()) model.search() },
                    modifier = Modifier.padding(horizontal = layout.gutter, vertical = 12.dp))
            } else {
                TvEmptyState(
                    title = if (model.query.isBlank()) "从一个片名开始" else "没有找到结果",
                    detail = if (model.query.isBlank()) "输入片名、别名或演员，按 OK 进入编辑"
                        else "换个关键词，或重置筛选条件",
                    modifier = Modifier.padding(horizontal = layout.gutter, vertical = 12.dp),
                    action = if (model.query.isBlank()) null else {
                        { TvChip("重置筛选") { model.updateSearchFilters(SearchFilters()) } }
                    },
                )
            }
        }
        Box(Modifier.padding(horizontal = layout.gutter)) { SearchPagingControl(model) }
        LazyVerticalGrid(columns = GridCells.Adaptive(layout.posterWidth), state = grid,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, top = 15.dp, bottom = 45.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            itemsIndexed(model.results, key = { _, item -> item.presentationKey }) { index, item ->
                val requester = rememberFocusTarget(model, "search-grid", item.presentationKey)
                FocusMediaCard(item, { model.onMediaFocus(item, "search-grid", index) }, { model.open(item) },
                    Modifier.focusRequester(requester), portrait = true)
            }
        }
    }
}
