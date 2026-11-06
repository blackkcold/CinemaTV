package dev.cinematv.ui.douban

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cinematv.diagnostics.DiagnosticLevel
import dev.cinematv.diagnostics.DiagnosticLog
import dev.cinematv.diagnostics.DiagnosticWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Dev-only diagnostics controls. Every action is explicit; nothing is uploaded and
 * clearing logs never touches the account vault or the local library.
 */
@Composable
fun DiagnosticsSection(tv: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(DiagnosticLog.enabled) }
    var level by remember { mutableStateOf(DiagnosticLog.level) }
    var stats by remember { mutableStateOf(DiagnosticWriter.Stats(0, 0L, 0L, false)) }
    var cacheBytes by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("") }
    var exportPath by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val entry = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }

    suspend fun refresh() {
        // The toggle label follows the real state even if another surface changed it.
        enabled = DiagnosticLog.enabled
        level = DiagnosticLog.level
        stats = DiagnosticLog.stats()
        cacheBytes = withContext(Dispatchers.IO) {
            File(context.cacheDir, "douban-public").walkTopDown()
                .filter { it.isFile }.sumOf { it.length() }
        }
    }

    LaunchedEffect(open) {
        if (open) {
            while (open) { refresh(); delay(2_000) }
        } else if (wasOpen) {
            withFrameNanos { }
            if (runCatching { entry.requestFocus() }.isFailure) {
                withFrameNanos { }
                runCatching { entry.requestFocus() }
            }
        }
    }

    AccountButton(tv, outlined = true, onClick = { wasOpen = true; open = true },
        modifier = Modifier.focusRequester(entry).testTag("diagnostics-entry")
            .semantics { contentDescription = "诊断日志设置，${summaryText(enabled, level, stats)}" }) {
        DiagnosticsSummary(enabled, level, stats)
    }

    if (!open) return

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("诊断日志") },
        text = {
            // The dialog window is composed here; requesting focus from the parent effect can
            // land before the window exists and leave a remote user with nothing focused.
            // Touch layouts keep their previous look, so only the TV surface takes focus.
            if (tv) LaunchedEffect(Unit) { focusAfterDialogAttaches(panelFocus) }
            Column(Modifier.heightIn(max = if (tv) 300.dp else 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("默认关闭。只记录应用观察到的状态，不含 Cookie、Token、账号标识、片名或原始页面。",
                    style = MaterialTheme.typography.bodySmall)
                DiagnosticsSummary(enabled, level, stats, Modifier
                    .testTag("diagnostics-status")
                    .semantics { liveRegion = LiveRegionMode.Polite })
                Text(detailLine(stats, cacheBytes),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("diagnostics-storage"))
                if (stats.failed) Text("日志目录暂不可写，记录已暂停；释放存储空间后会自动重试，也可关闭再开启立即重试。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

                DiagnosticsLevelRow(
                    tv = tv,
                    enabled = enabled,
                    level = level,
                    onToggle = {
                        DiagnosticLog.setEnabled(!enabled)
                        enabled = DiagnosticLog.enabled
                        level = DiagnosticLog.level
                        scope.launch { refresh() }
                    },
                    onSelectLevel = { target ->
                        DiagnosticLog.setLevel(target)
                        level = DiagnosticLog.level
                    },
                    toggleFocus = panelFocus
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccountButton(tv, enabled = stats.files > 0, onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO + NonCancellable) {
                                DiagnosticLog.export(context)
                            }
                            exportPath = file?.absolutePath
                            status = if (file != null) "已导出：${file.absolutePath}" else "导出失败，请重试"
                            refresh()
                        }
                    }, modifier = Modifier.weight(1f).testTag("diagnostics-export")) { Text("导出日志") }
                    if (!tv && exportPath != null) AccountButton(tv, outlined = true, onClick = {
                        val file = exportPath?.let(::File)
                        if (file == null || !file.isFile) {
                            exportPath = null
                            status = "文件已失效，请重新导出"
                        } else runCatching {
                            context.startActivity(Intent.createChooser(
                                DiagnosticLog.shareIntent(context, file), "分享诊断日志"))
                        }
                    }, modifier = Modifier.weight(1f).testTag("diagnostics-share")) { Text("分享") }
                }

                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("diagnostics-export-status")
                        .semantics { liveRegion = LiveRegionMode.Polite })
                Text("导出目录：Android/data/${context.packageName}/files/diagnostics",
                    style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccountButton(tv, outlined = true, enabled = stats.files > 0,
                        onClick = { confirm = "logs" },
                        modifier = Modifier.weight(1f).testTag("diagnostics-clear-logs")) { Text("清空日志") }
                    AccountButton(tv, outlined = true, enabled = cacheBytes > 0,
                        onClick = { confirm = "cache" },
                        modifier = Modifier.weight(1f).testTag("diagnostics-clear-cache")) { Text("清理缓存") }
                }
            }
        },
        confirmButton = {
            AccountButton(tv, onClick = { open = false },
                modifier = Modifier.testTag("diagnostics-close")) { Text("关闭") }
        }
    )

    confirm?.let { action ->
        val logs = action == "logs"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (logs) "清空诊断日志？" else "清理公开豆瓣缓存？") },
            text = { Column {
                LaunchedEffect(Unit) { focusAfterDialogAttaches(cancelFocus) }
                Text(if (logs) "只删除本机诊断日志文件，不影响账号会话、片库或网络状态。"
                    else "只删除公开榜单/标签的缓存并重置请求冷却；账号会话与片库不受影响。")
            } },
            confirmButton = {
                AccountButton(tv, onClick = {
                    confirm = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (logs) DiagnosticLog.clear()
                            else dev.cinematv.data.douban.public.DoubanPublicChannel.clear(context)
                        }
                        if (logs) exportPath = null
                        status = if (logs) "日志已清空" else "公开缓存已清理"
                        refresh()
                    }
                }, modifier = Modifier.testTag("diagnostics-confirm")) { Text(if (logs) "清空" else "清理") }
            },
            dismissButton = {
                AccountButton(tv, outlined = true, onClick = { confirm = null },
                    modifier = Modifier.focusRequester(cancelFocus).testTag("diagnostics-cancel")) { Text("取消") }
            }
        )
    }
}

/** A dialog-content effect runs inside the new window, which is what makes a focus request stick. */
private suspend fun focusAfterDialogAttaches(requester: FocusRequester) {
    for (attempt in 0 until 4) {
        withFrameNanos { }
        if (runCatching { requester.requestFocus() }.isSuccess) return
    }
}

private fun summaryText(enabled: Boolean, level: DiagnosticLevel,
                        stats: DiagnosticWriter.Stats): String = when {
    !enabled -> "诊断日志 · 已关闭"
    stats.failed -> "诊断日志 · 写入失败"
    else -> "诊断日志 · 记录中 · ${if (level == DiagnosticLevel.VERBOSE) "详细" else "基础"} · ${bytes(stats.bytes)}"
}

/** Dropped records are only mentioned when they exist; a normal session stays quiet. */
private fun detailLine(stats: DiagnosticWriter.Stats, cacheBytes: Long): String = buildString {
    append("日志 ").append(bytes(stats.bytes)).append(" · ").append(stats.files).append(" 个文件")
    if (stats.dropped > 0) append(" · 丢弃 ").append(stats.dropped).append(" 条")
    append(" · 公开缓存 ").append(bytes(cacheBytes))
}

@Composable
private fun DiagnosticsSummary(enabled: Boolean, level: DiagnosticLevel,
                               stats: DiagnosticWriter.Stats, modifier: Modifier = Modifier) {
    Text(
        summaryText(enabled, level, stats),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

private fun bytes(value: Long): String = when {
    value <= 0L -> "0 KB"
    value < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", value / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", value / 1024.0 / 1024.0)
}

/**
 * The three primary controls. Extracted so the narrow-phone, large-font layout is covered by
 * a real layout test at the width this row has inside the dialog on a 360dp screen.
 */
@Composable
internal fun DiagnosticsLevelRow(
    tv: Boolean,
    enabled: Boolean,
    level: DiagnosticLevel,
    onToggle: () -> Unit,
    onSelectLevel: (DiagnosticLevel) -> Unit,
    toggleFocus: FocusRequester? = null
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountButton(tv, onClick = onToggle,
            modifier = Modifier.weight(1f).testTag("diagnostics-toggle")
                .then(if (toggleFocus != null) Modifier.focusRequester(toggleFocus) else Modifier)) {
            Text(if (enabled) "关闭" else "开启")
        }
        AccountButton(tv, outlined = level != DiagnosticLevel.BASIC, enabled = enabled,
            onClick = { onSelectLevel(DiagnosticLevel.BASIC) },
            modifier = Modifier.weight(1f).testTag("diagnostics-level-basic")
                .semantics { selected = level == DiagnosticLevel.BASIC }) { Text("基础") }
        AccountButton(tv, outlined = level != DiagnosticLevel.VERBOSE, enabled = enabled,
            onClick = { onSelectLevel(DiagnosticLevel.VERBOSE) },
            modifier = Modifier.weight(1f).testTag("diagnostics-level-verbose")
                .semantics { selected = level == DiagnosticLevel.VERBOSE }) { Text("详细") }
    }
}
