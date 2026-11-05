package dev.cinematv.ui.douban

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.cinematv.CinemaViewModel
import dev.cinematv.data.douban.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun DoubanAccountEntry(model: CinemaViewModel, tv: Boolean) {
    var open by remember { mutableStateOf(false) }
    val entry = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if(!open && wasOpen) { withFrameNanos { }; runCatching { entry.requestFocus() } } }
    Button(onClick = { wasOpen = true; open = true }, modifier = Modifier.focusRequester(entry).testTag("douban-account-entry")) { Text("豆瓣个人账号与片单") }
    if(open) DoubanAccountPanel(model, tv) { open = false }
}

@Composable
private fun DoubanAccountPanel(model: CinemaViewModel, tv: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    val service = remember { DoubanAccountService.get(context) }
    val state by service.state.collectAsState()
    val scope = rememberCoroutineScope()
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var lan by remember { mutableStateOf<DoubanLanServer?>(null) }
    var note by remember { mutableStateOf("") }
    var importConfirm by remember { mutableStateOf(false) }
    var disconnectConfirm by remember { mutableStateOf(false) }
    val initial = remember { FocusRequester() }
    val login = { context.startActivity(Intent(context, DoubanLoginActivity::class.java)) }
    LaunchedEffect(Unit) { service.initialize(); runCatching { initial.requestFocus() } }
    DisposableEffect(Unit) { onDispose { syncJob?.cancel(); lan?.stop() } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(if(tv) .84f else .96f).fillMaxHeight(.9f)) {
            LazyColumn(Modifier.fillMaxSize().padding(if(tv) 24.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("豆瓣个人账号", style = MaterialTheme.typography.headlineSmall) }
                item { Text(state.owner?.displayName ?: "尚未登录") }
                item { Text(state.message, modifier = Modifier.testTag("douban-account-status")) }
                item { Text("登录态与片单只留在本机。仅在你点击同步时读取，不修改豆瓣记录。", style = MaterialTheme.typography.bodySmall) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = login, enabled = state.phase != DoubanPhase.SYNCING, modifier = Modifier.focusRequester(initial)) { Text(if(state.owner == null) "官方扫码登录" else "重新登录 / 验证") }
                    OutlinedButton(onClick = dismiss) { Text("返回") }
                } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = state.owner != null && state.phase in listOf(DoubanPhase.CONNECTED, DoubanPhase.ERROR, DoubanPhase.RATE_LIMITED), onClick = { syncJob = scope.launch { service.sync() } }) { Text("同步个人片单") }
                    if(state.phase == DoubanPhase.SYNCING) OutlinedButton(onClick = { syncJob?.cancel() }) { Text("取消同步") }
                    if(state.snapshot?.complete == false && state.phase == DoubanPhase.CONNECTED) OutlinedButton(onClick = { syncJob = scope.launch { service.sync(true) } }) { Text("继续同步") }
                } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        lan?.stop()
                        runCatching { DoubanLanServer.open { Handler(Looper.getMainLooper()).post { login() } } }.onSuccess { lan = it; note = "用同一 Wi-Fi 下的手机扫码打开设置，不是豆瓣登录二维码。" }.onFailure { note = it.message.orEmpty() }
                    }) { Text("手机局域网设置") }
                    if(lan != null) OutlinedButton(onClick = { lan?.stop(); lan = null; note = "手机设置入口已关闭" }) { Text("关闭入口") }
                } }
                if(lan != null) item {
                    val address = lan!!.url
                    val qr = remember(address) { qrBitmap(address) }
                    Image(qr.asImageBitmap(), "手机局域网设置二维码", Modifier.size(if(tv) 180.dp else 220.dp))
                    Text("10 分钟有效 · 仅手机遥控打开官方登录", style = MaterialTheme.typography.bodySmall)
                }
                if(note.isNotBlank()) item { Text(note, modifier = Modifier.testTag("douban-account-note")) }
                state.snapshot?.let { snapshot ->
                    item { Text("${snapshot.entries.size} 条 · ${if(snapshot.complete) "完整片单快照" else "部分片单，尚未完成"}") }
                    item { OutlinedButton(onClick = { syncJob = scope.launch { service.syncScores() } }, enabled = state.phase == DoubanPhase.CONNECTED) { Text("读取豆瓣社区评分（最多 10 部）") } }
                    item { OutlinedButton(onClick = { importConfirm = true }, enabled = state.phase != DoubanPhase.SYNCING) { Text("导入本机片库（保留本机冲突）") } }
                    items(snapshot.entries, key = { "${it.shelf}:${it.subjectId}" }) { item ->
                        Column {
                            Text(item.title)
                            Text("${item.shelf.label} · 我的评分：${item.personalStars?.let { "$it / 5" } ?: "未评"} · ${item.markedDate}", style = MaterialTheme.typography.bodySmall)
                            if(item.communityScore != null) Text("豆瓣社区评分 ${item.communityScore} / 10", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("个人星级不等于豆瓣社区评分。社区评分需单独点击读取，不覆盖其他来源评分或排名。", style = MaterialTheme.typography.bodySmall) }
                if(state.owner != null || state.phase == DoubanPhase.ERROR) item { OutlinedButton(onClick = { disconnectConfirm = true }, enabled = state.phase != DoubanPhase.SYNCING) { Text("断开账号并删除本机会话") } }
            }
        }
    }
    if(importConfirm) AlertDialog(onDismissRequest = { importConfirm = false }, title = { Text("导入本机片库？") },
        text = { Text("按豆瓣作品 ID 匹配。只添加尚未设置的本机观看状态；冲突、播放进度和收藏保持原样。不会写回豆瓣，也不会把个人星级当作社区评分。") },
        confirmButton = { Button(onClick = { importConfirm = false; scope.launch { val (added, conflicts) = model.repo.library.importDouban(state.snapshot?.entries.orEmpty()); note = "已添加 $added 条状态；保留 $conflicts 项冲突。" } }) { Text("导入并保留冲突") } }, dismissButton = { TextButton(onClick = { importConfirm = false }) { Text("取消") } })
    if(disconnectConfirm) AlertDialog(onDismissRequest = { disconnectConfirm = false }, title = { Text("删除本机豆瓣连接？") },
        text = { Text("删除此设备加密的登录态和豆瓣同步缓存。已导入的本机片库记录保留。此操作不注销豆瓣账号，也不撤销其他设备会话。") },
        confirmButton = { Button(onClick = { disconnectConfirm = false; scope.launch { service.disconnect(); runCatching { DoubanWebSessionCleaner.clear() }.onFailure { note = "加密会话已删除；系统 WebView 清理失败，请清除应用数据。" } }; lan?.stop(); lan = null }) { Text("删除本机连接") } }, dismissButton = { TextButton(onClick = { disconnectConfirm = false }) { Text("取消") } })
}

private fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 384, 384)
    return Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(384 * 384) { if(matrix[it % 384, it / 384]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 384, 0, 0, 384, 384)
    }
}
