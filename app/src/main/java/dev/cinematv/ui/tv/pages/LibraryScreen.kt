package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.data.LibraryStatus
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.tv.components.ContentRail
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun LibraryScreen(model: CinemaViewModel) {
    val layout = LocalTvLayout.current
    val entry = LocalContentFocus.current
    val nav = LocalNavigationFocus.current
    val tick = model.libraryVersion
    val history = remember(tick) { model.history() }
    val shelves = remember(tick) { LibraryStatus.entries.associateWith { if (it == LibraryStatus.FAVORITE) model.favorites() else model.libraryByStatus(it) } }
    var filter by rememberSaveable { mutableStateOf("all") }
    val state = rememberTvListState(model, "page")
    RestorePageFocus(model)
    LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(top = layout.contentTop), contentPadding = PaddingValues(bottom = 42.dp)) {
        item(key = "heading") {
            Column(Modifier.padding(horizontal = layout.gutter)) {
                Text("你的观影时光。", color = TvColors.Text, fontSize = 34.sp)
                Spacer(Modifier.height(6.dp))
                Text("把喜欢的故事留下，把未完的故事继续。", color = TvColors.Secondary, fontSize = 14.sp)
            }
        }
        item(key = "filters") {
            LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { GlassAction("全部", Modifier.focusRequester(entry).focusProperties { up = nav }.width(80.dp).height(36.dp), selected = filter == "all") { filter = "all" } }
                item { GlassAction("继续观看", Modifier.width(105.dp).height(36.dp), selected = filter == "history") { filter = "history" } }
                LibraryStatus.entries.forEach { status ->
                    item(key = status.name) { GlassAction(status.title, Modifier.width(80.dp).height(36.dp), selected = filter == status.name) { filter = status.name } }
                }
            }
        }
        if ((filter == "all" || filter == "history") && history.isNotEmpty()) item(key = "history") {
            ContentRail("继续观看", history, model, continueWatching = true)
        }
        LibraryStatus.entries.filter { filter == "all" || filter == it.name }.forEach { status ->
            val contents = shelves[status].orEmpty()
            if (contents.isNotEmpty()) item(key = status.name) { ContentRail(status.title, contents, model, portrait = true) }
        }
        val isEmpty = when (filter) {
            "all" -> history.isEmpty() && shelves.values.all { it.isEmpty() }
            "history" -> history.isEmpty()
            else -> shelves.entries.firstOrNull { it.key.name == filter }?.value.isNullOrEmpty()
        }
        if (isEmpty) item(key = "empty") {
            Column(Modifier.padding(horizontal = layout.gutter, vertical = 35.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("这里还没有故事。", color = TvColors.Text, fontSize = 24.sp)
                Text("在作品详情页添加，或开始观看后回来继续。", color = TvColors.Secondary, fontSize = 15.sp)
                GlassAction("去发现", Modifier.width(135.dp).height(43.dp)) { model.navigate(Page.EXPLORE) }
            }
        }
    }
}
