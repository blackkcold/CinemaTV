package dev.cinematv.data.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.cinematv.BuildConfig
import dev.cinematv.data.update.UpdatePolicy.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Isolated from media clients: no source cookies, tokens, interceptors or public-storage downloads. */
class UpdateRepository(private val context: Context) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build()
    private val preferences = context.getSharedPreferences("cinematv_updates", Context.MODE_PRIVATE)
    val selectedChannel: UpdateChannel get() = runCatching {
        UpdateChannel.parse(preferences.getString("channel", BuildConfig.UPDATE_CHANNEL)!!)
    }.getOrDefault(UpdateChannel.DEV)
    fun select(channel: UpdateChannel) { preferences.edit().putString("channel", channel.id).apply() }
    val updatesEnabled: Boolean get() = BuildConfig.UPDATES_ENABLED && !BuildConfig.DEBUG &&
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

    suspend fun check(channel: UpdateChannel): UpdateRelease = withContext(Dispatchers.IO) {
        check(updatesEnabled) { "联网更新尚未启用；需使用经验证的固定签名发行版" }
        val cert = installedCertificate()
        val publicKey = CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()).publicKey
        val envelope = request(UpdatePolicy.feedUrl(channel)).use { response ->
            check(response.code == 200) { if (response.code == 404) "该渠道尚未发布签名更新" else "更新服务暂时不可用" }
            val body = checkNotNull(response.body) { "更新清单为空" }
            require(body.contentLength() <= UpdatePolicy.MAX_FEED_BYTES) { "更新清单过大" }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= UpdatePolicy.MAX_FEED_BYTES) { "更新清单过大" }
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        }
        val release = UpdateManifest.decode(envelope, publicKey)
        validate(release, channel)
        val highest = preferences.getLong("highest_${channel.id}", 0)
        require(release.versionCode >= highest) { "已拒绝过时的更新清单" }
        preferences.edit().putLong("highest_${channel.id}", release.versionCode).apply()
        release
    }

    suspend fun download(release: UpdateRelease, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        check(updatesEnabled)
        validate(release, release.channel)
        require(UpdatePolicy.canUpgrade(BuildConfig.VERSION_CODE.toLong(), release.versionCode,
            preferences.getLong("highest_${release.channel.id}", 0))) { "不允许降级或安装旧版本" }
        val directory = File(context.cacheDir, "verified-updates").apply { mkdirs() }
        check(directory.isDirectory && directory.usableSpace > release.apkBytes + 16 * 1024 * 1024) { "存储空间不足" }
        val file = File(directory, "${UUID.randomUUID()}.apk")
        try {
            request(release.apkUrl).use { response ->
                check(response.code == 200) { "下载失败，请稍后重试" }
                val body = checkNotNull(response.body)
                require(body.contentLength() == -1L || body.contentLength() == release.apkBytes) { "更新包大小不匹配" }
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var lastProgress = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= release.apkBytes) { "更新包超出预期大小" }
                            output.write(buffer, 0, read)
                            val progress = (100 * total / release.apkBytes).toInt()
                            if (progress != lastProgress) { onProgress(progress); lastProgress = progress }
                        }
                        output.fd.sync()
                    }
                }
            }
            verifyFile(file, release)
            coroutineContext.ensureActive()
            file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    /** Recheck immediately before offering the private file to Android's user-confirmed installer. */
    suspend fun verifyFile(file: File, release: UpdateRelease) = withContext(Dispatchers.IO) {
        check(updatesEnabled)
        validate(release, release.channel)
        require(release.versionCode > BuildConfig.VERSION_CODE) { "不允许降级" }
        require(file.canonicalFile.parentFile == File(context.cacheDir, "verified-updates").canonicalFile &&
            file.isFile && file.length() == release.apkBytes) { "更新包缺失或大小不匹配，请重新下载" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        require(digest.digest().toHex() == release.sha256) { "更新包 SHA-256 校验失败" }
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags() or PackageManager.GET_META_DATA)
        requireNotNull(archive) { "更新包无法解析" }
        val app = requireNotNull(archive.applicationInfo) { "更新包缺少应用信息" }
        require(archive.packageName == context.packageName && versionCode(archive) == release.versionCode &&
            archive.versionName == release.versionName && app.minSdkVersion == release.minSdk &&
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
            app.metaData?.getString("dev.cinematv.UPDATE_CHANNEL") == release.channel.id) { "更新包身份或渠道不匹配" }
        val certificates = certificates(archive)
        require(certificates.size == 1 && MessageDigest.isEqual(certificates.single(), installedCertificate())) {
            "更新包签名与已安装版本不同，无法保留数据升级"
        }
        // PackageManager supplies signer identity; Android's package installer verifies the actual APK signature.
        // The signed manifest's SHA-256 already binds every downloaded byte to the installed certificate's key.
    }

    private fun validate(release: UpdateRelease, channel: UpdateChannel) =
        UpdatePolicy.requireValid(release, channel, context.packageName, Build.VERSION.SDK_INT, System.currentTimeMillis() / 1000)

    @Suppress("DEPRECATION")
    private fun installedCertificate(): ByteArray {
        val info = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
        val signatures = certificates(info)
        require(signatures.size == 1) { "当前签名配置不支持联网更新" }
        return signatures.single()
    }

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): List<ByteArray> = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
    } else info.signatures?.map { it.toByteArray() }.orEmpty()

    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private suspend fun request(initialUrl: String): Response {
        var url = initialUrl
        repeat(6) { redirects ->
            coroutineContext.ensureActive()
            require(UpdatePolicy.isAllowedTransport(url)) { "更新下载地址不受信任" }
            val response = client.newCall(Request.Builder().url(url).header("Accept", "application/octet-stream")
                .header("User-Agent", "CinemaTV-Updater/1").build()).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val next = response.use { it.header("Location")?.let(response.request.url::resolve)?.toString() }
            check(redirects < 5 && next != null) { "更新服务重定向异常" }
            url = next
        }
        error("更新服务重定向异常")
    }
}
