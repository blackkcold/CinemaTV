package dev.cinematv.ui.update

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.cinematv.BuildConfig
import dev.cinematv.data.update.UpdateChannel
import dev.cinematv.data.update.UpdatePolicy
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.mobile.MobileGlassOutlinedButton
import dev.cinematv.ui.typography.CinemaText
import dev.cinematv.ui.tv.design.TvColors

/** Shared state, native focusable TV and touch controls. No automatic network/install action. */
@Composable
fun UpdateSettings(tv: Boolean, model: UpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.onForeground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().testTag("update-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CinemaText("应用更新", color = TvColors.Text, fontSize = 22.sp)
        CinemaText("已安装 ${BuildConfig.VERSION_NAME} · ${BuildConfig.UPDATE_CHANNEL} · ${BuildConfig.VERSION_CODE}",
            color = TvColors.Secondary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UpdateChannel.entries.forEach { channel ->
                UpdateButton((if (model.channel == channel) "✓ " else "") + channel.title, tv,
                    "update-channel-${channel.id}", modifier = if (tv) Modifier else Modifier.weight(1f)) { model.select(channel) }
            }
        }
        CinemaText("测试版使用紫色图标。同包名切换保留数据，但只安装版本号更高且签名相同的包。",
            color = TvColors.Secondary, fontSize = 13.sp)
        if (!model.enabled) CinemaText("当前构建暂未启用联网更新，需要完成固定签名与发布验证。",
            color = TvColors.Secondary, fontSize = 13.sp, modifier = Modifier.testTag("update-unavailable"))
        CinemaText(model.message, color = TvColors.Text, fontSize = 14.sp, modifier = Modifier.testTag("update-status"))
        if (model.enabled && !model.busy) UpdateButton("检查更新", tv, "update-check", action = model::check)
        if (model.busy) UpdateButton("取消", tv, "update-cancel", action = model::cancel)
        model.release?.let { release ->
            CinemaText("${release.versionName} · ${release.apkBytes / (1024 * 1024)} MB\n${release.notes}",
                color = TvColors.Secondary, fontSize = 13.sp)
            if (!model.busy && !model.awaitingSystem && UpdatePolicy.canUpgrade(BuildConfig.VERSION_CODE.toLong(), release.versionCode)) {
                if (model.ready) UpdateButton("安装已校验更新", tv, "update-install") { model.install(context) }
                else UpdateButton("下载并校验", tv, "update-download", action = model::download)
            }
        }
    }
}

@Composable
private fun UpdateButton(label: String, tv: Boolean, tag: String, modifier: Modifier = Modifier, action: () -> Unit) {
    if (tv) GlassAction(label, modifier.testTag(tag).widthIn(min = 140.dp).height(44.dp), onClick = action)
    else MobileGlassOutlinedButton(onClick = action, modifier = modifier.testTag(tag),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        CinemaText(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
