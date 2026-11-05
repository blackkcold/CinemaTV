package dev.cinematv.ui.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cinematv.BuildConfig
import dev.cinematv.data.update.UpdateChannel
import dev.cinematv.data.update.UpdatePolicy
import dev.cinematv.data.update.UpdateRelease
import dev.cinematv.data.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository(application)
    var channel by mutableStateOf(repository.selectedChannel)
        private set
    var message by mutableStateOf(if (repository.updatesEnabled) "点击检查更新，仅访问 CinemaTV 官方发布。" else
        "联网更新尚未启用。需完成固定签名、签名更新清单和安装验证后才能开启。")
        private set
    var release by mutableStateOf<UpdateRelease?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var awaitingSystem by mutableStateOf(false)
        private set
    private var file: File? = null
    private var job: Job? = null
    private var generation = 0L
    val enabled get() = repository.updatesEnabled

    fun select(value: UpdateChannel) {
        if (channel == value) return
        cancel()
        channel = value
        repository.select(value)
        release = null
        discardFile()
        message = if (value == UpdateChannel.STABLE)
            "已选择稳定版。不会降级或清除数据；需等待版本号更高的稳定版。" else
            "已选择测试版，可能存在未修复问题。检查、下载和安装均需手动确认。"
    }

    fun check() = runOperation {
        discardFile()
        release = null
        message = "正在检查签名更新清单…"
        val found = repository.check(channel)
        release = found
        message = if (UpdatePolicy.canUpgrade(BuildConfig.VERSION_CODE.toLong(), found.versionCode))
            "发现 ${found.versionName}，可下载并校验。" else if (channel.id != BuildConfig.UPDATE_CHANNEL)
            "已订阅${channel.title}，等待版本号高于当前安装版的更新。不会降级或卸载。" else "当前已是该渠道最新版本。"
    }

    fun download() {
        val selected = release ?: return
        runOperation {
            discardFile()
            message = "正在下载并校验…"
            val epoch = generation
            file = repository.download(selected) { percent ->
                viewModelScope.launch { if (generation == epoch && busy) message = "正在下载 $percent%…" }
            }
            ready = true
            message = "完整性与签名身份已校验。点击安装后仍需在 Android 安装器确认。"
        }
    }

    fun install(context: Context) {
        if (busy || awaitingSystem) return
        val selected = release ?: return
        val downloaded = file ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            try {
                awaitingSystem = true
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                message = "请在系统中允许 CinemaTV 安装应用，返回后再次点击安装；拒绝不会开始安装。"
            } catch (_: ActivityNotFoundException) { awaitingSystem = false; message = "此设备没有应用安装授权页面，请在系统设置手动开启，或使用官方发布包。" }
            catch (_: SecurityException) { awaitingSystem = false; message = "此设备策略禁止安装应用，请联系设备管理员。" }
            return
        }
        runOperation {
            repository.verifyFile(downloaded, selected)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", downloaded)
            try {
                awaitingSystem = true
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                message = "已打开 Android 安装器。请确认安装；取消后可重新尝试。"
            } catch (_: ActivityNotFoundException) { awaitingSystem = false; message = "此设备没有可用的 APK 安装器。" }
            catch (_: SecurityException) { awaitingSystem = false; message = "系统拒绝安装，请检查安装授权和设备策略。" }
        }
    }

    fun onForeground() { awaitingSystem = false }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        busy = false
        message = "已取消，可重新检查或下载。"
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (busy || !enabled) return
        busy = true
        val epoch = ++generation
        job = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Do not expose transport URLs, signed CDN parameters or provider-specific exception text.
                if (epoch == generation) {
                    discardFile()
                    message = "无法完成更新：未发布、网络异常、清单过期或校验未通过。请稍后重试。不会安装未验证的包。"
                }
            } finally { if (epoch == generation) busy = false }
        }
    }

    private fun discardFile() { file?.delete(); file = null; ready = false }
    override fun onCleared() { discardFile(); super.onCleared() }
}
