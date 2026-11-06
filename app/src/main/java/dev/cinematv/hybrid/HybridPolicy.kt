package dev.cinematv.hybrid

/** Installation compatibility and browser capability are deliberately separate. */
object HybridPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val ENTRY_PATH = "/assets/tv-web/index.html"
    const val ENTRY_URL = ORIGIN + ENTRY_PATH
    const val MIN_WEBVIEW_MAJOR = 111
    const val MIN_HYBRID_API = 29

    data class Decision(val enabled: Boolean, val reason: String)

    fun evaluate(api: Int, providerVersion: String?, hasMessageListener: Boolean): Decision = when {
        api < MIN_HYBRID_API -> Decision(false, "当前系统使用兼容界面；混合界面需要 Android 10 或以上。")
        providerVersion.isNullOrBlank() -> Decision(false, "设备没有可用的系统 WebView。")
        (providerVersion.substringBefore('.').toIntOrNull() ?: 0) < MIN_WEBVIEW_MAJOR ->
            Decision(false, "请更新系统 WebView 后使用混合界面。")
        !hasMessageListener -> Decision(false, "当前 WebView 不支持受限消息接口。")
        else -> Decision(true, "")
    }
}
