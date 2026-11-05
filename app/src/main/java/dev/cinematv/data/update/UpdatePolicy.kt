package dev.cinematv.data.update

import java.net.URI
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

enum class UpdateChannel(val id: String, val title: String) {
    STABLE("stable", "稳定版"), DEV("dev", "测试版");
    companion object {
        fun parse(value: String): UpdateChannel = entries.firstOrNull { it.id == value }
            ?: throw IllegalArgumentException("未知更新渠道")
    }
}

data class UpdateRelease(
    val channel: UpdateChannel,
    val versionCode: Long,
    val versionName: String,
    val applicationId: String,
    val minSdk: Int,
    val apkUrl: String,
    val apkBytes: Long,
    val sha256: String,
    val publishedAt: Long,
    val expiresAt: Long,
    val notes: String,
)

/** The installed signing certificate is the trust anchor, never a certificate from the feed. */
object UpdatePolicy {
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MAX_FEED_BYTES = 64 * 1024
    const val MAX_LIFETIME_SECONDS = 31L * 24 * 60 * 60
    const val REPOSITORY = "blackkcold/CinemaTV"
    private val hash = Regex("[0-9a-f]{64}")
    private val assetPath = Regex("/blackkcold/CinemaTV/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.apk")

    fun feedUrl(channel: UpdateChannel) = "https://github.com/$REPOSITORY/releases/download/channel-${channel.id}/update.json"

    fun verifySignature(payload: ByteArray, signature: ByteArray, key: PublicKey): Boolean {
        if (payload.isEmpty() || payload.size > MAX_FEED_BYTES || signature.size !in 1..1024) return false
        val algorithm = when (key.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> return false
        }
        return runCatching {
            Signature.getInstance(algorithm).run {
                initVerify(key); update(payload); verify(signature)
            }
        }.getOrDefault(false)
    }

    fun requireValid(release: UpdateRelease, channel: UpdateChannel, applicationId: String, sdk: Int, now: Long) {
        require(release.channel == channel) { "更新渠道不匹配" }
        require(release.applicationId == applicationId) { "更新包名不匹配" }
        require(release.versionCode in 1..2_100_000_000L) { "无效版本号" }
        require(release.versionName.isNotBlank() && release.versionName.length <= 80) { "无效版本名称" }
        require(release.minSdk in 26..sdk) { "新版本不支持当前 Android 系统" }
        require(release.apkBytes in 1..MAX_APK_BYTES && hash.matches(release.sha256)) { "无效更新完整性信息" }
        require(release.notes.length <= 8_000) { "更新说明过长" }
        require(release.publishedAt > 0 && release.publishedAt <= now + 300 && release.expiresAt > now &&
            release.expiresAt > release.publishedAt && release.expiresAt - release.publishedAt <= MAX_LIFETIME_SECONDS) {
            "更新清单已过期或设备时间不正确"
        }
        val uri = URI(release.apkUrl)
        require(isHttps(uri) && uri.host == "github.com" && uri.rawQuery == null && assetPath.matches(uri.rawPath)) {
            "更新包必须来自 CinemaTV 官方发布页"
        }
    }

    fun canUpgrade(installedCode: Long, releaseCode: Long, highestSeenCode: Long = 0) =
        releaseCode > installedCode && releaseCode >= highestSeenCode

    fun isAllowedTransport(url: String): Boolean = runCatching {
        val uri = URI(url)
        isHttps(uri) && uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }.getOrDefault(false)

    private fun isHttps(uri: URI) = uri.scheme == "https" && uri.port in setOf(-1, 443) &&
        uri.rawUserInfo == null && uri.rawFragment == null

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
