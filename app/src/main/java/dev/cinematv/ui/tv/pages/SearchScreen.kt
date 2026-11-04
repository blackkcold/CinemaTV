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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.cinematv.CinemaViewModel
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
    val saved = remember { model.focusCoordinator.scroll(model.page.name, "results") }
    val grid = rememberLazyGridState(saved.index, saved.offset)
    val route = model.page.name
    DisposableEffect(grid) { onDispose { model.focusCoordinator.saveScroll(route, "results", grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) } }
    val input = rememberFocusTarget(model, "search", "input")
    val submit = { keyboard?.hide(); model.search() }
    RestorePageFocus(model)
    Column(Modifier.fillMaxSize().padding(top = layout.contentTop)) {
        Column(Modifier.padding(horizontal = layout.gutter)) {
            Text("想看的，在这里。", color = TvColors.Text, fontSize = 32.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvInput(model.query, { model.query = it }, "输入片名或关键词",
                    Modifier.weight(1f).height(52.dp).focusRequester(entry).focusRequester(input).focusProperties { up = navigation },
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
        Text(if (model.busy) "正在搜索 · 已找到 ${model.results.size} 部作品" else if (model.results.isNotEmpty()) "找到 ${model.results.size} 部作品" else "从一个片名，开始一段旅程。",
            color = TvColors.Secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 6.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(layout.posterWidth), state = grid,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, top = 15.dp, bottom = 45.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            items(model.results.ifEmpty { if (model.busy) emptyList() else model.featured.take(12) }, key = { it.presentationKey }) { item ->
                val requester = rememberFocusTarget(model, "search-grid", item.presentationKey)
                FocusMediaCard(item, { model.onMediaFocus(item, "search-grid") }, { model.open(item) }, Modifier.focusRequester(requester), portrait = true)
            }
        }
    }
}
