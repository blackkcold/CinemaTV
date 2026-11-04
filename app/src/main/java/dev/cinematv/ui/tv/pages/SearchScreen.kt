package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.data.SearchFilters
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.TvInput
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.components.FocusMediaCard
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun SearchScreen(model: CinemaViewModel) {
    val layout = LocalTvLayout.current
    val entry = LocalContentFocus.current
    val navigation = LocalNavigationFocus.current
    val keyboard = LocalSoftwareKeyboardController.current
    val saved = remember { model.focusCoordinator.scroll(model.focusRoute, "results") }
    val grid = rememberLazyGridState(saved.index, saved.offset)
    val route = model.focusRoute
    DisposableEffect(grid) { onDispose { model.focusCoordinator.saveScroll(route, "results", grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) } }
    val input = rememberFocusTarget(model, "search", "input")
    val submit = { keyboard?.hide(); model.search() }
    RestorePageFocus(model)
    Column(Modifier.fillMaxSize().padding(top = layout.contentTop)) {
        Column(Modifier.padding(horizontal = layout.gutter)) {
            Text("想看的，在这里。", color = TvColors.Text, fontSize = 32.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvInput(model.query, { model.query = it }, "片名、别名或演员",
                    Modifier.weight(1f).height(52.dp).focusRequester(entry).focusRequester(input)
                        .onFocusChanged { if (it.hasFocus) model.focusCoordinator.remember(model.focusRoute, "search", "input") }
                        .focusProperties { up = navigation },
                    onSubmit = submit)
                GlassAction("搜索", Modifier.width(110.dp).height(52.dp), prominent = true, onClick = submit)
            }
        }
        if (model.searchHistory.isNotEmpty()) LazyRow(
            contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 15.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.focusRestorer()) {
            items(model.searchHistory, key = { it }) { term ->
                GlassAction(term, Modifier.widthIn(min = 90.dp, max = 200.dp).height(35.dp)) { model.query = term; submit() }
            }
        } else Spacer(Modifier.height(18.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusRestorer()) {
            item {
                GlassAction(when (model.searchFilters.isTv) { true -> "剧集"; false -> "电影"; null -> "全部类型" }, Modifier.width(110.dp).height(35.dp)) {
                    model.updateSearchFilters(model.searchFilters.copy(isTv = when (model.searchFilters.isTv) { null -> false; false -> true; true -> null }))
                }
            }
            item { GlassAction(model.searchFilters.year ?: "全部年份", Modifier.width(110.dp).height(35.dp)) { model.cycleSearchYear() } }
            item { GlassAction("TMDB ≥ 8", Modifier.width(120.dp).height(35.dp), selected = model.searchFilters.ratingSource == "TMDB") {
                model.updateSearchFilters(model.searchFilters.copy(minRating = 8.0, ratingSource = "TMDB", doubanTopOnly = false))
            } }
            item { GlassAction("豆瓣 ≥ 8", Modifier.width(120.dp).height(35.dp), selected = model.searchFilters.ratingSource == "Douban" && !model.searchFilters.doubanTopOnly) {
                model.updateSearchFilters(model.searchFilters.copy(minRating = 8.0, ratingSource = "Douban", doubanTopOnly = false))
            } }
            item { GlassAction("豆瓣 TOP250", Modifier.width(145.dp).height(35.dp), selected = model.searchFilters.doubanTopOnly) {
                model.updateSearchFilters(model.searchFilters.copy(minRating = null, ratingSource = "Douban", doubanTopOnly = true))
            } }
            item { GlassAction("重置筛选", Modifier.width(110.dp).height(35.dp)) { model.updateSearchFilters(SearchFilters()) } }
        }
        Text(if (model.searchFilters.ratingSource == "Douban") "豆瓣筛选仅显示有效授权目录；未配置时不会替代为其他评分。" else model.searchStage,
            color = TvColors.Secondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 5.dp))
        if (model.catalogStatus.isNotBlank()) Text(model.catalogStatus, color = TvColors.Secondary,
            fontSize = 11.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 4.dp))
        if (model.catalogAttribution.isNotBlank()) Text(model.catalogAttribution, color = TvColors.Secondary,
            fontSize = 10.sp, modifier = Modifier.padding(horizontal = layout.gutter))
        Text(if (model.busy || model.chartLoading) "正在搜索 · 已找到 ${model.results.size} 部作品" else if (model.results.isNotEmpty()) "找到 ${model.results.size} 部作品" else "从一个片名，开始一段旅程。",
            color = TvColors.Secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 6.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(layout.posterWidth), state = grid,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, top = 15.dp, bottom = 45.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            items(model.results, key = { it.presentationKey }) { item ->
                val requester = rememberFocusTarget(model, "search-grid", item.presentationKey)
                FocusMediaCard(item, { model.onMediaFocus(item, "search-grid") }, { model.open(item) }, Modifier.focusRequester(requester), portrait = true)
            }
        }
    }
}
