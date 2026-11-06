package dev.cinematv

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Regression tests against the real packaged WebView and native review surfaces on a modern TV. */
@RunWith(AndroidJUnit4::class)
class HybridAuditUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var model: CinemaViewModel
    private lateinit var server: UiFixtureServer
    private val installedApkSha256: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        File(instrumentation.targetContext.applicationInfo.sourceDir).inputStream().use { input ->
            val bytes = ByteArray(65_536)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private val output get() = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?: instrumentation.targetContext.getExternalFilesDir(null)!!.absolutePath, "audit-evidence").apply { mkdirs() }

    @Before fun launch() {
        assertTrue("Run this suite on the hybrid Android TV lane", Build.VERSION.SDK_INT >= 29)
        assertEquals(Configuration.UI_MODE_TYPE_TELEVISION,
            instrumentation.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        instrumentation.targetContext.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
        server = UiFixtureServer(instrumentation.context).apply { pagedCatalog = true; catalogFilteredPages = true }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it; model = ViewModelProvider(it)[CinemaViewModel::class.java] }
        runBlocking { model.repo.library.initialize(); model.repo.library.clearHistory() }
        scenario.onActivity {
            model.updateReducedMotion(true)
            model.repo.configureMetadata(server.base, "audit-synthetic-token")
            model.serverAddress = server.base
            model.connect()
            model.navigate(Page.HOME)
        }
        rule.waitUntil(25_000) { model.authorized && model.movieBrowse.pages == 1 && model.seriesBrowse.pages == 1 && !model.movieBrowse.loading }
        awaitRoute(Page.HOME)
    }

    @After fun recordAndClose() {
        try { if (::activity.isInitialized) capture(testName.methodName) }
        finally {
            if (::scenario.isInitialized) scenario.close()
            if (::server.isInitialized) server.close()
            instrumentation.targetContext.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
        }
    }

    private fun web(): WebView? {
        var value: WebView? = null
        instrumentation.runOnMainSync { value = HybridDom.find(activity.window.decorView) }
        return value
    }
    private fun evaluate(script: String) = HybridDom.evaluate(activity, script)
    private fun awaitRoute(page: Page) {
        rule.waitUntil(20_000) { model.page == page && web() != null &&
            runCatching { evaluate("Boolean(document.querySelector('.route-${page.name.lowercase()}'))") == "true" }.getOrDefault(false) }
    }
    private fun key(code: Int) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0))
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        instrumentation.waitForIdleSync()
    }
    private fun inputReady(): Boolean {
        var ready = false
        instrumentation.runOnMainSync {
            val view = HybridDom.find(activity.window.decorView)
            ready = activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus() &&
                view != null && view.isAttachedToWindow && view.isShown && view.isFocused && view.hasWindowFocus()
        }
        return ready && runCatching { evaluate("document.hasFocus()") == "true" }.getOrDefault(false)
    }
    private fun click(id: String) {
        rule.waitUntil(10_000) { inputReady() }
        val target = JSONObject.quote(id)
        assertEquals("true", evaluate("(() => { const n=Array.from(document.querySelectorAll('[data-focus]')).find(x=>x.dataset.focus===$target); if(!n||n.disabled)return false; n.focus(); n.scrollIntoView({block:'nearest',inline:'nearest'}); return document.activeElement===n; })()"))
        rule.waitUntil(10_000) { inputReady() && evaluate("document.activeElement?.dataset.focus || ''") == id }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun awaitNativeTarget(tag: String) {
        // Injected key completion does not await the asynchronous Web bridge or
        // the native replacement's layout. Observe the destination of that one
        // press; never retry the input or move focus to make the assertion pass.
        rule.waitUntil(10_000) {
            web() == null && rule.onNodeWithTag(tag, useUnmergedTree = true).isDisplayed()
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        instrumentation.waitForIdleSync()
        awaitPresentationFrame()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "No Android compositor screenshot" }
        try { File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        File(output, "$name.json").writeText(JSONObject().put("method", testName.methodName).put("capture", name)
            .put("api", Build.VERSION.SDK_INT).put("route", model.page.name)
            .put("activity", activity.javaClass.name).put("expectedMode", "hybrid").put("installedApkSha256", installedApkSha256).put("webViewPresent", web() != null)
            .put("syntheticContent", true).put("quarantinedProgressCount", model.quarantinedProgressCount).toString(2))
    }

    /** A semantic node can exist before the native replacement has submitted its first frame. */
    private fun awaitPresentationFrame() {
        val current = web()
        val ready = CountDownLatch(1)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        var cleanup: (() -> Unit)? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            fun awaitDraw(view: View) {
                if (!active.get()) return
                try {
                    check(view.isAttachedToWindow)
                    val observer = view.viewTreeObserver
                    if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                        val committed = Runnable { if (active.get()) ready.countDown() }
                        observer.registerFrameCommitCallback(committed)
                        cleanup = { if (observer.isAlive) observer.unregisterFrameCommitCallback(committed); Unit }
                    } else {
                        val drawn = object : android.view.ViewTreeObserver.OnDrawListener {
                            private var observed = false
                            override fun onDraw() {
                                if (observed || !active.get()) return
                                observed = true
                                view.postOnAnimation { if (active.get()) ready.countDown() }
                            }
                        }
                        observer.addOnDrawListener(drawn)
                        cleanup = { if (observer.isAlive) observer.removeOnDrawListener(drawn) }
                    }
                    view.postInvalidateOnAnimation()
                } catch (error: Throwable) { failure = error; ready.countDown() }
            }
            if (current == null) awaitDraw(activity.window.decorView)
            else current.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (!active.get()) return
                    if (HybridDom.find(activity.window.decorView) !== current) {
                        failure = AssertionError("WebView changed while fencing its compositor")
                        ready.countDown()
                    } else awaitDraw(current)
                }
            })
        }
        try {
            assertTrue("Presentation did not submit the tested state within 8s", ready.await(8, TimeUnit.SECONDS))
            failure?.let { throw it }
        } finally {
            active.set(false)
            instrumentation.runOnMainSync { cleanup?.invoke() }
        }
    }

    @Test fun emptyFilteredCatalogLoadsNextPage() {
        click("nav:MOVIES"); awaitRoute(Page.MOVIES)
        click("catalog:category:剧情")
        click("catalog:year")
        rule.waitUntil(10_000) { evaluate("document.querySelectorAll('.poster-grid .poster').length") == "0" }
        assertEquals("true", evaluate("Boolean(document.querySelector('[data-focus=\"pager:load\"]'))"))
        click("pager:load")
        rule.waitUntil(15_000) { model.movieBrowse.pages == 2 && !model.movieBrowse.loading &&
            evaluate("document.querySelectorAll('.poster-grid .poster').length") == "2" }
        assertEquals("true", evaluate("document.querySelector('[data-focus=\"catalog:category:剧情\"]').getAttribute('aria-pressed')"))
        assertEquals("2026", evaluate("document.querySelector('[data-focus=\"catalog:year\"]').textContent.trim()"))
        assertEquals("pager:load", evaluate("document.activeElement?.dataset.focus || ''"))
        assertTrue(server.catalogRequests.any { it.contains("offset=2") && it.contains("type=movie") })
    }

    @Test fun nativeDestinationsAndRetainedReviewRestoreTheLibraryBookmark() {
        val source = TvSource("fixture", "原始测试片源", "${server.base}/provider")
        val legacy = MediaItem("legacy-audit", "待核对的测试剧集", source = source, isTv = true)
        val current = legacy.copy(episodes = listOf("${server.base}/stream.mp4?episode=current"),
            episodeNames = listOf("当前第一集"), episodeIds = listOf("current-1"), playlistKnown = true)
        server.registerDetail(current)
        runBlocking {
            model.repo.library.saveProgress(legacy, 45_000, 3, 120_000)
            model.repo.library.cacheItem(current)
        }
        rule.waitUntil(10_000) { model.quarantinedProgressCount == 1 }
        val before = model.repo.library.snapshot.episodeProgress.toMap()
        click("nav:SETTINGS"); awaitRoute(Page.SETTINGS)
        click("settings:developer")
        awaitNativeTarget("developer-options-entry")
        rule.onNodeWithTag("hybrid-native-panel").assertExists()
        rule.onNodeWithTag("developer-options-entry").assertExists()
        capture("${testName.methodName}-developer")
        key(KeyEvent.KEYCODE_BACK); awaitRoute(Page.SETTINGS)
        rule.waitUntil(10_000) { evaluate("document.activeElement?.dataset.focus || ''") == "settings:developer" }
        click("settings:subscriptions")
        awaitNativeTarget("source-subscriptions-screen")
        rule.onNodeWithTag("source-subscriptions-screen", useUnmergedTree = true).assertIsDisplayed()
        capture("${testName.methodName}-subscriptions")
        key(KeyEvent.KEYCODE_BACK); awaitRoute(Page.SETTINGS)
        rule.waitUntil(10_000) { evaluate("document.activeElement?.dataset.focus || ''") == "settings:subscriptions" }
        click("settings:library")
        awaitNativeTarget("library-progress-review")
        rule.onNodeWithTag("library-progress-review", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("原片单第 4 项", substring = true).assertExists()
        key(KeyEvent.KEYCODE_BACK); awaitRoute(Page.SETTINGS)
        click("nav:LIBRARY"); awaitRoute(Page.LIBRARY)
        click("library:HISTORY")
        click("library:manage")
        awaitNativeTarget("library-progress-review")
        rule.onNodeWithTag("library-progress-review", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("打开当前片单").assertExists()
        rule.onNodeWithText("当时的片单缺少明确的剧集身份", substring = true).assertExists()
        rule.onNodeWithTag("library-review-record:${before.keys.single()}", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
        assertNull(web())
        assertEquals(before, model.repo.library.snapshot.episodeProgress)
        capture("${testName.methodName}-review")
        key(KeyEvent.KEYCODE_BACK); awaitRoute(Page.LIBRARY)
        rule.waitUntil(10_000) { evaluate("document.activeElement?.dataset.focus || ''") == "library:manage" }
        assertEquals("true", evaluate("document.querySelector('[data-focus=\"library:HISTORY\"]').getAttribute('aria-pressed')"))
        assertEquals(before, model.repo.library.snapshot.episodeProgress)
    }
}
