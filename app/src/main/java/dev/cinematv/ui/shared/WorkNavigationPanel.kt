package dev.cinematv.ui.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.CinemaViewModel
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.mobile.MobileGlassOutlinedButton
import dev.cinematv.ui.typography.CinemaText as Text
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.focus.*

/** Same source-scoped controls on touch and remote. Existing glass materials remain unchanged. */
@Composable
fun WorkNavigationPanel(model: CinemaViewModel, tv: Boolean) {
    val navigation = model.workNavigation ?: return
    if (!navigation.visible) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("季与片源", color = TvColors.Text, fontSize = 20.sp)
        val seasonState = rememberTvListState(model, "work-seasons")
        if (tv) RegisterLazyFocusRegion(model, "work-seasons", seasonState, navigation.seasons.map { it.id })
        LazyRow(modifier = Modifier.testTag("work-seasons-rail"), state = seasonState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp)) {
            items(navigation.seasons, key = { it.id }) { season ->
                WorkOption(model, tv, "work-seasons", season.id, season.label,
                    season.id == navigation.selectedSeasonId) {
                    model.selectWorkSource(navigation.workId, season.id)
                }
            }
        }
        val sources = navigation.currentSeason?.sources.orEmpty()
        val sourceState = rememberTvListState(model, "work-sources:${navigation.selectedSeasonId}")
        if (tv) RegisterLazyFocusRegion(model, "work-sources", sourceState, sources.map { it.id })
        LazyRow(modifier = Modifier.testTag("work-sources-rail"), state = sourceState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp)) {
            items(sources, key = { it.id }) { source ->
                WorkOption(model, tv, "work-sources", source.id, source.label,
                    source.id == navigation.selectedSourceId) {
                    model.selectWorkSource(navigation.workId, navigation.selectedSeasonId, source.id)
                }
            }
        }
        Text("收藏、片库状态和观看进度按片源分别保存", color = TvColors.Secondary, fontSize = 12.sp)
    }
}

@Composable
private fun WorkOption(model: CinemaViewModel, tv: Boolean, region: String, id: String,
    label: String, active: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.testTag("$region:$id").semantics { selected = active }
    if (tv) {
        val focus = rememberFocusTarget(model, region, id)
        GlassAction(label, modifier.focusRequester(focus).widthIn(min = 150.dp, max = 330.dp).height(48.dp),
            selected = active, onFocus = { model.focusCoordinator.remember(model.focusRoute, region, id) }, onClick = onClick)
    } else {
        MobileGlassOutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
            Text(if (active) "$label ✓" else label)
        }
    }
}
