package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cinematv.CinemaViewModel
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.shared.LibraryProgressReview
import dev.cinematv.ui.shared.LibraryProgressReviews
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.design.LocalTvLayout
import dev.cinematv.ui.tv.focus.LocalContentFocus
import dev.cinematv.ui.typography.CinemaTvText as Text
import java.text.DateFormat
import java.util.Date

/** Current source records can be opened for inspection; archived checkpoints remain unchanged. */
internal fun LazyListScope.libraryProgressReviewItems(reviews: List<LibraryProgressReview>, onOpen: (MediaItem) -> Unit) {
    item(key = "legacy-review-heading") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("待核对的观看记录", color = TvColors.Text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("library-progress-review"))
            Text("${reviews.size} 条旧记录已保留。打开对应作品核对当前片单，再选择要看的剧集；这些旧进度不会自动套用到其他剧集。",
                color = TvColors.Secondary, fontSize = 13.sp, lineHeight = 21.sp)
        }
    }
    if (reviews.isEmpty()) item(key = "legacy-review-empty") {
        Text("当前没有需要核对的观看记录。", color = TvColors.Secondary, fontSize = 15.sp)
    }
    items(reviews, key = { it.id }) { record ->
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(record.item?.title?.take(180) ?: "来源记录暂不可用", color = TvColors.Text, fontSize = 18.sp)
            record.item?.source?.name?.takeIf(String::isNotBlank)?.let { Text(it.take(100), color = TvColors.Secondary, fontSize = 12.sp) }
            Text(record.reasonText, color = TvColors.Secondary, fontSize = 13.sp, lineHeight = 21.sp)
            Text("原片单第 ${record.oldEpisodeIndex.coerceAtLeast(0) + 1} 项 · 已观看 ${record.positionMs.coerceAtLeast(0) / 60_000} 分钟" +
                if (record.durationMs > 0) " / ${record.durationMs / 60_000} 分钟" else "",
                color = TvColors.Secondary, fontSize = 12.sp)
            if (record.updatedAt > 0) Text("记录时间：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.updatedAt))}",
                color = TvColors.Secondary, fontSize = 12.sp)
            record.item?.let { item ->
                GlassAction("打开当前片单", Modifier.width(170.dp).height(40.dp).testTag("library-review-open:${record.id}")) { onOpen(item) }
            }
        }
    }
}

@Composable
internal fun LibraryProgressReviewDialog(model: CinemaViewModel, onDismiss: () -> Unit) {
    val close = remember { FocusRequester() }
    val reviews = remember(model.libraryVersion) { LibraryProgressReviews.project(model.repo.library.snapshot) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CinemaGlassSurface(Modifier.fillMaxWidth(.86f).fillMaxHeight(.88f), radius = 26.dp) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassAction("返回资料库", Modifier.focusRequester(close).width(155.dp).height(40.dp), onClick = onDismiss)
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    libraryProgressReviewItems(reviews) { item -> onDismiss(); model.open(item) }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; close.requestFocus() }
    }
}

@Composable
fun LibraryProgressReviewScreen(model: CinemaViewModel, onBack: () -> Unit) {
    val reviews = remember(model.libraryVersion) { LibraryProgressReviews.project(model.repo.library.snapshot) }
    val entry = LocalContentFocus.current
    val layout = LocalTvLayout.current
    Column(Modifier.fillMaxSize().padding(horizontal = layout.gutter, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("资料管理", color = TvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        GlassAction("返回资料库", Modifier.focusRequester(entry).width(155.dp).height(40.dp)) { onBack() }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            libraryProgressReviewItems(reviews, model::open)
        }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { entry.requestFocus() } }
}
