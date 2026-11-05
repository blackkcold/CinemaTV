package dev.cinematv.data.douban

import android.content.Context
import android.util.AtomicFile
import dev.cinematv.BuildConfig
import dev.cinematv.ui.douban.DoubanWebSessionCleaner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Non-secret, backup-excluded opt-in. Enabling never grants login or starts a request. */
class DoubanDeveloperOptions internal constructor(private val context: Context,
    namespace: String = "douban-experiment", private val channel: String = BuildConfig.UPDATE_CHANNEL,
    private val clearSession: suspend () -> Unit = { DoubanAccounts.clearExisting(context) },
    private val clearWeb: suspend () -> Unit = { DoubanWebSessionCleaner.clear() },
    private val legacyExists: () -> Boolean = { File(context.noBackupFilesDir, "douban-account.v1").exists() || File(context.applicationInfo.dataDir, "app_webview").exists() },
    private val beforeWrite: () -> Unit = {}) {
    init { require(namespace.matches(Regex("[a-z0-9-]{1,40}"))) }
    val access = DoubanFeatureAccess(channel)
    private val file = AtomicFile(File(context.noBackupFilesDir, "$namespace.v1"))
    private val authorityLock = Any()
    private var disableGeneration = 0L
    private val mutex = Mutex()
    private var initialized = false
    private var optedIn = false
    private var cleanupPending = false
    private var webPending = false
    private val webGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableBusy = MutableStateFlow(true)
    val busy: StateFlow<Boolean> = mutableBusy
    private val mutableNotice = MutableStateFlow("")
    val notice: StateFlow<String> = mutableNotice

    suspend fun initialize() = withContext(NonCancellable) { mutex.withLock {
        if (initialized) return@withLock
        initialized = true
        val generation = synchronized(authorityLock) { disableGeneration }
        withContext(Dispatchers.IO) {
            try {
                if (file.baseFile.exists()) {
                    val record = JSONObject(String(file.readFully(), Charsets.UTF_8))
                    check(record.getInt("version") == 1)
                    optedIn = record.getBoolean("enabled")
                    cleanupPending = record.getBoolean("cleanupPending")
                    webPending = record.getBoolean("webPending")
                } else {
                    // Upgrade from the ungated test build never silently enables the experiment.
                    cleanupPending = legacyExists()
                    webPending = cleanupPending
                }
            } catch (_: Exception) { optedIn = false; cleanupPending = true; webPending = true }
            if (cleanupPending || channel != "dev") cleanupLocked()
            else try {
                if (webPending) { clearWeb(); webPending = false; save() }
                synchronized(authorityLock) { if (optedIn && generation == disableGeneration) access.enable() }
            } catch (_: Exception) {
                optedIn = false; cleanupPending = true; access.revoke(); runCatching { save() }
                mutableNotice.value = "实验保持关闭；上次登录页面尚未清理，请重试清理。"
            }
        }
        mutableBusy.value = false
    } }

    suspend fun enable(): Boolean {
      val generation = synchronized(authorityLock) { disableGeneration }
      return mutex.withLock {
        if (!initialized || cleanupPending || channel != "dev") return@withLock false
        withContext(Dispatchers.IO) {
            try {
                optedIn = true; save()
                synchronized(authorityLock) {
                    if (generation != disableGeneration) { optedIn = false; save(); false }
                    else { access.enable(); mutableNotice.value = "实验已开启；尚未授权任何账号访问。"; true }
                }
            } catch (_: Exception) {
                optedIn = false; access.revoke(); mutableNotice.value = "无法保存实验设置，功能保持关闭。"; false
            }
        }
      }
    }

    suspend fun disableAndClear(): Boolean {
        // Revoke synchronously before waiting for an old operation or cleanup lock.
        synchronized(authorityLock) { disableGeneration++; access.revoke() }
        return withContext(NonCancellable) { mutex.withLock {
            mutableBusy.value = true
            val result = withContext(Dispatchers.IO) { cleanupLocked() }
            mutableBusy.value = false
            result
        } }
    }

    /** Must persist the recovery marker before a WebView can create any login cookies. */
    suspend fun beginWebLogin(): Long = mutex.withLock {
        val ticket = access.capture()
        withContext(Dispatchers.IO) { access.guard(ticket) {
            val lease = webGeneration.incrementAndGet()
            webPending = true; save(); lease
        } }
    }

    fun isCurrentWebLogin(lease: Long): Boolean = webGeneration.get() == lease

    suspend fun clearWebLogin(lease: Long? = null) = mutex.withLock {
        if (lease != null && !isCurrentWebLogin(lease)) return@withLock
        if (!webPending) return@withLock
        webGeneration.incrementAndGet() // Invalidate pending WebView callbacks before clearing.
        withContext(NonCancellable) {
            try {
                clearWeb()
                withContext(Dispatchers.IO) { webPending = false; save() }
            } catch (_: Exception) {
                webPending = true
                withContext(Dispatchers.IO) { runCatching { save() } }
                mutableNotice.value = "登录页面清理未完成；下次打开时会重试。"
            }
        }
    }
    fun scheduleWebCleanup(lease: Long) { cleanupScope.launch { clearWebLogin(lease) } }

    private suspend fun cleanupLocked(): Boolean {
        access.revoke(); optedIn = false; cleanupPending = true
        webGeneration.incrementAndGet()
        return try {
            save() // Durable recovery marker precedes destructive cleanup.
            clearSession()
            if (webPending || legacyExists()) clearWeb()
            webPending = false; cleanupPending = false; save()
            mutableNotice.value = "实验已关闭；本机会话与同步缓存已清除，已导入片库保留。"
            true
        } catch (_: Exception) {
            cleanupPending = true; runCatching { save() }
            mutableNotice.value = "本次访问已停止，但关闭状态或会话清理未能完整保存。请重试清理；仍失败时请在系统中清除应用数据，勿认为会话已删除。"
            false
        }
    }

    private fun save() {
        beforeWrite()
        val bytes = JSONObject().put("version", 1).put("enabled", optedIn)
            .put("cleanupPending", cleanupPending).put("webPending", webPending).toString().toByteArray()
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    companion object {
        @Volatile private var instance: DoubanDeveloperOptions? = null
        fun get(context: Context): DoubanDeveloperOptions = instance ?: synchronized(this) {
            instance ?: DoubanDeveloperOptions(context.applicationContext).also { instance = it }
        }
    }
}
