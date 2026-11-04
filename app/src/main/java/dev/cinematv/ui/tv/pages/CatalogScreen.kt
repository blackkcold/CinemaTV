package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.shared.*
import dev.cinematv.ui.tv.components.*
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun CatalogScreen(model: CinemaViewModel, series: Boolean) {
    val all = if (series) model.browseSeries else model.browseMovies
    val layout = LocalTvLayout.current
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf<String?>(null) }
    var rated by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(all, category, year, rated) { CatalogFilter(category, year, rated).apply(all) }
    val region = if (series) "series-grid" else "movie-grid"
    val saved = remember { model.focusCoordinator.scroll(model.focusRoute, region) }
    val grid = rememberLazyGridState(saved.index, saved.offset)
    val route = model.focusRoute
    DisposableEffect(grid) { onDispose { model.focusCoordinator.saveScroll(route, region, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) } }
    val entry = LocalContentFocus.current
    val nav = LocalNavigationFocus.current
    RestorePageFocus(model)
    Column(Modifier.fillMaxSize().padding(top = layout.contentTop)) {
        Column(Modifier.padding(horizontal = layout.gutter)) {
            Text(if (series) "好故事，慢慢看。" else "为大屏而生。", color = TvColors.Text, fontSize = 34.sp)
            Spacer(Modifier.height(5.dp))
            Text(if (series) "剧集 · 找到下一部想追的故事" else "电影 · 发现值得留出时间的作品", color = TvColors.Secondary, fontSize = 14.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            item { GlassAction("全部", Modifier.focusRequester(entry).focusProperties { up = nav }.width(80.dp).height(36.dp), selected = category == null && year == null) { category = null; year = null } }
            items(all.map { it.category }.filter(String::isNotBlank).distinct().take(12)) { value ->
                GlassAction(value, Modifier.widthIn(min = 80.dp, max = 160.dp).height(36.dp).padding(horizontal = 2.dp), selected = category == value) { category = if (category == value) null else value }
            }
            items(all.map { it.year }.filter(String::isNotBlank).distinct().sortedDescending().take(8)) { value ->
                GlassAction(value, Modifier.width(80.dp).height(36.dp), selected = year == value) { year = if (year == value) null else value }
            }
            item { GlassAction(if (rated) "评分优先 ✓" else "评分优先", Modifier.width(108.dp).height(36.dp), selected = rated) { rated = !rated } }
        }
        LazyVerticalGrid(state = grid,
            columns = GridCells.Adaptive(if (series) layout.landscapeWidth else layout.posterWidth),
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, top = 14.dp, bottom = 45.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (filtered.isEmpty()) item(key = "catalog-empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(if (all.isEmpty()) "暂无推荐，连接服务器后刷新。" else "没有符合筛选条件的作品。",
                    color = TvColors.Secondary, modifier = Modifier.padding(vertical = 16.dp))
            }
            items(filtered, key = { it.presentationKey }) { item ->
                val target = rememberFocusTarget(model, region, item.presentationKey)
                FocusMediaCard(item, { model.onMediaFocus(item, region) }, { model.open(item) },
                    modifier = Modifier.focusRequester(target), portrait = !series)
            }
            // Paging belongs after the results. A fixed header would leave portrait
            // captions below the viewport even before the first row receives focus.
            item(key = "catalog-paging", span = { GridItemSpan(maxLineSpan) }) {
                CatalogPagingControls(model, series)
            }
        }
    }
}
