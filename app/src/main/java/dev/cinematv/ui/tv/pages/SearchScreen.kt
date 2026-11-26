package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.FocusCoordinator
import dev.cinematv.data.SearchFilters
import dev.cinematv.data.SearchRatingMode
import dev.cinematv.data.activeFilterCount
import dev.cinematv.data.doubanchart.DoubanPublicCatalog
import dev.cinematv.data.ratingMode
import dev.cinematv.data.summary
import dev.cinematv.data.withRatingMode
import dev.cinematv.data.withType
import dev.cinematv.ui.shared.searchMetadataLine
import dev.cinematv.ui.tv.LocalTvSearchChrome
import dev.cinematv.ui.tv.SearchChromeRegions
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.tv.components.TvChip
import dev.cinematv.ui.tv.components.TvEmptyState
import dev.cinematv.ui.tv.components.TvErrorState
import dev.cinematv.ui.tv.components.TvChipRow
import dev.cinematv.ui.tv.components.TvField
import dev.cinematv.ui.tv.components.SearchPagingControl
import dev.cinematv.ui.tv.components.FocusMediaCard
import dev.cinematv.ui.tv.components.SearchBrowseHeader
import dev.cinematv.ui.tv.components.SearchFilterDialog
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * SEARCH has three display states, all owned by the page except the navigation switch:
 *
 * - expanded: the full header and the root navigation (also the safe state for IME, an
 *   empty result set, an error, or a fresh entry);
 * - browsing: the navigation is absent (the root reads [LocalTvSearchChrome]), the header
 *   is one summary line and the grid owns the reclaimed space;
 * - filter panel: a Basic-glass dialog over the current results; Back closes only it.
 *
 * The D-pad ladder is explicit: first result row Up → summary, summary Up → expanded +
 * navigation, summary OK → filter panel, summary Down → last result.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SearchScreen(model: CinemaViewModel) {
    val layout = LocalTvLayout.current
    val entry = LocalContentFocus.current
    val navigation = LocalNavigationFocus.current
    val navigationOwner = LocalNavigationFocusOwner.current
    val registry = LocalTvFocusRegistry.current
    val chrome = LocalTvSearchChrome.current
    val route = model.focusRoute
    val imeVisible = WindowInsets.isImeVisible
    val filters = model.searchFilters
    val ratingMode = filters.ratingMode
    val filterCount = filters.activeFilterCount
    val filtersSummary = filters.summary()

    val saved = remember(route) { model.focusCoordinator.scroll(route, "results") }
    val grid = rememberLazyGridState(saved.index, saved.offset)
    DisposableEffect(grid) {
        onDispose {
            model.focusCoordinator.saveScroll(route, "results",
                grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
        }
    }
    val hits = model.searchHits
    // The grid is keyed by the stable display-group key, never the represented member: a group's
    // representative may change while streaming, and the focused row must not.
    val resultKeys = remember(hits) { hits.map { it.displayKey } }
    RegisterLazyFocusGridRegion(model, "search-grid", grid, resultKeys)

    val input = rememberFocusTarget(model, "search", "input")
    val filterEntryTarget = rememberFocusTarget(model, "search", "filter")
    val summaryTarget = rememberFocusTarget(model, SearchChromeRegions.CHROME, SearchChromeRegions.SUMMARY)
    val scope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<Job?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var filterOpened by remember { mutableStateOf(false) }
    // Aggregate focus of the page content (navigation excluded). Used only to notice that
    // some other target took focus while a delayed navigation request was pending.
    var pageHasFocus by remember { mutableStateOf(false) }

    /**
     * A delayed request must lose to any newer user choice.
     *
     * [TvNavigationFocusOwner] counts every content acquisition (cards, chips, actions),
     * so a different target taken during the two-frame wait cancels this request instead
     * of being stolen back. Route/browsing/dialog conditions stay the caller's job.
     */
    fun requestAfterFrames(requester: FocusRequester, isCurrent: () -> Boolean = { true }) {
        focusJob?.cancel()
        val owner = navigationOwner
        val contentEpoch = owner?.contentAcquisition
        focusJob = scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            if (owner != null && contentEpoch != null && owner.contentAcquisition != contentEpoch) return@launch
            if (isCurrent()) runCatching { requester.requestFocus() }
        }
    }

    /** Enter Browsing from a key the user pressed: the navigation may then disappear. */
    fun showSummary() {
        if (hits.isEmpty()) return
        chrome.browsing = true
        requestAfterFrames(summaryTarget) { chrome.browsing && route == model.focusRoute }
    }

    /** Summary Up: expand first, wait for the navigation to exist again, then focus it. */
    fun restoreNavigation() {
        chrome.browsing = false
        navigationOwner?.update(false)
        focusJob?.cancel()
        focusJob = scope.launch {
            var attempts = 0
            // The removed summary leaves the page briefly unfocused. Only a target taken
            // after that gap is a newer user choice; the stale focus of a disappearing
            // node must not make us give up on the navigation.
            var pageWentEmpty = false
            while (attempts < 12) {
                withFrameNanos { }
                if (chrome.browsing || filterOpen || route != model.focusRoute) return@launch
                if (navigationOwner?.available == true) {
                    runCatching { navigation.requestFocus() }
                    withFrameNanos { }
                    if (navigationOwner.hasFocus) return@launch
                    if (!pageHasFocus) pageWentEmpty = true
                    // Content took focus after the empty gap (for example the user pressed
                    // Down): keeping the retry would steal that choice.
                    else if (pageWentEmpty) return@launch
                }
                attempts++
            }
            // The navigation never became available: keep the page reachable instead of
            // leaving an empty focus tree, but never over a target the user just chose.
            if (!chrome.browsing && !filterOpen && route == model.focusRoute && !pageHasFocus) {
                runCatching { entry.requestFocus() }
            }
        }
    }

    /** Summary Down: restore the last result card, never the first result blindly. */
    fun restoreResults() {
        chrome.browsing = true
        val bookmark = model.focusCoordinator.last(route)?.takeIf { it.region == "search-grid" }
            ?: model.focusCoordinator.restore(route, "search-grid")
                ?.let { FocusCoordinator.Bookmark("search-grid", it, 0) }
            ?: resultKeys.firstOrNull()?.let { FocusCoordinator.Bookmark("search-grid", it, 0) }
            ?: return
        focusJob?.cancel()
        focusJob = scope.launch {
            val isCurrent = { chrome.browsing && !filterOpen && route == model.focusRoute }
            val outcome = registry.restore(route, bookmark, isCurrent = isCurrent)
            if (outcome == FocusRestoreResult.Missing && resultKeys.isNotEmpty()) {
                registry.restore(route,
                    FocusCoordinator.Bookmark("search-grid", resultKeys.first(), 0),
                    isCurrent = isCurrent)
            }
        }
    }

    fun openFilters() {
        filterOpened = true
        filterOpen = true
    }

    /**
     * The adaptive grid's real column count, read from the current layout.
     *
     * Every item laid out in one grid row shares the same `offset.y`, and all columns fit
     * horizontally (the grid never scrolls sideways), so the largest same-`offset.y` group
     * is the column count. A partially filled last row can only make this estimate
     * smaller, and an underestimate can never turn a later row into row 0; it can only
     * make the classification more conservative. Deriving the row from the topmost
     * *visible* row is wrong: that row is the viewport top, not the data's first row.
     */
    fun gridColumns(): Int {
        val visible = grid.layoutInfo.visibleItemsInfo
        return visible.groupBy { it.offset.y }.maxOfOrNull { it.value.size }
            ?.coerceAtLeast(1) ?: 1
    }

    fun isFirstResultRow(index: Int): Boolean {
        if (index !in hits.indices) return false
        // Row 0 is decided by the dataset position, never by which row happens to be at
        // the top of the viewport: after a deep scroll that row is not index 0.
        return index < gridColumns()
    }

    /** Whether a row exists below this one; also uses the global column count. */
    fun hasResultsBelow(index: Int): Boolean {
        if (index !in hits.indices) return false
        val columns = gridColumns()
        return hits.size > (index / columns + 1) * columns
    }

    RestorePageFocus(model)
    val searchBookmark = remember(route) { model.focusCoordinator.last(route) }
    var initialSearchFocusDone by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route, chrome.browsing) {
        // A no-bookmark entry lands on the query field; Browsing never owns that field.
        if (!initialSearchFocusDone && !chrome.browsing &&
            (searchBookmark == null || (searchBookmark.region == "search" && searchBookmark.itemKey == "input"))) {
            withFrameNanos { }; withFrameNanos { }
            runCatching { entry.requestFocus() }
            initialSearchFocusDone = true
        }
    }

    // Returned from DETAIL/PLAYER while Browsing: the grid bookmark is restored by
    // RestorePageFocus. A missing/foreign bookmark must still leave a reachable target.
    LaunchedEffect(route, chrome.browsing) {
        if (!chrome.browsing || filterOpen) return@LaunchedEffect
        val bookmark = model.focusCoordinator.last(route)
        if (bookmark == null || bookmark.region != "search-grid") {
            requestAfterFrames(summaryTarget) {
                chrome.browsing && route == model.focusRoute && !filterOpen
            }
        }
    }

    // A new query or a filter that actually changed is a new result set: show its top and
    // give it a reachable focus. Streaming increments keep the generation and never reset.
    var shownGeneration by rememberSaveable { mutableStateOf(model.searchPresentationGeneration) }
    var pendingGeneration by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(model.searchPresentationGeneration) {
        val generation = model.searchPresentationGeneration
        if (generation == shownGeneration) return@LaunchedEffect
        pendingGeneration = generation
        focusJob?.cancel()
        grid.scrollToItem(0)
    }
    LaunchedEffect(pendingGeneration, hits.size, model.busy, model.chartLoading,
        model.searchError, filterOpen, imeVisible) {
        val generation = pendingGeneration ?: return@LaunchedEffect
        if (generation != model.searchPresentationGeneration) return@LaunchedEffect
        // The dialog or the IME owns focus; the scroll reset above already ran.
        if (filterOpen || imeVisible) return@LaunchedEffect
        // A later user choice must win: any content acquisition after this point
        // invalidates the pending "focus the new result set" request.
        val owner = navigationOwner
        val contentEpoch = owner?.contentAcquisition
        val isCurrent = {
            generation == model.searchPresentationGeneration && route == model.focusRoute && !filterOpen &&
                (owner == null || contentEpoch == null || owner.contentAcquisition == contentEpoch)
        }
        if (resultKeys.isNotEmpty()) {
            val outcome = registry.restore(route,
                FocusCoordinator.Bookmark("search-grid", resultKeys.first(), 0),
                isCurrent = isCurrent)
            if (outcome != FocusRestoreResult.Aborted) {
                shownGeneration = generation
                pendingGeneration = null
            }
        } else if (!model.busy && !model.chartLoading) {
            shownGeneration = generation
            pendingGeneration = null
            // Empty or failed: a filter entry is the reachable next step.
            requestAfterFrames(filterEntryTarget) { generation == model.searchPresentationGeneration }
        }
    }

    // Results stopped being browsable (emptied, failed, or the IME came back). Expand first,
    // then place focus on a node that still exists.
    val resultsSettledEmpty = hits.isEmpty() && !model.busy && !model.chartLoading
    LaunchedEffect(chrome.browsing, filterOpen, imeVisible, resultsSettledEmpty, model.searchError) {
        if (!chrome.browsing || filterOpen) return@LaunchedEffect
        if (!imeVisible && !resultsSettledEmpty && model.searchError.isBlank()) return@LaunchedEffect
        chrome.browsing = false
        if (imeVisible) return@LaunchedEffect
        requestAfterFrames(filterEntryTarget) { !chrome.browsing && route == model.focusRoute }
    }

    // Closing the filter panel returns focus to the entry that opened it. A filter change
    // already scrolled the grid to its top; do not let the generation effect steal focus.
    LaunchedEffect(filterOpen) {
        if (filterOpen || !filterOpened) return@LaunchedEffect
        shownGeneration = model.searchPresentationGeneration
        pendingGeneration = null
        val owner = navigationOwner
        val contentEpoch = owner?.contentAcquisition
        focusJob?.cancel()
        focusJob = scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            // A key that reached the page after the dialog closed owns the newer target.
            if (owner != null && contentEpoch != null && owner.contentAcquisition != contentEpoch) return@launch
            val target = if (chrome.browsing) summaryTarget else filterEntryTarget
            runCatching { target.requestFocus() }
        }
    }

    // A streaming rebuild may merge or split groups, changing a display key. Migrate the current
    // bookmark through the mapping before the grid's key list changes under the focused row.
    LaunchedEffect(model.searchGroupRemap, route) {
        val remap = model.searchGroupRemap
        if (remap.isEmpty()) return@LaunchedEffect
        val bookmark = model.focusCoordinator.last(route) ?: return@LaunchedEffect
        if (bookmark.region != "search-grid") return@LaunchedEffect
        var key = bookmark.itemKey
        var hops = 0
        while (hops++ < 8) {
            val next = remap[key] ?: break
            if (next == key) break
            key = next
        }
        // The mapped key must still be present; otherwise fall back to the old position,
        // clamped to the new size. Never to the navigation or another route.
        if (resultKeys.isEmpty()) return@LaunchedEffect
        if (key !in resultKeys) {
            val fallback = resultKeys.getOrNull(bookmark.position.coerceIn(0, resultKeys.lastIndex))
                ?: return@LaunchedEffect
            key = fallback
        }
        if (key == bookmark.itemKey) return@LaunchedEffect
        model.focusCoordinator.remember(route, "search-grid", key, bookmark.position)
        // A filter change is a new result set: it already scheduled its own landing (results to
        // the top, then the first result or the filter entry). That request owns where focus
        // lands, so this migration keeps the bookmark correct but must neither cancel nor
        // replace it -- otherwise a filter switch would silently restore a deep old row instead
        // of honouring the reset the user asked for.
        if (pendingGeneration != null || focusJob?.isActive == true) return@LaunchedEffect
        // The old row was disposed with its focused key, so the page now has no focus. Restore
        // the migrated group only in that gap: a newer user choice (dialog, IME, navigation,
        // another card) always wins.
        val owner = navigationOwner
        val contentEpoch = owner?.contentAcquisition
        focusJob?.cancel()
        focusJob = scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            val isCurrent = {
                route == model.focusRoute && !filterOpen && !imeVisible && !pageHasFocus &&
                    owner?.hasFocus != true &&
                    (owner == null || contentEpoch == null || owner.contentAcquisition == contentEpoch)
            }
            if (!isCurrent()) return@launch
            registry.restore(route, FocusCoordinator.Bookmark("search-grid", key, bookmark.position),
                isCurrent = isCurrent)
        }
    }

    val doubanActive = ratingMode == SearchRatingMode.DoubanAtLeast8 ||
        ratingMode == SearchRatingMode.DoubanTop250 || ratingMode == SearchRatingMode.DoubanTag
    val browseTitle = buildString {
        append(if (model.query.isBlank()) "搜索结果" else "搜索「${model.query}」")
        if (hits.isNotEmpty()) append(" · ${hits.size} 部作品")
    }

    Column(Modifier.fillMaxSize()
        .padding(top = if (chrome.browsing) TvSpace.lg else layout.contentTop)
        .onFocusChanged { pageHasFocus = it.hasFocus }
        .testTag(if (chrome.browsing) "tv-page-search-browsing" else "tv-page-search")) {
        if (chrome.browsing) {
            Column(Modifier.padding(horizontal = layout.gutter)) {
                SearchBrowseHeader(
                    title = browseTitle,
                    filtersSummary = filtersSummary,
                    filterCount = filterCount,
                    summaryRequester = summaryTarget,
                    onFocusSummary = {
                        model.focusCoordinator.remember(route, SearchChromeRegions.CHROME, SearchChromeRegions.SUMMARY)
                    },
                    onExpand = { restoreNavigation() },
                    onOpenFilters = { openFilters() },
                    onReturnToResults = { restoreResults() })
            }
            Spacer(Modifier.height(TvSpace.xs))
        } else {
            Column(Modifier.padding(horizontal = layout.gutter)) {
                Text("想看的，在这里。", color = TvColors.Text, fontSize = 32.sp)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvField(model.query, { model.query = it }, "片名、别名或演员", Modifier.weight(1f),
                        tag = "search-field", onSubmit = { if (model.query.isNotBlank()) model.search() },
                        shellModifier = Modifier.focusRequester(entry).focusRequester(input)
                            .onFocusChanged { if (it.hasFocus) model.focusCoordinator.remember(model.focusRoute, "search", "input") }
                            .focusProperties { up = navigation })
                    GlassAction("搜索", Modifier.width(110.dp).height(52.dp), prominent = true,
                        enabled = model.query.isNotBlank(),
                        onClick = { if (model.query.isNotBlank()) model.search() })
                }
            }
            if (model.searchHistory.isNotEmpty()) TvChipRow(
                contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 15.dp)) {
                items(model.searchHistory, key = { it }) { term ->
                    TvChip(term, modifier = Modifier.widthIn(min = 90.dp, max = 200.dp)) {
                        model.query = term
                        if (model.query.isNotBlank()) model.search()
                    }
                }
            } else Spacer(Modifier.height(18.dp))
            TvChipRow(contentPadding = PaddingValues(horizontal = layout.gutter)) {
                item {
                    TvChip("全部类型", selected = filters.isTv == null, modifier = Modifier.width(104.dp)) {
                        model.updateSearchFilters(filters.withType(null))
                    }
                }
                item {
                    TvChip("电影", selected = filters.isTv == false, modifier = Modifier.width(84.dp)) {
                        model.updateSearchFilters(filters.withType(if (filters.isTv == false) null else false))
                    }
                }
                item {
                    TvChip("剧集", selected = filters.isTv == true, modifier = Modifier.width(84.dp)) {
                        model.updateSearchFilters(filters.withType(if (filters.isTv == true) null else true))
                    }
                }
                item {
                    TvChip(filters.year?.let { "年份 $it" } ?: "全部年份",
                        selected = filters.year != null, modifier = Modifier.width(118.dp)) {
                        model.cycleSearchYear()
                    }
                }
                item {
                    GlassAction(if (filterCount > 0) "筛选 $filterCount 项" else "筛选…",
                        Modifier.width(120.dp).height(TvHeight.chip)
                            .focusRequester(filterEntryTarget)
                            .onFocusChanged {
                                if (it.isFocused) model.focusCoordinator.remember(route, "search", "filter")
                            },
                        selected = filterCount > 0,
                        onClick = { openFilters() })
                }
            }
            TvChipRow(contentPadding = PaddingValues(horizontal = layout.gutter)) {
                item {
                    TvChip("全部评分", selected = ratingMode == SearchRatingMode.Any,
                        modifier = Modifier.width(104.dp)) {
                        model.updateSearchFilters(filters.withRatingMode(SearchRatingMode.Any))
                    }
                }
                item {
                    TvChip("TMDB ≥ 8", selected = ratingMode == SearchRatingMode.TmdbAtLeast8,
                        modifier = Modifier.width(120.dp)) {
                        model.updateSearchFilters(filters.withRatingMode(
                            if (ratingMode == SearchRatingMode.TmdbAtLeast8) SearchRatingMode.Any
                            else SearchRatingMode.TmdbAtLeast8))
                    }
                }
                item {
                    TvChip("豆瓣 ≥ 8", selected = ratingMode == SearchRatingMode.DoubanAtLeast8,
                        modifier = Modifier.width(120.dp)) {
                        model.updateSearchFilters(filters.withRatingMode(
                            if (ratingMode == SearchRatingMode.DoubanAtLeast8) SearchRatingMode.Any
                            else SearchRatingMode.DoubanAtLeast8))
                    }
                }
                item {
                    TvChip("豆瓣 TOP250", selected = ratingMode == SearchRatingMode.DoubanTop250,
                        modifier = Modifier.width(145.dp)) {
                        model.updateSearchFilters(filters.withRatingMode(
                            if (ratingMode == SearchRatingMode.DoubanTop250) SearchRatingMode.Any
                            else SearchRatingMode.DoubanTop250))
                    }
                }
            }
            if (doubanActive) Text(
                "豆瓣榜单与评分来自公开页面，个人使用；登录豆瓣账号可读取个人片单。",
                color = TvColors.Secondary, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = layout.gutter, vertical = 5.dp))
            Text(model.searchStage, color = TvColors.Secondary, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = layout.gutter, vertical = 5.dp))
            if (model.catalogStatus.isNotBlank()) Text(model.catalogStatus, color = TvColors.Secondary,
                fontSize = 11.sp, modifier = Modifier.padding(horizontal = layout.gutter, vertical = 4.dp))
            if (model.searchAttribution.isNotBlank()) Text(model.searchAttribution, color = TvColors.Secondary,
                fontSize = 10.sp, modifier = Modifier.padding(horizontal = layout.gutter))
            Text(if (model.busy || model.chartLoading) "正在搜索 · 已找到 ${hits.size} 部作品"
                else if (hits.isNotEmpty()) "找到 ${hits.size} 部作品"
                else "从一个片名，开始一段旅程。",
                color = TvColors.Secondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = layout.gutter, vertical = 6.dp))
            if (hits.isEmpty() && !model.busy && !model.chartLoading) {
                if (model.searchError.isNotBlank()) {
                    TvErrorState("搜索失败", model.searchError,
                        onRetry = { if (model.query.isNotBlank()) model.search() },
                        modifier = Modifier.padding(horizontal = layout.gutter, vertical = 12.dp))
                } else {
                    TvEmptyState(
                        title = if (model.query.isBlank()) "从一个片名开始" else "没有找到结果",
                        detail = if (model.query.isBlank()) "输入片名、别名或演员，按 OK 进入编辑"
                            else "换个关键词，或调整筛选条件",
                        modifier = Modifier.padding(horizontal = layout.gutter, vertical = 12.dp),
                        action = if (model.query.isBlank()) null else {
                            {
                                TvChip(if (filterCount > 0) "清空筛选（$filterCount 项）" else "筛选…") {
                                    // Clearing filters never touches the query text: it lives
                                    // in the field, not in SearchFilters.
                                    if (filterCount > 0) model.updateSearchFilters(SearchFilters())
                                    else openFilters()
                                }
                            }
                        },
                    )
                }
            }
            Box(Modifier.padding(horizontal = layout.gutter)) { SearchPagingControl(model) }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(layout.posterWidth), state = grid,
            modifier = Modifier.fillMaxSize().focusRestorer().testTag("search-results-grid"),
            contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter,
                top = if (chrome.browsing) 0.dp else 15.dp, bottom = 45.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            itemsIndexed(hits, key = { _, hit -> hit.displayKey }) { index, hit ->
                val key = hit.displayKey
                val requester = rememberFocusTarget(model, "search-grid", key)
                Box(Modifier.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionUp -> {
                            // Only the first result row climbs to the summary; every other
                            // row keeps the default row-by-row move.
                            if (!imeVisible && isFirstResultRow(index)) {
                                showSummary()
                                true
                            } else false
                        }
                        Key.DirectionDown -> {
                            // Leaving the first row is what hides the navigation; the key
                            // itself is not consumed, so focus still moves down one row.
                            if (!imeVisible && !chrome.browsing && isFirstResultRow(index) &&
                                hasResultsBelow(index)) chrome.browsing = true
                            false
                        }
                        else -> false
                    }
                }) {
                    FocusMediaCard(hit.presentation,
                        { model.onMediaFocus(hit.presentation, "search-grid", index, focusKey = key) },
                        { model.openSearchGroup(key) }, Modifier.focusRequester(requester), portrait = true,
                        // Group membership is shown as a source count, never as a playability claim.
                        metadataOverride = hit.presentation.searchMetadataLine())
                }
            }
        }
    }

    if (filterOpen) SearchFilterDialog(
        filters = filters,
        years = model.searchYears,
        tags = DoubanPublicCatalog.uiTags,
        onFilters = { model.updateSearchFilters(it) },
        onDismiss = { filterOpen = false })
}
