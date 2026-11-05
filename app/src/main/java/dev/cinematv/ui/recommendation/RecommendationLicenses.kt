package dev.cinematv.ui.recommendation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import dev.cinematv.ui.tv.focus.trackTvContentFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.cinematv.data.recommendation.recommendationLicenseFiles
import dev.cinematv.data.recommendation.writeRecommendationSourceArchive

/** Only public library sources/notices are exported. No profile, title, URL, account or history data. */
@Composable
fun RecommendationLicensesDialog(tv: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val close = remember { FocusRequester() }
    val licenseScroll = rememberScrollState()
    var notice by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = requireNotNull(context.contentResolver.openOutputStream(uri))
                    writeRecommendationSourceArchive(stream) { name -> context.assets.open(name) }
                }
                notice = "已导出开源源码和声明；不包含任何个人数据。"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "源码导出失败，请重新选择可写入的位置。源码仍完整保存在 APK 内。" }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Keep asynchronous text state/effects in the visible dialog composition.
        // Its window can recompose independently from the obscured settings window.
        val text = rememberLicenseText()
        CinemaGlassSurface(Modifier.fillMaxWidth(if (tv) .85f else .96f).fillMaxHeight(.9f), radius = 24.dp) {
            Column(Modifier.padding(20.dp).testTag("recommendation-licenses"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("推荐 core 开源声明", color = Color.White, fontSize = 22.sp)
                    LicenseAction("关闭", tv, Modifier.focusRequester(close).testTag("recommendation-licenses-close"), onDismiss)
                }
                LicenseAction("导出开源源码 ZIP", tv) {
                    try { export.launch("CinemaTV-RankSys-0.4.3-sources.zip") }
                    catch (_: android.content.ActivityNotFoundException) { notice = "设备没有文件保存器。可从 APK 的 assets/recommendation-third-party/sources/ 提取源码包。" }
                }
                if (notice.isNotEmpty()) Text(notice, color = Color.LightGray, fontSize = 14.sp)
                Text(text, color = Color.LightGray, fontSize = if (tv) 16.sp else 13.sp,
                    modifier = Modifier.weight(1f).verticalScroll(licenseScroll).testTag("recommendation-license-text")
                        .onPreviewKeyEvent { event ->
                            if (!tv || event.type != KeyEventType.KeyDown) false
                            else when {
                                event.key == Key.DirectionDown && licenseScroll.canScrollForward -> {
                                    scope.launch { licenseScroll.animateScrollBy(240f) }; true
                                }
                                event.key == Key.DirectionUp && licenseScroll.canScrollBackward -> {
                                    scope.launch { licenseScroll.animateScrollBy(-240f) }; true
                                }
                                else -> false
                            }
                        }.trackTvContentFocus().focusable(tv))
            }
        }
        if (tv) LaunchedEffect(Unit) { withFrameNanos { }; close.requestFocus() }
    }
}

@Composable
private fun LicenseAction(label: String, tv: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (tv) GlassAction(label, modifier, onClick = onClick)
    else TextButton(onClick = onClick, modifier = modifier) { Text(label, color = Color.White) }
}

@Composable
private fun rememberLicenseText(): String {
    val assets = LocalContext.current.assets
    val text by produceState("正在读取随 APK 附带的开源声明…", assets) {
        try {
            if (dev.cinematv.BuildConfig.DEBUG) android.util.Log.i("RecommendationLicenses", "load-start")
            val loaded = withContext(Dispatchers.IO) {
                recommendationLicenseFiles.joinToString("\n\n") { name ->
                    assets.open("recommendation-third-party/$name").bufferedReader().use { it.readText() }
                }.also {
                    if (dev.cinematv.BuildConfig.DEBUG) android.util.Log.i("RecommendationLicenses", "asset-read-complete chars=${it.length}")
                }
            }
            value = loaded
            if (dev.cinematv.BuildConfig.DEBUG) android.util.Log.i("RecommendationLicenses", "dialog-state-published")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { value = "无法读取此安装包的开源声明，请重新安装经过验证的 CinemaTV APK。" }
    }
    return text
}
