package dev.cinematv.hybrid

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.TvInput
import dev.cinematv.ui.TvPlayerPage
import dev.cinematv.ui.tv.TvRootApp
import dev.cinematv.ui.tv.design.CinemaTvTheme
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.typography.CinemaTvText as Text

/** Web owns presentation, while the existing ViewModel remains the sole business route owner. */
@Composable
fun HybridTvRootApp(model: CinemaViewModel, session: HybridPresentationState = viewModel()) {
    val context = LocalContext.current
    val policy = remember {
        val version = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()
        HybridPolicy.evaluate(Build.VERSION.SDK_INT, version,
            runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false))
    }
    val images = remember(session) { HybridImages(context.applicationContext, session.ids) }
    val effects = remember(images) { HybridDeviceEffects(context.applicationContext, images::trim) }
    val gateway = remember(model, session) { CinemaPresentationGateway(model, session, images, effects) { (context as? Activity)?.finish() } }
    DisposableEffect(gateway, effects) {
        effects.start()
        onDispose { effects.close(); gateway.close() }
    }
    LaunchedEffect(model.page) {
        if (session.nativePanel != null && session.nativePanel != model.page) session.nativePanel = null
        if (model.page != Page.SEARCH) session.searchInput = false
        if (model.page != Page.LIBRARY) session.clearHistoryConfirmation = false
    }

    // The player's own handler owns panel/controls/page order. There is no live WebView behind it.
    if (model.page == Page.PLAYER) {
        CinemaTvTheme(model.reducedMotion, model.glassQuality) { TvPlayerPage(model) }
        return
    }

    if (!policy.enabled || session.nativeFallback) {
        BackHandler(enabled = model.page != Page.HOME) { model.back() }
        Box(Modifier.fillMaxSize().testTag("hybrid-native-fallback")) {
            TvRootApp(model)
            if (session.failure != null && policy.enabled) {
                CinemaTvTheme(true, GlassQuality.BASIC) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                        GlassAction("重试新界面", Modifier.padding(12.dp).width(130.dp).height(40.dp)) { session.retry() }
                    }
                }
            }
        }
        return
    }

    if (session.failure != null) {
        BackHandler { session.nativeFallback = true }
        HybridRecovery(session.failure.orEmpty(), { session.retry() }, { session.nativeFallback = true })
        return
    }

    if (session.nativePanel != null) {
        BackHandler { gateway.closeNativePanel() }
        Column(Modifier.fillMaxSize().background(TvColors.Background).testTag("hybrid-native-panel")) {
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                CinemaTvTheme(true, GlassQuality.BASIC) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassAction("返回新界面", Modifier.width(146.dp).height(40.dp)) { gateway.closeNativePanel() }
                    }
                }
            }
            Box(Modifier.weight(1f)) { TvRootApp(model, settingsInitialSection = session.nativeSettingsSection,
                libraryManagement = session.nativePanel == Page.LIBRARY, onCloseLibraryManagement = gateway::closeNativePanel) }
        }
        return
    }

    BackHandler { gateway.back() }
    HybridWebHost(gateway, Modifier.fillMaxSize(), session::fail, effects::resumed)
    if (session.searchInput) {
        HybridSearchInput(model.query, onDismiss = { session.searchInput = false }, onSearch = {
            session.searchInput = false; gateway.submitSearch(it)
        })
    }
    if (session.clearHistoryConfirmation) {
        HybridClearHistoryDialog(onDismiss = { session.clearHistoryConfirmation = false }, onConfirm = {
            session.clearHistoryConfirmation = false
            model.clearWatchHistory()
        })
    }
}

@Composable
private fun HybridRecovery(reason: String, retry: () -> Unit, native: () -> Unit) {
    val initial = remember { FocusRequester() }
    CinemaTvTheme(true, GlassQuality.BASIC) {
        Column(Modifier.fillMaxSize().background(TvColors.Background).padding(64.dp).testTag("hybrid-recovery"),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("重新打开 CinemaTV", color = TvColors.Text, fontSize = 30.sp)
            Spacer(Modifier.height(14.dp))
            Text(reason, color = TvColors.Secondary, fontSize = 17.sp)
            Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                GlassAction("重新打开界面", Modifier.focusRequester(initial).width(175.dp).height(48.dp), prominent = true, onClick = retry)
                GlassAction("使用兼容界面", Modifier.width(175.dp).height(48.dp), onClick = native)
            }
        }
    }
    LaunchedEffect(Unit) { initial.requestFocus() }
}

@Composable
private fun HybridSearchInput(initial: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    var query by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismiss() { keyboard?.hide(); onDismiss() }
    fun submit() { if (query.isNotBlank()) { keyboard?.hide(); onSearch(query.trim()) } }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CinemaTvTheme(true, GlassQuality.BASIC) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(.8f).background(Color(0xFA161B24), RoundedCornerShape(26.dp))
                    .padding(30.dp).testTag("hybrid-search-input"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("搜索影视", color = TvColors.Text, fontSize = 26.sp)
                    TvInput(query, { query = it.take(120) }, "片名、演员或关键词", Modifier.fillMaxWidth().focusRequester(focus), onSubmit = ::submit)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlassAction("搜索", Modifier.width(135.dp).height(44.dp), prominent = true, onClick = ::submit)
                        GlassAction("取消", Modifier.width(135.dp).height(44.dp), onClick = ::dismiss)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
}

@Composable
private fun HybridClearHistoryDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val cancel = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CinemaTvTheme(true, GlassQuality.BASIC) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(.8f).background(Color(0xFA161B24), RoundedCornerShape(26.dp))
                    .padding(30.dp).testTag("hybrid-history-confirmation"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("清空全部观看历史？", color = TvColors.Text, fontSize = 26.sp)
                    Text("这会移除本机的观看记录和续播进度。收藏与片库状态将保留。", color = TvColors.Secondary, fontSize = 17.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlassAction("取消", Modifier.focusRequester(cancel).width(135.dp).height(44.dp), prominent = true, onClick = onDismiss)
                        GlassAction("确认清空", Modifier.width(135.dp).height(44.dp), onClick = onConfirm)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { cancel.requestFocus() }
}
