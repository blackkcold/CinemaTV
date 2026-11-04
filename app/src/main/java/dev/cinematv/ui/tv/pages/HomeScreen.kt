package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.CinemaViewModel
import dev.cinematv.ui.tv.components.*
import dev.cinematv.ui.tv.focus.*
import dev.cinematv.ui.tv.design.LocalTvLayout

@Composable
fun HomeScreen(model: CinemaViewModel) {
    val history = model.history()
    val state = rememberTvListState(model, "page")
    val firstRail = remember { FocusRequester() }
    val layout = LocalTvLayout.current
    val hasRail = history.isNotEmpty() || model.featured.isNotEmpty() || model.tvShows.isNotEmpty()
    val bookmark = remember { model.focusCoordinator.last(model.focusRoute) }
    var prepared by remember { mutableStateOf(false) }
    LaunchedEffect(history.size, model.featured.size, model.tvShows.size) {
        withFrameNanos { }
        val rows = buildList {
            add("hero" to "hero")
            if (history.isNotEmpty()) add("continue" to "rail:继续观看")
            if (model.featured.isNotEmpty()) add("movies" to "rail:为你精选")
            if (model.tvShows.isNotEmpty()) add("series" to "rail:值得追的好故事")
            if (model.featured.size > 6) add("more" to "rail:更多电影")
        }
        // A new continue rail can move the bookmarked row. If the whole region
        // disappeared, compose the hero so the page fallback is still usable.
        val index = resolveLazyFocusTarget(rows.map { it.second }, bookmark?.region.orEmpty())?.index ?: 0
        if (state.layoutInfo.visibleItemsInfo.none { it.key == rows[index].first }) state.scrollToItem(index)
        prepared = true
    }
    RestorePageFocus(model, ready = prepared)
    LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(top = layout.contentTop), contentPadding = PaddingValues(bottom = 45.dp)) {
        item(key = "hero") { CinemaHero(model.focused ?: model.featured.firstOrNull(), model, belowFocus = firstRail.takeIf { hasRail }, reserveNavigation = true) }
        if (history.isNotEmpty()) item(key = "continue") { ContentRail("继续观看", history, model, continueWatching = true, entryRequester = firstRail) }
        if (model.featured.isNotEmpty()) item(key = "movies") { ContentRail("为你精选", model.featured, model, entryRequester = firstRail.takeIf { history.isEmpty() }) }
        if (model.tvShows.isNotEmpty()) item(key = "series") { ContentRail("值得追的好故事", model.tvShows, model, portrait = true, entryRequester = firstRail.takeIf { history.isEmpty() && model.featured.isEmpty() }) }
        if (model.featured.size > 6) item(key = "more") { ContentRail("更多电影", model.featured.drop(6), model, portrait = true) }
        if (model.catalogAttribution.isNotBlank()) item(key = "attribution") {
            Text(model.catalogAttribution, color = TvColors.Secondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 16.dp))
        }
    }
}
