package dev.cinematv.ui.douban

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.cinematv.data.douban.DoubanAccountService
import dev.cinematv.data.douban.DoubanPhase
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/** Official login only. Not an application shell, no JS bridge, no password/cookie input field. */
class DoubanLoginActivity : ComponentActivity() {
    private var web: WebView? = null
    private lateinit var status: TextView
    private val service by lazy { dev.cinematv.data.douban.DoubanAccounts.get(this) }
    private var saving = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(17, 21, 29)); setPadding(18, 12, 18, 12) }
        status = TextView(this).apply { setTextColor(Color.WHITE); text = "豆瓣官方登录 · 选择页面右上角的二维码，用豆瓣 App 扫码。完成后点击下方验证。"; textSize = 16f }
        root.addView(status)
        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply { text = "已完成登录，验证并保存"; setOnClickListener { confirmSave() } })
        buttons.addView(Button(this).apply { text = "取消并返回"; setOnClickListener { finish() } })
        root.addView(buttons)
        setContentView(root)
        AlertDialog.Builder(this).setTitle("在这台设备登录豆瓣")
            .setMessage("登录页由豆瓣提供。请用豆瓣 App 扫码确认，不要向 CinemaTV 输入密码。登录完成后，将另行确认是否在本机加密保存会话，用于手动读取你的片单和评分。会话不会传给手机设置页、云端或 LibreTV。")
            .setPositiveButton("打开官方登录") { _, _ -> lifecycleScope.launch {
                runCatching { DoubanWebSessionCleaner.startup(); openOfficial(root) }
                    .onFailure { status.text = "系统 WebView 不可用，无法打开官方登录。请更新系统 WebView 后重试。" }
            } }.setNegativeButton("取消") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun openOfficial(root: LinearLayout) {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.removeAllCookies {
            if(isFinishing) return@removeAllCookies
            web = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                cookies.setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.cancel(); status.text = "证书验证失败，已停止；不要忽略安全警告" }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val u = request?.url ?: return true
                        val allowed = allowedPage(u)
                        if(!allowed) runOnUiThread { status.text = "已阻止离开豆瓣官方登录站点" }
                        return !allowed
                    }
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url ?: return blocked()
                        val host = u.host.orEmpty()
                        return if(u.scheme == "https" && (host == "douban.com" || host.endsWith(".douban.com") || host.endsWith(".doubanio.com"))) null else blocked()
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if(url?.let { Uri.parse(it).host } == "sec.douban.com") status.text = "豆瓣要求官方安全验证，请自行完成；程序不会自动重试或绕过。"
                    }
                }
                root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                loadUrl("https://accounts.douban.com/passport/login")
            }
        }
    }
    private fun confirmSave() {
        if(saving || web == null) return
        AlertDialog.Builder(this).setTitle("允许 CinemaTV 保存本机登录态？")
            .setMessage("这会让此设备在未来 7 天内以你的豆瓣登录态手动读取看过、想看、在看及评分。会话如被他人取得，可能被用于访问你的账号。仅保存在本机 Keystore 加密文件，排除系统备份，不发送给局域网浏览器或云端。你可随时断开并删除。")
            .setPositiveButton("允许本机保存并验证") { _, _ ->
                saving = true
                val local = CookieManager.getInstance().getCookie("https://movie.douban.com").orEmpty()
                lifecycleScope.launch {
                    service.initialize(); service.acceptLocalSession(local)
                    val result = service.state.value
                    status.text = result.message
                    saving = false
                    if(result.phase == DoubanPhase.CONNECTED) finish()
                }
            }.setNegativeButton("暂不保存", null).show()
    }
    override fun onDestroy() {
        web?.apply { stopLoading(); (parent as? ViewGroup)?.removeView(this); clearCache(true); clearHistory(); destroy() }; web = null
        runCatching {
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }
    companion object {
        internal fun allowedPage(u: Uri): Boolean = u.scheme == "https" && u.port in listOf(-1,443) && u.userInfo == null && u.host in setOf("accounts.douban.com", "www.douban.com", "movie.douban.com", "sec.douban.com")
        private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
