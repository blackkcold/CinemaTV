package dev.cinematv.ui.douban

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.cinematv.BuildConfig
import dev.cinematv.CinemaViewModel
import dev.cinematv.data.douban.DoubanDeveloperOptions
import kotlinx.coroutines.launch

@Composable
fun DoubanDeveloperOptionsEntry(model: CinemaViewModel, tv: Boolean) {
    if (BuildConfig.UPDATE_CHANNEL != "dev") return
    val options = DoubanDeveloperOptions.get(LocalContext.current)
    val enabled by options.access.enabled.collectAsState()
    val busy by options.busy.collectAsState()
    val notice by options.notice.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    var wasOpen by remember { mutableStateOf(false) }
    val trigger = remember { FocusRequester() }
    LaunchedEffect(Unit) { options.initialize() }
    LaunchedEffect(open) { if (!open && wasOpen) { withFrameNanos { }; runCatching { trigger.requestFocus() } } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountButton(tv, onClick = { wasOpen = true; open = true },
            modifier = Modifier.focusRequester(trigger).testTag("developer-options-entry")) { Text("开发者选项 · Dev") }
        if (enabled) DoubanAccountEntry(model, tv)
    }
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("开发者选项") },
        text = { Column(Modifier.heightIn(max = if (tv) 250.dp else 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("豆瓣个人账号实验 · ${if (enabled) "已开启" else "已关闭（默认）"}", Modifier.testTag("douban-experiment-state"))
            Text("真实账号登录及完整同步尚未完成验证。仅 Dev 提供；开启只显示实验入口，不会自动登录、读取片单或启动局域网服务。")
            Text("关闭时停止请求并删除本机会话和同步缓存，已导入的片库保留。切换到 Stable 前请先关闭并完成清理；换渠道不会自动清理残留。")
            if (notice.isNotBlank()) Text(notice, Modifier.testTag("douban-experiment-notice"))
            if (!enabled) AccountButton(tv, enabled = !busy, outlined = true,
                onClick = { scope.launch { options.disableAndClear() } }) { Text("重试清理残留会话") }
        } }, confirmButton = {
            AccountButton(tv, enabled = !busy, onClick = { confirm = !enabled },
                modifier = Modifier.testTag("douban-experiment-toggle")) { Text(if (enabled) "关闭并清理豆瓣账号" else "开启豆瓣账号实验") }
        }, dismissButton = { AccountButton(tv, enabled = !busy, onClick = { open = false }) { Text("返回") } })
    confirm?.let { enabling ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (enabling) "开启豆瓣账号实验？" else "关闭并清理豆瓣账号？") },
            text = { Text(if (enabling) "这是尚未完成真实账号验收的实验功能。开启不是账号访问授权；官方扫码与保存本机会话仍需分别确认。不会自动发送请求。"
                else "将停止账号请求和局域网入口，删除此设备登录态与同步缓存。已导入片库不删除，不会注销豆瓣账号或撤销其他设备登录。") },
            confirmButton = { AccountButton(tv, onClick = {
                confirm = null
                if (!enabling) options.access.revoke()
                scope.launch { if (enabling) options.enable() else options.disableAndClear() }
            }, modifier = Modifier.testTag("douban-experiment-confirm")) { Text(if (enabling) "开启实验" else "关闭并清理") } },
            dismissButton = { AccountButton(tv, outlined = true, onClick = { confirm = null }) { Text("取消") } })
    }
}
