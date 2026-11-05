package dev.cinematv.ui.recommendation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val licenseFiles = listOf("THIRD-PARTY.txt", "MPL-2.0.txt", "Apache-2.0.txt")
private val sourceFiles = listOf("core", "novdiv", "diversity").map { "sources/RankSys-$it-0.4.3-sources.jar" }

/** Only public library sources/notices are exported. No profile, title, URL, account or history data. */
@Composable
fun RecommendationLicensesDialog(tv: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val close = remember { FocusRequester() }
    var text by remember { mutableStateOf("正在读取随 APK 附带的开源声明…") }
    var notice by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = requireNotNull(context.contentResolver.openOutputStream(uri))
                    ZipOutputStream(stream).use { zip ->
                        (licenseFiles + sourceFiles).forEach { name ->
                            zip.putNextEntry(ZipEntry(name))
                            context.assets.open("recommendation-third-party/$name").use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                notice = "已导出开源源码和声明；不包含任何个人数据。"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "源码导出失败，请重新选择可写入的位置。源码仍完整保存在 APK 内。" }
        }
    }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            licenseFiles.joinToString("\n\n") { name ->
                context.assets.open("recommendation-third-party/$name").bufferedReader().use { it.readText() }
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
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
