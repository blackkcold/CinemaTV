package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.CinemaViewModel
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.focus.rememberFocusTarget
import dev.cinematv.ui.typography.CinemaTvText as Text

@Composable
fun CatalogPagingControls(model: CinemaViewModel, series: Boolean) {
    val state = model.browseState(series)
    if (state.description().isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${if (series) "剧集" else "电影"}：${state.description()}", color = TvColors.Secondary, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val target = rememberFocusTarget(model, "catalog-paging", "more:$series")
            // Keep the trigger mounted while loading so D-pad focus does not jump.
            if (state.nextOffset != null || state.pages > 0) GlassAction(if (state.loading) "加载中…" else if (state.nextOffset != null) "加载更多" else "本次加载结束", Modifier.focusRequester(target).width(125.dp).height(36.dp),
                onFocus = { model.focusCoordinator.remember(model.focusRoute, "catalog-paging", "more:$series") }) { model.loadMoreCatalog(series) }
            if (!state.loading) GlassAction("刷新目录", Modifier.width(125.dp).height(36.dp)) { model.refreshCatalog() }
        }
    }
}

@Composable
fun SearchPagingControl(model: CinemaViewModel) {
    if (model.searchFilters.doubanTopOnly || (!model.canLoadSearchCatalog && !model.searchCatalogLoading && !model.hasSearchCatalog)) return
    val target = rememberFocusTarget(model, "search-paging", "more")
    GlassAction(if (model.searchCatalogLoading) "加载目录中…" else if (model.canLoadSearchCatalog) "加载更多目录" else "本次加载结束", Modifier.focusRequester(target).width(160.dp).height(36.dp),
        onFocus = { model.focusCoordinator.remember(model.focusRoute, "search-paging", "more") }) { model.loadMoreSearchCatalog() }
}
