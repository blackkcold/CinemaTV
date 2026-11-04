package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.cinematv.CinemaViewModel
import dev.cinematv.ui.tv.components.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun HomeScreen(model: CinemaViewModel) {
    val history = model.history()
    val state = rememberTvListState(model, "page")
    val firstRail = remember { FocusRequester() }
    val hasRail = history.isNotEmpty() || model.featured.isNotEmpty() || model.tvShows.isNotEmpty()
    RestorePageFocus(model)
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 45.dp)) {
        item(key = "hero") { CinemaHero(model.focused ?: model.featured.firstOrNull(), model, belowFocus = firstRail.takeIf { hasRail }) }
        if (history.isNotEmpty()) item(key = "continue") { ContentRail("继续观看", history, model, continueWatching = true, entryRequester = firstRail) }
        if (model.featured.isNotEmpty()) item(key = "movies") { ContentRail("为你精选", model.featured, model, entryRequester = firstRail.takeIf { history.isEmpty() }) }
        if (model.tvShows.isNotEmpty()) item(key = "series") { ContentRail("值得追的好故事", model.tvShows, model, portrait = true, entryRequester = firstRail.takeIf { history.isEmpty() && model.featured.isEmpty() }) }
        if (model.featured.size > 6) item(key = "more") { ContentRail("更多电影", model.featured.drop(6), model, portrait = true) }
    }
}
