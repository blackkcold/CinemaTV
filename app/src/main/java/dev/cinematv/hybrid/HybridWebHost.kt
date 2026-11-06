package dev.cinematv.hybrid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import dev.cinematv.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/** Back is consumed once at the native boundary; only arrows/OK reach the web spatial navigator. */
class CinemaWebView(context: Context, private val back: () -> Unit) : WebView(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            return super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action,
                KeyEvent.KEYCODE_ENTER, event.repeatCount, event.metaState, event.deviceId,
                event.scanCode, event.flags, event.source))
        }
        return super.dispatchKeyEvent(event)
    }
}

private const val CONTENT_POLICY = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
    "img-src 'self'; font-src 'self'; connect-src 'none'; frame-src 'none'; worker-src 'none'; " +
    "object-src 'none'; base-uri 'none'; form-action 'none'; media-src 'none'"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HybridWebHost(gateway: CinemaPresentationGateway, modifier: Modifier = Modifier,
    onFailure: (String) -> Unit, onResumed: () -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val failure by rememberUpdatedState(onFailure)
    val resumed by rememberUpdatedState(onResumed)
    var ready by remember { mutableStateOf(false) }
    val images = remember(gateway) { gateway.images.openSession() }
    val dispatcher = remember(gateway) { HybridBridgeDispatcher(gateway, scope, { ready = true }, { failure(it) }) }
    val web = remember(gateway) {
        var allocated: CinemaWebView? = null
        runCatching {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context.applicationContext))
                .addPathHandler("/images/", images).build()
            CinemaWebView(context, dispatcher::requestBack).also { allocated = it }.apply {
                contentDescription = "CinemaTV 混合界面"
                setBackgroundColor(android.graphics.Color.rgb(5, 8, 13))
                isFocusable = true
                isFocusableInTouchMode = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                    setGeolocationEnabled(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    textZoom = 100
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    blockNetworkLoads = true
                    setSupportZoom(false)
                    safeBrowsingEnabled = true
                }
                WebViewCompat.addWebMessageListener(this, "CinemaNative", setOf(HybridPolicy.ORIGIN), dispatcher)
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                        callback.invoke(origin, false, false)
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.toString() != HybridPolicy.ENTRY_URL
                    @Deprecated("Old provider callback")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = url != HybridPolicy.ENTRY_URL

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url
                        if (request.method != "GET" || uri.scheme != "https" || uri.host != "appassets.androidplatform.net" ||
                            uri.port !in setOf(-1, 443) || uri.userInfo != null || uri.query != null || uri.fragment != null) return HybridImages.denied()
                        val path = uri.path.orEmpty()
                        // AssetsPathHandler is not an API for enumerating other APK assets.
                        val allowed = (path.startsWith("/assets/tv-web/") && path.substringAfterLast('.') in setOf("html", "js", "css", "svg", "png", "webp", "woff", "woff2", "ttf")) ||
                            (path.startsWith("/assets/fonts/") && path.substringAfterLast('.') in setOf("ttf", "ttc", "woff2")) || path.startsWith("/images/")
                        if (!allowed || path.contains("..") || path.contains('\\') || path.contains('\u0000')) return HybridImages.denied()
                        if (request.isForMainFrame && uri.toString() != HybridPolicy.ENTRY_URL) return HybridImages.denied()
                        val result = loader.shouldInterceptRequest(uri) ?: return HybridImages.denied()
                        result.responseHeaders = result.responseHeaders.orEmpty() + mapOf(
                            "Content-Security-Policy" to CONTENT_POLICY, "X-Content-Type-Options" to "nosniff",
                            "Referrer-Policy" to "no-referrer", "Cache-Control" to "no-store")
                        return result
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (url != HybridPolicy.ENTRY_URL) { view.stopLoading(); failure("界面加载来源无效，已停止加载。") }
                        dispatcher.documentStarted()
                        ready = false
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) failure("本地界面未能打开，可以重试或进入兼容界面。")
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) failure("本地界面资源不可用，可以重试或进入兼容界面。")
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        dispatcher.close()
                        failure("界面进程已停止，播放记录已保留。请重新打开界面。")
                        return true
                    }
                }
            }
        }.getOrElse {
            dispatcher.close()
            images.close()
            allocated?.let { failed ->
                runCatching { WebViewCompat.removeWebMessageListener(failed, "CinemaNative") }
                runCatching { failed.stopLoading(); failed.removeAllViews(); failed.destroy() }
            }
            failure("设备暂时无法打开混合界面，可以使用兼容界面。")
            null
        }
    }
    if (web == null) return
    BackHandler { dispatcher.requestBack() }
    AndroidView(factory = { web }, modifier = modifier)

    DisposableEffect(web, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { web.onResume(); dispatcher.visibility(true); resumed() }
                Lifecycle.Event.ON_PAUSE -> { dispatcher.visibility(false); web.onPause() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        web.loadUrl(HybridPolicy.ENTRY_URL)
        web.requestFocus()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            dispatcher.close()
            images.close()
            runCatching { WebViewCompat.removeWebMessageListener(web, "CinemaNative") }
            web.stopLoading()
            web.onPause()
            (web.parent as? ViewGroup)?.removeView(web)
            web.removeAllViews()
            web.destroy()
            gateway.images.trim()
        }
    }
    LaunchedEffect(web, ready) {
        if (!ready) { delay(15_000); if (!dispatcher.ready) failure("界面启动超时，可以重试或使用兼容界面。") }
        else { web.requestFocus(); dispatcher.visibility(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    }
    LaunchedEffect(gateway, dispatcher) {
        snapshotFlow { gateway.payload().toString() }.collect { dispatcher.state(JSONObject(it)) }
    }
}
