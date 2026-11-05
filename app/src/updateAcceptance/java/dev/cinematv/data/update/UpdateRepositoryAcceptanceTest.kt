package dev.cinematv.data.update

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** TEST ONLY. Installed target and test APKs must share the disposable fixture certificate. */
@RunWith(AndroidJUnit4::class)
class UpdateRepositoryAcceptanceTest {
    private lateinit var context: Context
    private lateinit var fixtures: File
    private lateinit var privateDownloads: File
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var repository: UpdateRepository
    private val observedUrls = mutableListOf<String>()
    companion object {
        private lateinit var fixtureDirectory: File

        @BeforeClass @JvmStatic fun stagePublicTestFixtures() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            fixtureDirectory = File(context.cacheDir, "update-acceptance-fixtures")
            fixtureDirectory.deleteRecursively()
            check(fixtureDirectory.mkdirs())
            // Test-only staging transport. This code is absent from production APKs. adb reverse
            // exposes only the host's public TEST ONLY artifact folder, never the key directory.
            val base = InstrumentationRegistry.getArguments().getString("fixtureBaseUrl")
                ?.toHttpUrl() ?: error("Missing fixtureBaseUrl; use test-update-repository.sh")
            require(base.scheme == "http" && base.host == "127.0.0.1" && base.encodedPath == "/" &&
                base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
            val staging = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .callTimeout(120, TimeUnit.SECONDS).build()
            try {
                val receipt = staging.newCall(Request.Builder().url(base.resolve("SHA256SUMS.txt")!!).build()).execute().use {
                    check(it.code == 200)
                    requireNotNull(it.body).string()
                }.lineSequence().filter { it.isNotBlank() }.associate { line ->
                    val (hash, name) = line.split(Regex("\\s+"), limit = 2)
                    name to hash
                }
                val needed = listOf("stable.apk", "dev.apk", "wrong-key.apk", "wrong-package.apk", "wrong-min-sdk.apk") +
                    receipt.keys.filter { it.startsWith("manifests/") && it.endsWith(".json") }
                require(needed.size > 5) { "No signed test envelopes in fixture receipt" }
                for (name in needed) {
                    require(Regex("(?:manifests/)?[a-z-]+\\.(?:apk|json)").matches(name))
                    val output = File(fixtureDirectory, name)
                    output.parentFile!!.mkdirs()
                    staging.newCall(Request.Builder().url(base.resolve(name)!!).build()).execute().use { response ->
                        check(response.code == 200) { "Missing public test fixture $name" }
                        requireNotNull(response.body).byteStream().use { input -> output.outputStream().use { input.copyTo(it) } }
                    }
                    val actual = java.security.MessageDigest.getInstance("SHA-256")
                    output.inputStream().use { input ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(bytes)
                            if (read < 0) break
                            actual.update(bytes, 0, read)
                        }
                    }
                    val hash = actual.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    check(hash == receipt[name]) { "Staged test fixture digest mismatch: $name" }
                }
            } finally {
                staging.connectionPool.evictAll()
                staging.dispatcher.executorService.shutdown()
            }
        }

        @AfterClass @JvmStatic fun deleteStagedFixtures() {
            if (::fixtureDirectory.isInitialized) fixtureDirectory.deleteRecursively()
        }
    }

    private val preferences get() = context.getSharedPreferences("cinematv_updates", Context.MODE_PRIVATE)

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.cinematv", context.packageName)
        assertFalse("The target must not be a debug APK", BuildConfig.DEBUG)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertTrue(BuildConfig.UPDATES_ENABLED)
        assertEquals(100, BuildConfig.VERSION_CODE)
        fixtures = fixtureDirectory
        assertTrue("Stage fixtures with test-update-repository.sh", File(fixtures, "stable.apk").isFile)
        assertTrue(preferences.edit().clear().commit())
        privateDownloads = File(context.cacheDir, "verified-updates")
        privateDownloads.deleteRecursively()
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                assertTrue("Repository must validate official HTTPS URLs", UpdatePolicy.isAllowedTransport(original.url.toString()))
                synchronized(observedUrls) { observedUrls += original.url.toString() }
                // Rewrite only after the real repository validates the production URL. Return the
                // original URL so redirect resolution still uses the official host, not loopback.
                chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath)).build())
                    .newBuilder().request(original).build()
            }.build()
        repository = UpdateRepository(context, client)
        assertTrue(repository.updatesEnabled)
    }

    @After fun tearDown() {
        if (::client.isInitialized) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        if (::server.isInitialized) server.shutdown()
        if (::privateDownloads.isInitialized) privateDownloads.deleteRecursively()
        if (::context.isInitialized) preferences.edit().clear().commit()
    }

    private fun feed(name: String) = File(fixtures, "manifests/$name.json").readText()
    private fun enqueueFeed(name: String) { server.enqueue(MockResponse().setBody(feed(name))) }
    private fun apkBody(name: String): Buffer = File(fixtures, "$name.apk").inputStream().use { Buffer().readFrom(it) }
    private fun enqueueApk(name: String) { server.enqueue(MockResponse().setBody(apkBody(name))) }
    private fun assertNoDownloads() { assertTrue(privateDownloads.listFiles().orEmpty().isEmpty()) }
    private suspend fun rejects(message: String, block: suspend () -> Unit) {
        try { block(); fail("Expected rejection: $message") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message)) }
    }
    private suspend fun checked(name: String, channel: UpdateChannel = UpdateChannel.STABLE): UpdateRelease {
        enqueueFeed(name)
        return repository.check(channel)
    }
    private suspend fun rejectsDownload(name: String, apk: String, message: String, channel: UpdateChannel = UpdateChannel.STABLE) {
        val release = checked(name, channel)
        enqueueApk(apk)
        rejects(message) { repository.download(release) {} }
        assertNoDownloads()
    }

    @Test fun validStableAndDevUseInstalledCertificateAndPrivateVerifiedApks() = runBlocking {
        for ((name, channel, code) in listOf(Triple("stable", UpdateChannel.STABLE, 101L), Triple("dev", UpdateChannel.DEV, 102L))) {
            val release = checked(name, channel)
            assertEquals(code, release.versionCode)
            assertEquals(code, preferences.getLong("highest_${channel.id}", 0))
            enqueueApk(name)
            val progress = mutableListOf<Int>()
            val file = repository.download(release) { progress += it }
            assertEquals(privateDownloads.canonicalFile, file.canonicalFile.parentFile)
            assertEquals(release.apkBytes, file.length())
            assertEquals(100, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a < b })
            repository.verifyFile(file, release) // the same recheck used before installer launch
            assertTrue(file.delete())
            assertNoDownloads()
        }
        assertEquals(listOf(UpdatePolicy.feedUrl(UpdateChannel.STABLE),
            "https://github.com/blackkcold/CinemaTV/releases/download/TEST-ONLY/stable.apk",
            UpdatePolicy.feedUrl(UpdateChannel.DEV),
            "https://github.com/blackkcold/CinemaTV/releases/download/TEST-ONLY/dev.apk"), observedUrls)
    }

    @Test fun tamperedEnvelopeCannotAdvanceWatermark() = runBlocking {
        rejectsUntrustedFeed("tampered", "签名校验失败")
    }
    @Test fun wrongKeyEnvelopeCannotAdvanceWatermark() = runBlocking {
        rejectsUntrustedFeed("wrong-envelope-key", "签名校验失败")
    }
    @Test fun expiredEnvelopeCannotAdvanceWatermark() = runBlocking {
        rejectsUntrustedFeed("expired", "已过期")
    }
    private suspend fun rejectsUntrustedFeed(name: String, message: String) {
        assertTrue(preferences.edit().putLong("highest_stable", 100).commit())
        enqueueFeed(name)
        rejects(message) { repository.check(UpdateChannel.STABLE) }
        assertEquals(100L, preferences.getLong("highest_stable", 0))
        assertNoDownloads()
    }

    @Test fun signedInvalidPackageChannelVersionSdkAndUrlAreRejected() = runBlocking {
        for ((name, message) in listOf("wrong-package" to "包名不匹配", "wrong-channel" to "渠道不匹配",
            "invalid-version" to "无效版本号", "unsupported-sdk" to "不支持当前", "http-url" to "官方发布页",
            "foreign-url" to "官方发布页")) {
            rejectsUntrustedFeed(name, message)
        }
    }

    @Test fun signedRollbackCannotLowerObservedCode() = runBlocking {
        checked("dev", UpdateChannel.DEV)
        enqueueFeed("rollback")
        rejects("过时") { repository.check(UpdateChannel.DEV) }
        assertEquals(102L, preferences.getLong("highest_dev", 0))
        assertNoDownloads()
    }

    @Test fun digestMismatchDeletesDownloadedFile() = runBlocking {
        rejectsDownload("bad-digest", "stable", "SHA-256")
    }
    @Test fun declaredLengthMismatchLeavesNoFile() = runBlocking {
        rejectsDownload("size-too-large", "stable", "大小不匹配")
    }
    @Test fun chunkedOverflowDeletesPartialFile() = runBlocking {
        val release = checked("size-too-small")
        server.enqueue(MockResponse().setChunkedBody(apkBody("stable"), 64 * 1024))
        rejects("超出预期大小") { repository.download(release) {} }
        assertNoDownloads()
    }
    @Test fun truncatedChunkedBodyDeletesPartialFile() = runBlocking {
        val release = checked("stable")
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(4096)), 1024))
        rejects("大小不匹配") { repository.download(release) {} }
        assertNoDownloads()
    }

    @Test fun actualArchivePackageMismatchIsRejected() = runBlocking {
        rejectsDownload("archive-package", "wrong-package", "身份或渠道不匹配")
    }
    @Test fun actualArchiveChannelMismatchIsRejected() = runBlocking {
        rejectsDownload("archive-channel", "stable", "身份或渠道不匹配", UpdateChannel.DEV)
    }
    @Test fun actualArchiveVersionCodeAndNameMismatchAreRejected() = runBlocking {
        rejectsDownload("archive-version-name", "stable", "身份或渠道不匹配")
        rejectsDownload("archive-version", "stable", "身份或渠道不匹配")
    }
    @Test fun actualArchiveMinSdkMismatchIsRejectedOnBothSupportedDevices() = runBlocking {
        // The APK actually declares25, which both API26/35 can parse. The signed envelope
        // claims26, so rejection proves archive identity checking, not OS incompatibility.
        rejectsDownload("archive-sdk", "wrong-min-sdk", "身份或渠道不匹配")
    }
    @Test fun actualWrongSignerIsRejectedEvenWithTrustedEnvelopeAndCorrectDigest() = runBlocking {
        rejectsDownload("wrong-apk-key", "wrong-key", "签名与已安装版本不同", UpdateChannel.DEV)
    }

    @Test fun cancelledBodyReadReturnsPromptlyAndDeletesPartialPrivateFile() = runBlocking {
        val release = checked("stable")
        // Send a prefix, advertise the full APK length, then keep the socket open without more
        // bytes. No timed throttle can accidentally satisfy the cancellation deadline; closing
        // the client socket also wakes the server immediately rather than delaying teardown.
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(8192)))
            .setHeader("Content-Length", release.apkBytes).setSocketPolicy(SocketPolicy.KEEP_OPEN))
        val progressed = CompletableDeferred<Unit>()
        val job = launch { repository.download(release) { progressed.complete(Unit) } }
        withTimeout(10_000) { progressed.await() }
        assertTrue("The test must cancel an existing private partial", privateDownloads.listFiles().orEmpty().any { it.length() > 0 })
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertNoDownloads()
    }

    @Test fun recheckRejectsOutsidePrivateDirectoryAndPostDownloadTampering() = runBlocking {
        val release = checked("stable")
        rejects("大小不匹配") { repository.verifyFile(File(fixtures, "stable.apk"), release) }
        enqueueApk("stable")
        val file = repository.download(release) {}
        java.io.RandomAccessFile(file, "rw").use { input ->
            val original = input.readByte()
            input.seek(0)
            input.writeByte(original.toInt() xor 1)
        }
        rejects("SHA-256") { repository.verifyFile(file, release) }
    }

    @Test fun untrustedRedirectNeverLeavesOfficialTransport() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/evil"))
        rejects("不受信任") { repository.check(UpdateChannel.STABLE) }
        assertEquals(1, server.requestCount)
        assertEquals(listOf(UpdatePolicy.feedUrl(UpdateChannel.STABLE)), observedUrls)
        assertNoDownloads()
    }
}
