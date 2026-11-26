package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.CinemaViewModel
import dev.cinematv.FocusCoordinator
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.components.TvEmptyState
import dev.cinematv.Page
import dev.cinematv.isUsable
import dev.cinematv.ConnectionCopy
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.tv.components.*
import dev.cinematv.ui.tv.focus.*
import dev.cinematv.ui.tv.design.LocalTvLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun HomeScreen(model: CinemaViewModel) {
    val history = model.history()
    val discovery = model.discovery
    val discoveryTitle = "发现 · 搜索「${discovery.query}」"
    val state = rememberTvListState(model, "page")
    val firstRail = remember { FocusRequester() }
    val entry = LocalContentFocus.current
    val layout = LocalTvLayout.current
    val itemBringIntoViewSpec = LocalBringIntoViewSpec.current
    val route = model.focusRoute
    val registry = LocalTvFocusRegistry.current
    val navigationDown = LocalTvNavigationDown.current
    val navigationOwner = LocalNavigationFocusOwner.current
    val navHasFocus = rememberUpdatedState(LocalNavigationHasFocus.current)
    val scope = rememberCoroutineScope()
    val emptyTarget = rememberFocusTarget(model, HomeRegions.EMPTY, "primary")
    var verticalJob by remember { mutableStateOf<Job?>(null) }
    // Monotonic request identity: a coroutine that already passed its checks still aborts
    // when a newer request (or a data refresh) superseded it.
    var requestToken by remember { mutableStateOf(0L) }
    val hasLocalRails = history.isNotEmpty() || model.featured.isNotEmpty() || model.tvShows.isNotEmpty()
    val hasRail = hasLocalRails || discovery.items.isNotEmpty()
    val showsEmptyState = !hasRail && !discovery.loading
    // The hero is a manual, independent shelf: available movies first, then series,
    // then the bounded discovery result for a LibreTV-only install. It never auto-advances.
    val heroItems = when {
        model.featured.isNotEmpty() -> model.featured.take(8)
        model.tvShows.isNotEmpty() -> model.tvShows.take(8)
        discovery.items.isNotEmpty() -> discovery.items.take(8)
        else -> emptyList()
    }
    // Only a discovery shelf has unverified media types, and it says so.
    val heroEyebrow = if (model.featured.isEmpty() && model.tvShows.isEmpty() &&
        discovery.items.isNotEmpty()) "发现 · 类型未确认" else ""
    // Down from the hero lands on the first real row, or the empty action when the page
    // has nothing else; never on a status line or an uncomposed node.
    val heroBelow = when {
        hasRail -> firstRail
        showsEmptyState -> emptyTarget
        else -> null
    }
    val historyKeys = history.map { it.presentationKey }
    val featuredKeys = model.featured.map { it.presentationKey }
    val seriesKeys = model.tvShows.map { it.presentationKey }
    val discoveryKeys = discovery.items.map { it.presentationKey }
    val rows = HomeFocusTraversal.rows(historyKeys, featuredKeys, seriesKeys, discoveryKeys,
        heroInteractive = true, emptyInteractive = showsEmptyState)

    /** Read the rows again inside a key handler: composition order is not a contract. */
    fun currentRows(): List<HomeRow> {
        val historyNow = model.history()
        return HomeFocusTraversal.rows(
            historyKeys = historyNow.map { it.presentationKey },
            featuredKeys = model.featured.map { it.presentationKey },
            seriesKeys = model.tvShows.map { it.presentationKey },
            discoveryKeys = model.discovery.items.map { it.presentationKey },
            heroInteractive = true,
            emptyInteractive = historyNow.isEmpty() && model.featured.isEmpty() &&
                model.tvShows.isEmpty() && model.discovery.items.isEmpty() && !model.discovery.loading,
        )
    }

    fun lastKeys(rowsNow: List<HomeRow>): Map<String, String> = rowsNow.mapNotNull { row ->
        model.focusCoordinator.restore(route, row.region)?.let { row.region to it }
    }.toMap()

    fun sameFocus(a: FocusCoordinator.Bookmark?, b: FocusCoordinator.Bookmark?): Boolean =
        a?.region == b?.region && a?.itemKey == b?.itemKey

    /**
     * Guarded last resort for a navigation-initiated entry: reveal the hero, then focus its
     * action. It re-checks ownership before scrolling and before requesting, so a stale
     * entry never pulls the viewport or steals focus.
     */
    suspend fun recoverHomeEntry(isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        state.scrollToItem(0)
        withFrameNanos { }
        withFrameNanos { }
        if (!isCurrent()) return
        runCatching { entry.requestFocus() }
    }

    /**
     * Prepare the parent column, then ask for the stable key. Nothing is requested before
     * the row is composed, and every step aborts when the user, the route, the navigation
     * epoch or the row set changed in the meantime.
     */
    fun requestRowFocus(rowsNow: List<HomeRow>, target: HomeTarget, source: FocusCoordinator.Bookmark?,
                        fromNavigation: Boolean) {
        val rowIndex = rowsNow.indexOfFirst { it.region == target.region }
        if (rowIndex < 0) return
        verticalJob?.cancel()
        val token = ++requestToken
        val row = rowsNow[rowIndex]
        val navOwner = navigationOwner
        // The navigation epoch that asked for this move. Re-entering the navigation, or any
        // other content taking focus, makes this request stale even while the route stays.
        val navAcquisition = navOwner?.acquisition
        val contentAcquisition = navOwner?.contentAcquisition
        var started: Job? = null
        started = scope.launch {
            try {
                fun stillOwnsContent(): Boolean = !(navOwner?.hasFocus ?: navHasFocus.value)
                fun stillOwnsNavigation(): Boolean = navOwner != null && navOwner.available &&
                    navOwner.hasFocus && navOwner.acquisition == navAcquisition &&
                    navOwner.contentAcquisition == contentAcquisition
                fun requestIsCurrent(): Boolean = token == requestToken && route == model.focusRoute &&
                    if (fromNavigation) stillOwnsNavigation() else
                        stillOwnsContent() && sameFocus(model.focusCoordinator.last(route), source)
                if (!requestIsCurrent()) return@launch
                val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == row.key }
                val fullyVisible = info != null &&
                    info.offset >= state.layoutInfo.viewportStartOffset &&
                    info.offset + info.size <= state.layoutInfo.viewportEndOffset
                if (!fullyVisible) state.scrollToItem(rowIndex)
                withFrameNanos { }
                withFrameNanos { }
                // The row must be composed now, and must still exist with the same stable
                // key: a row that vanished while we waited gets no stale request.
                val arrived = state.layoutInfo.visibleItemsInfo.any { it.key == row.key }
                val live = currentRows().firstOrNull { it.region == target.region }
                if (!requestIsCurrent() || !arrived || live == null || target.itemKey !in live.itemKeys) {
                    // Missing is not a jump target: keep the user where they are. Only a
                    // navigation entry falls back to the hero, still under the same guard.
                    if (fromNavigation) recoverHomeEntry { requestIsCurrent() }
                    return@launch
                }
                val result = registry.restore(route,
                    FocusCoordinator.Bookmark(target.region, target.itemKey, target.fallbackIndex),
                    isCurrent = { requestIsCurrent() })
                if (result == FocusRestoreResult.Missing && fromNavigation) {
                    recoverHomeEntry { requestIsCurrent() }
                }
            } finally {
                if (verticalJob === started) verticalJob = null
            }
        }
        verticalJob = started
    }

    /** Returns false at a boundary: the key then keeps its existing owner. */
    fun requestVertical(fromRegion: String, direction: Int): Boolean {
        val rowsNow = currentRows()
        val target = HomeFocusTraversal.verticalTarget(rowsNow, fromRegion, direction, lastKeys(rowsNow))
            ?: return false
        requestRowFocus(rowsNow, target, model.focusCoordinator.last(route), fromNavigation = false)
        return true
    }

    /**
     * Down from the floating navigation: restore the last valid HOME row, else the hero.
     * Returns false when the navigation does not own focus, so the default path stays.
     */
    fun requestHomeEntry(): Boolean {
        val navOwner = navigationOwner
        if (navOwner == null || !navOwner.hasFocus) return false
        val rowsNow = currentRows()
        if (rowsNow.isEmpty()) return false
        val last = model.focusCoordinator.last(route)
        val row = rowsNow.firstOrNull { it.region == last?.region } ?: rowsNow.first()
        val candidate = (if (row.region == last?.region) last?.itemKey else null)
            ?: model.focusCoordinator.restore(route, row.region) ?: row.entryKey
        val itemKey = candidate.takeIf { it in row.itemKeys } ?: row.entryKey
        requestRowFocus(rowsNow,
            HomeTarget(row.key, row.region, itemKey, row.itemKeys.indexOf(itemKey).coerceAtLeast(0)),
            source = null, fromNavigation = true)
        return true
    }

    val relayOwner = remember(model) { Any() }
    val entryRequest = rememberUpdatedState(newValue = { requestHomeEntry() })
    DisposableEffect(navigationDown, relayOwner) {
        navigationDown.setHandler(relayOwner) { entryRequest.value() }
        onDispose { navigationDown.clearHandler(relayOwner) }
    }

    val entryBookmark = remember { model.focusCoordinator.last(model.focusRoute) }
    var prepared by remember { mutableStateOf(false) }
    LaunchedEffect(historyKeys, featuredKeys, seriesKeys, discoveryKeys, showsEmptyState) {
        // Data changes only invalidate an unfinished focus request. They must never move the
        // viewport or pull focus back to the entry bookmark while the user is browsing.
        verticalJob?.cancel()
        if (prepared) return@LaunchedEffect
        withFrameNanos { }
        // Entry preparation happens once: reveal the bookmarked row if this visit started
        // on a known row, otherwise leave the hero as the landing target.
        if (rows.isNotEmpty()) {
            val index = resolveLazyFocusTarget(rows.map { it.region }, entryBookmark?.region.orEmpty())?.index ?: 0
            if (state.layoutInfo.visibleItemsInfo.none { it.key == rows[index].key }) state.scrollToItem(index)
        }
        prepared = true
    }
    RestorePageFocus(model, ready = prepared)
    // The hero is the HOME landing target. RestorePageFocus owns bookmarked rows;
    // this only completes the no-bookmark entry after the hero has composed.
    val homeBookmark = remember(model.focusRoute) { model.focusCoordinator.last(model.focusRoute) }
    var initialHeroFocusDone by remember(model.focusRoute) { mutableStateOf(false) }
    LaunchedEffect(prepared, heroItems.isNotEmpty()) {
        if (!initialHeroFocusDone && prepared && heroItems.isNotEmpty() &&
            (homeBookmark == null || homeBookmark.region == HomeRegions.HERO)) {
            withFrameNanos { }; withFrameNanos { }
            runCatching { entry.requestFocus() }
            initialHeroFocusDone = true
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides RailViewportBringIntoViewSpec) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(top = layout.contentTop).testTag("tv-page-home"), contentPadding = PaddingValues(bottom = 45.dp)) {
            item(key = "hero") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    if (heroItems.isEmpty()) {
                        CinemaHero(null, model, belowFocus = heroBelow)
                    } else {
                        TvHero(heroItems.size, Modifier.height(layout.heroHeight)) { page ->
                            CinemaHero(heroItems[page], model, eyebrow = heroEyebrow, belowFocus = heroBelow)
                        }
                    }
                }
            }
            if (history.isNotEmpty()) item(key = "continue") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    ContentRail("继续观看", history, model, continueWatching = true, entryRequester = firstRail,
                        regionKey = HomeRegions.CONTINUE,
                        onVerticalMove = { requestVertical(HomeRegions.CONTINUE, it) })
                }
            }
            if (model.featured.isNotEmpty()) item(key = "movies") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    ContentRail("为你精选", model.featured, model, portrait = true,
                        entryRequester = firstRail.takeIf { history.isEmpty() },
                        regionKey = HomeRegions.FEATURED,
                        onVerticalMove = { requestVertical(HomeRegions.FEATURED, it) },
                        action = {
                            GlassAction("查看全部", Modifier.height(36.dp)) { model.navigate(Page.MOVIES) }
                        })
                }
            }
            if (model.tvShows.isNotEmpty()) item(key = "series") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    ContentRail("值得追的好故事", model.tvShows, model, portrait = true, entryRequester = firstRail.takeIf { history.isEmpty() && model.featured.isEmpty() },
                        regionKey = HomeRegions.SERIES,
                        onVerticalMove = { requestVertical(HomeRegions.SERIES, it) })
                }
            }
            if (discovery.items.isNotEmpty()) item(key = "discover") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    Column {
                        ContentRail(discoveryTitle, discovery.items, model, portrait = true,
                            entryRequester = firstRail.takeIf { !hasLocalRails },
                            regionKey = HomeRegions.DISCOVERY,
                            onVerticalMove = { requestVertical(HomeRegions.DISCOVERY, it) })
                        Row(Modifier.padding(start = layout.gutter, top = 2.dp)) {
                            GlassAction(if (discovery.loading) "正在搜索…" else "换一批",
                                Modifier.width(132.dp).height(40.dp), enabled = !discovery.loading) { model.rotateDiscovery() }
                        }
                    }
                }
            }
            if (showsEmptyState) item(key = "home-empty") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    val usable = model.connectionState.isUsable
                    TvEmptyState(
                        title = if (usable) "还没有可看的推荐" else "先连接影视服务",
                        detail = ConnectionCopy.summary(model.connectionState) + " · 搜索以发现内容",
                        modifier = Modifier.padding(horizontal = layout.gutter, vertical = 24.dp),
                        action = {
                            GlassAction(if (usable) "开始搜索" else "连接影视服务",
                                Modifier.focusRequester(emptyTarget).width(150.dp).height(44.dp), prominent = true,
                                onFocus = { model.focusCoordinator.remember(route, HomeRegions.EMPTY, "primary") }) {
                                model.navigate(if (usable) Page.SEARCH else Page.SETTINGS)
                            }
                        }
                    )
                }
            }
            if (!hasRail && model.busy && !discovery.loading) item(key = "home-loading") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    Text("正在准备你的影院…", color = TvColors.Secondary, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = layout.gutter, vertical = 12.dp))
                }
            }
            if (discovery.items.isEmpty() && discovery.loading) item(key = "discover-loading") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    Text("正在为你搜索「${discovery.query}」…", color = TvColors.Secondary, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = layout.gutter, vertical = 12.dp))
                }
            }
            if (discovery.message.isNotBlank()) item(key = "discover-status") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    if (discovery.items.isEmpty()) {
                        TvErrorState("推荐暂时不可用", discovery.message,
                            onRetry = { model.rotateDiscovery() },
                            modifier = Modifier.padding(horizontal = layout.gutter, vertical = 16.dp))
                    } else {
                        TvStatusLine(discovery.message,
                            Modifier.padding(horizontal = layout.gutter, vertical = 8.dp), error = true)
                    }
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
