package dev.cinematv.data.update

import org.json.JSONObject
import java.security.PublicKey
import java.util.Base64

object UpdateManifest {
    /** Signature covers exact UTF-8 payload bytes. JSON is parsed only after verification. */
    fun decode(envelope: ByteArray, trustedKey: PublicKey): UpdateRelease {
        require(envelope.size in 1..UpdatePolicy.MAX_FEED_BYTES) { "更新清单大小不正确" }
        val wrapper = JSONObject(String(envelope, Charsets.UTF_8))
        require(wrapper.getInt("schema") == 1) { "不支持的更新清单格式" }
        val payload = Base64.getDecoder().decode(wrapper.getString("payload"))
        val signature = Base64.getDecoder().decode(wrapper.getString("signature"))
        require(UpdatePolicy.verifySignature(payload, signature, trustedKey)) { "更新清单签名校验失败" }
        val json = JSONObject(String(payload, Charsets.UTF_8))
        require(json.getInt("schema") == 1) { "不支持的更新内容格式" }
        return UpdateRelease(
            UpdateChannel.parse(json.getString("channel")), json.getLong("versionCode"),
            json.getString("versionName"), json.getString("applicationId"), json.getInt("minSdk"),
            json.getString("apkUrl"), json.getLong("apkBytes"), json.getString("sha256"),
            json.getLong("publishedAt"), json.getLong("expiresAt"), json.getString("notes"),
        )
    }
}
