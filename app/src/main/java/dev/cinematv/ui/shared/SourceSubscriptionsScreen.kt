package dev.cinematv.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.TvInput
import dev.cinematv.ui.mobile.MobileGlassOutlinedButton
import dev.cinematv.ui.tv.design.TvColors

/** All URL inputs and subscription snapshots stay on the native side of the hybrid boundary. */
@Composable
fun SourceSubscriptionsScreen(model: CinemaViewModel, tv: Boolean = true, modifier: Modifier = Modifier,
    onOpenConnection: () -> Unit = { model.navigate(Page.SETTINGS) }) {
    val state = rememberLazyListState()
    val preview = model.sourceSubscriptionPreview
    val busy = model.sourceSubscriptionBusy
    val mainAction = remember { FocusRequester() }
    val confirmAction = remember { FocusRequester() }
    var displayedPreview by remember { mutableStateOf(false) }
    val prefixItems = 1 + if (model.sourceSubscriptionNotice.isNotBlank()) 1 else 0
    val beforePreview = prefixItems + if (!model.authorized) 1 else 0
    LaunchedEffect(preview?.fetchedAt) {
        if (preview != null) {
            displayedPreview = true
            // The confirmation row can be outside the lazy viewport after a long diff/notice.
            state.scrollToItem(if (tv) beforePreview + 1 else beforePreview)
            withFrameNanos { }
            if (tv) runCatching { confirmAction.requestFocus() }
        } else if (displayedPreview) {
            displayedPreview = false
            state.scrollToItem(if (tv) beforePreview + 3 else 0)
            withFrameNanos { }
            if (tv) runCatching { mainAction.requestFocus() }
        }
    }
    val importedUrls = model.sourceSubscriptions.map { it.subscription.url }.toSet()
    val advertised = model.defaultSourceSubscriptions.filterNot { it.url in importedUrls }
    LazyColumn(state = state, modifier = modifier.fillMaxSize().testTag("source-subscriptions-screen"),
        contentPadding = PaddingValues(if (tv) 25.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("片源列表订阅", color = TvColors.Text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("读取 LibreTV 提供的点播与直播列表，预览后导入；刷新时保留已有停用选择。", color = TvColors.Secondary, fontSize = 14.sp)
                Text("每次搜索最多使用前 50 个启用点播源，可在片源设置中调整启用状态。", color = TvColors.Secondary, fontSize = 12.sp)
            }
        }
        if (model.sourceSubscriptionNotice.isNotBlank()) item("notice") {
            Text(model.sourceSubscriptionNotice, Modifier.testTag("subscription-notice"), color = TvColors.Secondary, fontSize = 14.sp)
        }
        if (!model.authorized) item("connection") {
            SubscriptionAction("连接并登录 LibreTV", "subscription-open-connection", tv, onClick = onOpenConnection)
        }
        if (preview != null) {
            item("preview-summary") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("预览：${preview.subscription.displayName}", color = TvColors.Text, fontSize = 20.sp)
                    Text("点播 ${preview.snapshot.sources.size} 个 · 直播 ${preview.snapshot.liveSources.size} 个",
                        Modifier.testTag("subscription-preview-counts"), color = TvColors.Text)
                    val vod = preview.changes.vod
                    val live = preview.changes.live
                    Text("点播：新增 ${vod.added}、更新 ${vod.changed}、移除 ${vod.removed}\n直播：新增 ${live.added}、更新 ${live.changed}、移除 ${live.removed}",
                        Modifier.testTag("subscription-preview-diff"), color = TvColors.Secondary, fontSize = 14.sp)
                    Text(preview.snapshot.stats.description(), Modifier.testTag("subscription-preview-stats"), color = TvColors.Secondary, fontSize = 14.sp)
                    Text("移除仅影响此订阅的片源列表；手动添加的源、其他订阅、收藏和观看进度保持保留。",
                        color = TvColors.Secondary, fontSize = 13.sp)
                }
            }
            item("preview-confirm") {
                SubscriptionAction(if (busy) "正在保存…" else if (preview.previousRevision == null) "确认导入" else "确认更新",
                    "subscription-apply", tv, enabled = !busy, modifier = Modifier.focusRequester(confirmAction),
                    onClick = model::importSourceSubscriptionPreview)
            }
            item("preview-cancel") {
                SubscriptionAction("取消预览", "subscription-cancel", tv, enabled = !busy,
                    onClick = model::dismissSourceSubscriptionPreview)
            }
        }
        item("custom-title") { Text("添加订阅", color = TvColors.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
        item("custom-name") { SubscriptionField("名称（可选）", model.sourceSubscriptionName,
            { model.sourceSubscriptionName = it.take(120) }, "subscription-name", tv) }
        item("custom-url") { SubscriptionField("SourceList / TVBOX 列表 URL", model.sourceSubscriptionAddress,
            { model.sourceSubscriptionAddress = it.take(2_048) }, "subscription-url", tv) }
        item("custom-preview") {
            SubscriptionAction(if (busy) "正在读取…" else "读取并预览", "subscription-preview-custom", tv,
                enabled = !busy && model.authorized && model.sourceSubscriptionAddress.isNotBlank(),
                modifier = Modifier.focusRequester(mainAction)) { model.previewSourceSubscription() }
        }
        if (advertised.isNotEmpty()) item("advertised-title") {
            Text("服务器提供的订阅", color = TvColors.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        items(advertised, key = { "advertised:${it.id}" }) { subscription ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(subscription.displayName, color = TvColors.Text)
                Text("建议每 ${subscription.refreshDays} 天检查更新", color = TvColors.Secondary, fontSize = 13.sp)
                SubscriptionAction("预览订阅", "subscription-preview-${subscription.id}", tv, !busy && model.authorized) {
                    model.previewSourceSubscription(subscription)
                }
            }
        }
        item("saved-title") { Text("已保存订阅（${model.sourceSubscriptions.size}）", color = TvColors.Text,
            fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
        if (model.sourceSubscriptions.isEmpty()) item("saved-empty") {
            Text("尚未导入片源列表。", color = TvColors.Secondary)
        }
        items(model.sourceSubscriptions, key = { "saved:${it.subscription.id}" }) { record ->
            Column(Modifier.testTag("subscription-saved-${record.subscription.id}"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(record.subscription.displayName, color = TvColors.Text, fontWeight = FontWeight.SemiBold)
                Text("点播 ${record.snapshot.sources.size} · 直播 ${record.snapshot.liveSources.size}" +
                    if (record.refreshDue()) " · 建议刷新" else " · 已保存", color = TvColors.Secondary, fontSize = 14.sp)
                Text(record.snapshot.stats.description(), color = TvColors.Secondary, fontSize = 13.sp)
                SubscriptionAction("检查更新并预览", "subscription-refresh-${record.subscription.id}", tv, !busy && model.authorized) {
                    model.previewSourceSubscription(record.subscription)
                }
                SubscriptionAction("停止订阅，保留片源", "subscription-stop-${record.subscription.id}", tv, !busy) {
                    model.stopSourceSubscription(record.subscription.url)
                }
            }
        }
        if (preview != null) {
            item("preview-sources-title") { Text("预览片源明细", color = TvColors.Text, fontSize = 19.sp) }
            items(preview.snapshot.sources, key = { "preview-vod:${it.key}" }) { source ->
                PreviewSourceRow("点播 · ${source.name}", source.url, "subscription-preview-vod-${source.key}", tv)
            }
            items(preview.snapshot.liveSources, key = { "preview-live:${it.key}" }) { source ->
                PreviewSourceRow("直播 · ${source.name}", source.url, "subscription-preview-live-${source.key}", tv)
            }
        }
    }
}

@Composable
private fun PreviewSourceRow(name: String, url: String, tag: String, tv: Boolean) {
    var expanded by remember(url) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Every TV row is a native focus target, so D-pad can scroll through the entire lazy preview.
        SubscriptionAction(name, tag, tv, onClick = { expanded = !expanded })
        Text(url, color = TvColors.Secondary, fontSize = 12.sp, maxLines = if (expanded) 8 else 2,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SourceSubscriptionsDialog(model: CinemaViewModel, tv: Boolean = false, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(TvColors.Background).statusBarsPadding().navigationBarsPadding()
            .testTag("source-subscriptions-dialog")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SubscriptionAction("返回设置", "source-subscriptions-close", tv, onClick = onDismiss)
            }
            SourceSubscriptionsScreen(model, tv, Modifier.weight(1f), onOpenConnection = {
                onDismiss(); model.navigate(Page.SETTINGS)
            })
        }
    }
}

@Composable
private fun SubscriptionField(label: String, value: String, onValueChange: (String) -> Unit, tag: String, tv: Boolean) {
    if (tv) TvInput(value, onValueChange, label, Modifier.fillMaxWidth().height(52.dp).testTag(tag))
    else OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().testTag(tag),
        label = { Text(label) }, singleLine = true)
}

@Composable
private fun SubscriptionAction(label: String, tag: String, tv: Boolean, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val target = modifier.fillMaxWidth().testTag(tag)
    if (tv) GlassAction(label, target.height(43.dp).alpha(if (enabled) 1f else .5f)
        .semantics { if (!enabled) disabled() }, onClick = { if (enabled) onClick() })
    else MobileGlassOutlinedButton(onClick, target.semantics(mergeDescendants = true) { }, enabled = enabled) { Text(label) }
}
