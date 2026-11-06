package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.CinemaViewModel
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.tv.components.*
import dev.cinematv.ui.tv.focus.*
import dev.cinematv.ui.tv.design.LocalTvLayout

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun HomeScreen(model: CinemaViewModel) {
    val history = model.history()
    val discovery = model.discovery
    val discoveryTitle = "发现 · 搜索「${discovery.query}」"
    val state = rememberTvListState(model, "page")
    val firstRail = remember { FocusRequester() }
    val layout = LocalTvLayout.current
    val itemBringIntoViewSpec = LocalBringIntoViewSpec.current
    val hasLocalRails = history.isNotEmpty() || model.featured.isNotEmpty() || model.tvShows.isNotEmpty()
    val hasRail = hasLocalRails || discovery.items.isNotEmpty()
    val bookmark = remember { model.focusCoordinator.last(model.focusRoute) }
    var prepared by remember { mutableStateOf(false) }
    LaunchedEffect(history.size, model.featured.size, model.tvShows.size, discovery.items.size) {
        withFrameNanos { }
        val rows = buildList {
            add("hero" to "hero")
            if (history.isNotEmpty()) add("continue" to "rail:继续观看")
            if (model.featured.isNotEmpty()) add("movies" to "rail:为你精选")
            if (model.tvShows.isNotEmpty()) add("series" to "rail:值得追的好故事")
            if (discovery.items.isNotEmpty()) add("discover" to "rail:$discoveryTitle")
            if (model.featured.size > 6) add("more" to "rail:更多电影")
        }
        // A new continue rail can move the bookmarked row. If the whole region
        // disappeared, compose the hero so the page fallback is still usable.
        val index = resolveLazyFocusTarget(rows.map { it.second }, bookmark?.region.orEmpty())?.index ?: 0
        if (state.layoutInfo.visibleItemsInfo.none { it.key == rows[index].first }) state.scrollToItem(index)
        prepared = true
    }
    RestorePageFocus(model, ready = prepared)
    CompositionLocalProvider(LocalBringIntoViewSpec provides RailViewportBringIntoViewSpec) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(top = layout.contentTop), contentPadding = PaddingValues(bottom = 45.dp)) {
            item(key = "hero") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    CinemaHero(model.focused ?: model.featured.firstOrNull() ?: discovery.items.firstOrNull(),
                        model, belowFocus = firstRail.takeIf { hasRail }, reserveNavigation = true)
                }
            }
            if (history.isNotEmpty()) item(key = "continue") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    ContentRail("继续观看", history, model, continueWatching = true, entryRequester = firstRail)
                }
            }
            if (model.featured.isNotEmpty()) item(key = "movies") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    ContentRail("为你精选", model.featured, model, entryRequester = firstRail.takeIf { history.isEmpty() })
                }
            }
            if (model.tvShows.isNotEmpty()) item(key = "series") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    ContentRail("值得追的好故事", model.tvShows, model, portrait = true, entryRequester = firstRail.takeIf { history.isEmpty() && model.featured.isEmpty() })
                }
            }
            if (discovery.items.isNotEmpty()) item(key = "discover") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    Column {
                        ContentRail(discoveryTitle, discovery.items, model, portrait = true,
                            entryRequester = firstRail.takeIf { !hasLocalRails })
                        Row(Modifier.padding(start = layout.gutter, top = 2.dp)) {
                            GlassAction(if (discovery.loading) "正在搜索…" else "换一批",
                                Modifier.width(132.dp).height(40.dp)) { model.rotateDiscovery() }
                        }
                    }
                }
            }
            if (discovery.items.isEmpty() && discovery.loading) item(key = "discover-loading") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    Text("正在为你搜索「${discovery.query}」…", color = TvColors.Secondary, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = layout.gutter, vertical = 12.dp))
                }
            }
            if (model.featured.size > 6) item(key = "more") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    ContentRail("更多电影", model.featured.drop(6), model, portrait = true)
                }
            }
            if (model.catalogAttribution.isNotBlank()) item(key = "attribution") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    Text(model.catalogAttribution, color = TvColors.Secondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 16.dp))
                }
            }
        }
    }
}
