package dev.cinematv.ui.douban

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Android does not promise onDestroy. Clear interrupted WebView authentication on next launch too. */
object DoubanWebSessionCleaner {
    private val startupLock = Mutex()
    private var startupComplete = false
    suspend fun startup() = startupLock.withLock {
        if (!startupComplete) { clear(); startupComplete = true }
    }
    suspend fun clear() = withContext(Dispatchers.Main) {
        WebStorage.getInstance().deleteAllData()
        suspendCancellableCoroutine<Unit> { continuation ->
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies {
                cookies.flush()
                if(continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
