package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.data.LibraryStatus
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.tv.components.TvChip
import dev.cinematv.ui.tv.components.TvChipRow
import dev.cinematv.ui.tv.components.TvEmptyState
import dev.cinematv.ui.tv.components.TvPageHeader
import dev.cinematv.ui.tv.components.ContentRail
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun LibraryScreen(model: CinemaViewModel) {
    val layout = LocalTvLayout.current
    val itemBringIntoViewSpec = LocalBringIntoViewSpec.current
    val entry = LocalContentFocus.current
    val nav = LocalNavigationFocus.current
    val tick = model.libraryVersion
    val history = remember(tick) { model.history() }
    val shelves = remember(tick) { LibraryStatus.entries.associateWith { if (it == LibraryStatus.FAVORITE) model.favorites() else model.libraryByStatus(it) } }
    var filter by rememberSaveable { mutableStateOf("all") }
    var showClearHistoryConfirmation by remember { mutableStateOf(false) }
    var showProgressReview by remember { mutableStateOf(false) }
    var reviewWasOpened by remember { mutableStateOf(false) }
    val reviewEntry = rememberFocusTarget(model, "actions", "review-progress")
    // Cancel returns to the trigger; clearing removes it, so restore the All filter instead.
    var restoreClearHistoryTrigger by remember { mutableStateOf<Boolean?>(null) }
    val clearHistory = rememberFocusTarget(model, "actions", "clear-history")
    val state = rememberTvListState(model, "page")
    val filterState = rememberTvListState(model, "filters")
    val scope = rememberCoroutineScope()
    RestorePageFocus(model)
    LaunchedEffect(restoreClearHistoryTrigger, history.isNotEmpty()) {
        val restoreTrigger = restoreClearHistoryTrigger ?: return@LaunchedEffect
        val target = if (restoreTrigger && history.isNotEmpty()) clearHistory else entry
        state.scrollToItem(if (target == clearHistory) 0 else 1)
        if (target == entry) filterState.scrollToItem(0)
        withFrameNanos { }; withFrameNanos { }
        if (!runCatching { target.requestFocus() }.getOrDefault(false)) {
            runCatching { entry.requestFocus() }
        }
        restoreClearHistoryTrigger = null
    }
    LaunchedEffect(showProgressReview) {
        if (!showProgressReview && reviewWasOpened && model.page == Page.LIBRARY) { withFrameNanos { }; runCatching { reviewEntry.requestFocus() } }
    }
    if (showProgressReview) LibraryProgressReviewDialog(model) { showProgressReview = false }
    CompositionLocalProvider(LocalBringIntoViewSpec provides RailViewportBringIntoViewSpec) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(top = layout.contentTop), contentPadding = PaddingValues(bottom = 42.dp)) {
            item(key = "heading") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    Column(Modifier.padding(horizontal = layout.gutter)) {
                        TvPageHeader("你的观影时光。", "把喜欢的故事留下，把未完的故事继续。")
                        Spacer(Modifier.height(12.dp))
                        GlassAction("管理观看记录", Modifier.focusRequester(reviewEntry).width(170.dp).height(40.dp),
                            onFocus = { model.focusCoordinator.remember(model.focusRoute, "actions", "review-progress") }) {
                            reviewWasOpened = true; showProgressReview = true
                        }
                        if (model.quarantinedProgressCount > 0) {
                            Spacer(Modifier.height(10.dp))
                            Text("${model.quarantinedProgressCount} 条续播进度无法确认对应剧集，已保留但不会自动续播。",
                                color = TvColors.Secondary, fontSize = 12.sp)
                        }
                        if (history.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            GlassAction("清空全部观看历史", Modifier.focusRequester(clearHistory).focusProperties { up = nav }.width(184.dp).height(40.dp),
                                onFocus = { model.focusCoordinator.remember(model.focusRoute, "actions", "clear-history") }) {
                                showClearHistoryConfirmation = true
                            }
                        }
                    }
                }
            }
            item(key = "filters") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    TvChipRow(state = filterState,
                        contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 20.dp)) {
                        item {
                            TvChip("全部", modifier = Modifier.focusRequester(entry).focusProperties { up = nav }
                                .onPreviewKeyEvent { event ->
                                    if (history.isNotEmpty() && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                        // The heading may have left lazy composition; attach it before moving focus.
                                        scope.launch {
                                            state.scrollToItem(0)
                                            withFrameNanos { }; withFrameNanos { }
                                            runCatching { clearHistory.requestFocus() }
                                        }
                                        true
                                    } else false
                                }.width(80.dp), selected = filter == "all") { filter = "all" }
                        }
                        item { TvChip("继续观看", modifier = Modifier.width(105.dp), selected = filter == "history") { filter = "history" } }
                        LibraryStatus.entries.forEach { status ->
                            item(key = status.name) { TvChip(status.title, modifier = Modifier.width(80.dp), selected = filter == status.name) { filter = status.name } }
                        }
                    }
                }
            }
            if ((filter == "all" || filter == "history") && history.isNotEmpty()) item(key = "history") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    ContentRail("继续观看", history, model, continueWatching = true)
                }
            }
            LibraryStatus.entries.filter { filter == "all" || filter == it.name }.forEach { status ->
                val contents = shelves[status].orEmpty()
                if (contents.isNotEmpty()) item(key = status.name) {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                        ContentRail(status.title, contents, model, portrait = true)
                    }
                }
            }
            val isEmpty = when (filter) {
                "all" -> history.isEmpty() && shelves.values.all { it.isEmpty() }
                "history" -> history.isEmpty()
                else -> shelves.entries.firstOrNull { it.key.name == filter }?.value.isNullOrEmpty()
            }
            if (isEmpty) item(key = "empty") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides itemBringIntoViewSpec) {
                    TvEmptyState(
                        title = "这里还没有故事。",
                        detail = "在作品详情页添加，或开始观看后回来继续。",
                        modifier = Modifier.padding(horizontal = layout.gutter, vertical = 24.dp),
                        action = { TvChip("去发现") { model.navigate(Page.EXPLORE) } },
                    )
                }
            }
        }
    }
    if (showClearHistoryConfirmation) {
        LibraryCleanupConfirmationDialog(
            title = "清空全部观看历史？",
            description = "这将永久删除本机的全部观看历史及各集续播进度，无法撤销。收藏和片库状态会保留。",
            confirmLabel = "永久清空",
            onDismiss = {
                showClearHistoryConfirmation = false
                restoreClearHistoryTrigger = true
            },
            onConfirm = {
                showClearHistoryConfirmation = false
                restoreClearHistoryTrigger = false
                model.clearWatchHistory()
            }
        )
    }
}
