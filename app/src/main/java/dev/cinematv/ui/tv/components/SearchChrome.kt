package dev.cinematv.ui.tv.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cinematv.data.SearchFilters
import dev.cinematv.data.SearchRatingMode
import dev.cinematv.data.ratingMode
import dev.cinematv.data.summary
import dev.cinematv.data.withRatingMode
import dev.cinematv.data.withType
import dev.cinematv.data.withYear
import dev.cinematv.ui.tv.design.LocalTvLayout
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.design.TvSpace
import dev.cinematv.ui.tv.design.TvText
import dev.cinematv.ui.tv.design.TvType

/**
 * The one-line header shown while results are being browsed.
 *
 * The navigation is already absent (the root reads the same state), so this row is the
 * first reachable target above the grid: Up expands the page and returns to the
 * navigation, OK opens the filter panel, Down goes back to the last result.
 */
@Composable
fun SearchBrowseHeader(
    title: String,
    filtersSummary: String,
    filterCount: Int,
    summaryRequester: FocusRequester,
    onFocusSummary: () -> Unit,
    onExpand: () -> Unit,
    onOpenFilters: () -> Unit,
    onReturnToResults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (filterCount > 0) {
        val conditions = listOf("筛选 $filterCount 项", filtersSummary)
            .filter(String::isNotBlank).joinToString(" · ")
        conditions
    } else "筛选"
    Row(modifier.fillMaxWidth().testTag("search-browse-header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpace.md)) {
        TvText(title, style = TvType.caption, color = TvColors.Secondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        GlassAction(label, Modifier.widthIn(min = 160.dp, max = 460.dp)
                .focusRequester(summaryRequester)
                .onFocusChanged { if (it.isFocused) onFocusSummary() }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        // The navigation is hidden here; Up is the only way back to it.
                        Key.DirectionUp -> { onExpand(); true }
                        // Leave Down to the page so it can prepare the grid target first.
                        Key.DirectionDown -> { onReturnToResults(); true }
                        else -> false
                    }
                },
            selected = filterCount > 0,
            onClick = onOpenFilters)
    }
}

/**
 * The filter panel. It is a Basic-glass dialog in its own window: Back closes it without
 * touching the route, every group is single-choice, and choosing the current value again
 * returns that dimension to "全部". Clearing filters never clears the query text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchFilterDialog(
    filters: SearchFilters,
    years: List<String>,
    tags: List<String>,
    onFilters: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    val layout = LocalTvLayout.current
    val firstChip = remember { FocusRequester() }
    // A dialog is its own window; its content composes after the caller, so ask from
    // inside and retry across frames like TvConfirmDialog does.
    LaunchedEffect(firstChip) {
        var attempts = 0
        while (attempts < 6) {
            if (runCatching { firstChip.requestFocus() }.isSuccess) break
            withFrameNanos { }
            attempts++
        }
    }
    val selectedRating = filters.ratingMode
    // "仅适用时": the curated public tag list is the only applicability source. An empty
    // list (no public channel) hides the group instead of showing an empty one.
    val showTags = tags.isNotEmpty()
    TvDialogFrame(onDismissRequest = onDismiss, maxWidth = 760.dp) {
        TvText("筛选结果", style = TvType.h2, color = TvColors.Text)
        TvText(filters.summary().ifBlank { "当前显示全部内容" }, style = TvType.caption,
            color = TvColors.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.fillMaxWidth().heightIn(max = layout.height * .5f)
                .verticalScroll(rememberScrollState()).focusGroup().testTag("search-filter-panel"),
            verticalArrangement = Arrangement.spacedBy(TvSpace.md)) {
            FilterGroup("类型") {
                FilterChip("全部", filters.isTv == null, Modifier.focusRequester(firstChip)) {
                    onFilters(filters.withType(null))
                }
                FilterChip("电影", filters.isTv == false) { onFilters(filters.withType(false)) }
                FilterChip("剧集", filters.isTv == true) { onFilters(filters.withType(true)) }
            }
            FilterGroup("年份") {
                FilterChip("全部年份", filters.year == null) { onFilters(filters.withYear(null)) }
                years.forEach { year ->
                    // Choosing the selected year again clears it, like every other group.
                    FilterChip(year, filters.year == year) {
                        onFilters(filters.withYear(if (filters.year == year) null else year))
                    }
                }
            }
            FilterGroup("榜单与评分") {
                FilterChip("全部评分", selectedRating == SearchRatingMode.Any) {
                    onFilters(filters.withRatingMode(SearchRatingMode.Any))
                }
                FilterChip("TMDB ≥ 8", selectedRating == SearchRatingMode.TmdbAtLeast8) {
                    onFilters(filters.withRatingMode(if (selectedRating == SearchRatingMode.TmdbAtLeast8)
                        SearchRatingMode.Any else SearchRatingMode.TmdbAtLeast8))
                }
                FilterChip("豆瓣 ≥ 8", selectedRating == SearchRatingMode.DoubanAtLeast8) {
                    onFilters(filters.withRatingMode(if (selectedRating == SearchRatingMode.DoubanAtLeast8)
                        SearchRatingMode.Any else SearchRatingMode.DoubanAtLeast8))
                }
                FilterChip("豆瓣 TOP250", selectedRating == SearchRatingMode.DoubanTop250) {
                    onFilters(filters.withRatingMode(if (selectedRating == SearchRatingMode.DoubanTop250)
                        SearchRatingMode.Any else SearchRatingMode.DoubanTop250))
                }
            }
            if (showTags) FilterGroup("豆瓣标签") {
                // "全部标签" is this group's neutral choice: selected when no tag is active,
                // and never selected next to a selected tag chip. It clears only an active
                // tag, so choosing it cannot wipe a separate ranking/score filter.
                FilterChip("全部标签", selected = filters.doubanTag == null) {
                    if (filters.doubanTag != null) onFilters(filters.withRatingMode(SearchRatingMode.Any))
                }
                tags.forEach { tag ->
                    FilterChip("标签：$tag", filters.doubanTag == tag) {
                        onFilters(if (filters.doubanTag == tag) filters.withRatingMode(SearchRatingMode.Any)
                            else filters.withRatingMode(SearchRatingMode.DoubanTag, tag))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpace.sm, Alignment.End)) {
            GlassAction("清空筛选", enabled = filters.isTv != null || filters.year != null ||
                selectedRating != SearchRatingMode.Any) {
                // Query text lives in the field, not in SearchFilters; this cannot clear it.
                onFilters(SearchFilters())
            }
            GlassAction("完成", prominent = true, onClick = onDismiss)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TvSpace.xs)) {
        TvText(label, style = TvType.label, color = TvColors.Text)
        FlowRow(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TvSpace.xs),
            verticalArrangement = Arrangement.spacedBy(TvSpace.xs)) {
            content()
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TvChip(label, selected = selected, enabled = enabled,
        modifier = modifier.widthIn(min = 84.dp), onClick = onClick)
}
