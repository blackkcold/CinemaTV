package dev.cinematv.data.update

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.json.JSONObject

class UpdatePolicyTest {
    private val now = 1_800_000_000L
    private fun release() = UpdateRelease(UpdateChannel.DEV, 20, "0.7.0-dev.1", "dev.cinematv", 26,
        "https://github.com/blackkcold/CinemaTV/releases/download/v0.7.0-dev.1/CinemaTV-dev.apk",
        100, "a".repeat(64), now - 60, now + 86400, "Test release")
    private fun valid(value: UpdateRelease, channel: UpdateChannel = UpdateChannel.DEV) =
        UpdatePolicy.requireValid(value, channel, "dev.cinematv", 35, now)
    private fun rejected(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun acceptsExpectedOfficialRelease() { valid(release()) }
    @Test fun rejectsWrongChannelOrPackage() {
        rejected { valid(release(), UpdateChannel.STABLE) }
        rejected { valid(release().copy(applicationId = "attacker.app")) }
    }
    @Test fun rejectsInvalidIntegrityAndVersions() {
        listOf(release().copy(apkBytes = 0), release().copy(apkBytes = UpdatePolicy.MAX_APK_BYTES + 1),
            release().copy(sha256 = "a".repeat(63)), release().copy(versionCode = 0),
            release().copy(versionCode = 2_100_000_001), release().copy(minSdk = 36),
            release().copy(versionName = "")).forEach { rejected { valid(it) } }
    }
    @Test fun rejectsStaleFutureAndUnboundedManifests() {
        listOf(release().copy(expiresAt = now), release().copy(publishedAt = now + 301),
            release().copy(expiresAt = now + UpdatePolicy.MAX_LIFETIME_SECONDS + 1),
            release().copy(publishedAt = 0)).forEach { rejected { valid(it) } }
    }
    @Test fun neverDowngradesOrReinstallsAndHonorsChannelHighWaterMark() {
        assertTrue(UpdatePolicy.canUpgrade(10, 11, 11))
        assertFalse(UpdatePolicy.canUpgrade(10, 10))
        assertFalse(UpdatePolicy.canUpgrade(10, 9))
        assertFalse(UpdatePolicy.canUpgrade(10, 11, 12))
    }
    @Test fun rejectsUntrustedInitialApkUrls() {
        listOf("http://github.com/blackkcold/CinemaTV/releases/download/v1/a.apk",
            "https://github.com.evil.test/blackkcold/CinemaTV/releases/download/v1/a.apk",
            "https://github.com/other/repo/releases/download/v1/a.apk",
            "https://github.com/blackkcold/CinemaTV/releases/download/v1/../a.apk",
            "https://github.com/blackkcold/CinemaTV/releases/download/v1/a.apk?x=y",
            "https://user@github.com/blackkcold/CinemaTV/releases/download/v1/a.apk",
            "https://github.com:444/blackkcold/CinemaTV/releases/download/v1/a.apk",
            "https://github.com/blackkcold/CinemaTV/releases/download/v1/a.apk#x").forEach { url ->
            rejected { valid(release().copy(apkUrl = url)) }
        }
    }
    @Test fun redirectTransportIsHttpsAndHostBounded() {
        assertTrue(UpdatePolicy.isAllowedTransport("https://release-assets.githubusercontent.com/asset?sig=test"))
        listOf("http://github.com/x", "https://evil.test/a.apk", "https://github.com.evil/a", "file:///a",
            "https://user@github.com/a", "https://github.com:8080/a").forEach { assertFalse(UpdatePolicy.isAllowedTransport(it)) }
    }
    @Test fun fixedFeedUrlsDoNotDependOnSourceServer() {
        assertEquals("https://github.com/blackkcold/CinemaTV/releases/download/channel-stable/update.json", UpdatePolicy.feedUrl(UpdateChannel.STABLE))
        rejected { UpdateChannel.parse("nightly") }
    }

    @Test fun verifiesExactBytesAndRejectsWrongKeyTamperingAndMissingSignatures() {
        // Ephemeral test keys only, never serialized or used to sign a release.
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = generator.generateKeyPair()
        val payload = "signed update payload".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(payload); sign() }
        assertTrue(UpdatePolicy.verifySignature(payload, signature, pair.public))
        assertFalse(UpdatePolicy.verifySignature("modified".toByteArray(), signature, pair.public))
        assertFalse(UpdatePolicy.verifySignature(payload, signature, generator.generateKeyPair().public))
        assertFalse(UpdatePolicy.verifySignature(payload, byteArrayOf(), pair.public))
        assertFalse(UpdatePolicy.verifySignature(ByteArray(UpdatePolicy.MAX_FEED_BYTES + 1), signature, pair.public))
    }

    @Test fun signedEnvelopeDecodesOnlyWithInstalledTrustKey() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val value = release()
        val payload = JSONObject().put("schema", 1).put("channel", "dev").put("versionCode", value.versionCode)
            .put("versionName", value.versionName).put("applicationId", value.applicationId).put("minSdk", value.minSdk)
            .put("apkUrl", value.apkUrl).put("apkBytes", value.apkBytes).put("sha256", value.sha256)
            .put("publishedAt", value.publishedAt).put("expiresAt", value.expiresAt).put("notes", value.notes).toString().toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(payload); sign() }
        val envelope = JSONObject().put("schema", 1).put("payload", Base64.getEncoder().encodeToString(payload))
            .put("signature", Base64.getEncoder().encodeToString(signature))
        assertEquals(value, UpdateManifest.decode(envelope.toString().toByteArray(), pair.public))
        envelope.put("payload", Base64.getEncoder().encodeToString(payload + " ".toByteArray()))
        rejected { UpdateManifest.decode(envelope.toString().toByteArray(), pair.public) }
    }
}
