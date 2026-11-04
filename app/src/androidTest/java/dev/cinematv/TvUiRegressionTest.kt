package dev.cinematv

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.data.LibraryStatus
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class TvUiRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    private lateinit var server: UiFixtureServer
    private val model get() = rule.activity.model
    private val directory get() = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?: rule.activity.getExternalFilesDir(null)!!.absolutePath, "ui-evidence").apply { mkdirs() }
    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        rule.waitForIdle()
        val trace = rule.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() }
        File(directory, "focus-trace.txt").appendText("key=$code route=${model.focusRoute}\n$trace\n")
    }
    private fun screenshot(name: String, waitForArt: Boolean = true) {
        rule.waitForIdle()
        try {
          if (waitForArt && model.page != Page.PLAYER) {
            val art = (if (model.page == Page.DETAIL) model.selected else model.focused ?: model.featured.firstOrNull())?.backdrop
            if (!art.isNullOrBlank()) rule.waitUntil(8000) { rule.activity.decodedScene == art }
          }
        } finally {
          val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
          File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Before fun connectFixture() {
        server = UiFixtureServer(InstrumentationRegistry.getInstrumentation().context)
        rule.runOnIdle {
            model.updateReducedMotion(true)
            model.serverAddress = server.base
            model.connect()
            model.navigate(Page.HOME)
        }
        rule.waitUntil(20000) { model.authorized && model.featured.size == 24 && model.tvShows.size == 24 }
        rule.waitForIdle()
    }
    @After fun exportFinalState() {
        runCatching { screenshot("99-final-state", waitForArt = false) }.onFailure {
            File(directory, "capture-error.txt").writeText(it.stackTraceToString())
        }
        if (::server.isInitialized) server.close()
        rule.activity.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
    }
    @Test fun remoteRoutesRestoreOffscreenItemsAndPlayerPanels() {
        rule.onNodeWithText("观看详情").assertIsFocused()
        screenshot("01-home")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithContentDescription(server.title(0)).assertIsFocused()
        repeat(9) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        val selected = server.title(9)
        rule.onNodeWithContentDescription(selected).assertIsFocused()
        screenshot("02-focused-rail")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(15000) { model.page == Page.DETAIL && !model.busy && model.selected?.episodes?.size == 36 }
        screenshot("03-detail")
        rule.onNodeWithText("片库状态").performClick()
        rule.onNodeWithText("想看").assertIsFocused()
        key(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithText("片库状态").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.runOnIdle { assertEquals(LibraryStatus.WANT_TO_WATCH, model.repo.libraryStatus(model.selected!!)) }
        key(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithContentDescription(selected).assertIsFocused()
        screenshot("04-restored-rail")

        rule.runOnIdle { model.navigate(Page.MOVIES) }
        rule.onNodeWithText("全部").assertIsFocused()
        screenshot("05-movies")
        rule.runOnIdle { model.navigate(Page.SERIES) }
        screenshot("06-series")
        rule.runOnIdle { model.navigate(Page.EXPLORE) }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        screenshot("07-discovery")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(15000) { model.page == Page.DETAIL && !model.busy }
        key(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithContentDescription(server.title(1)).assertIsFocused()

        rule.onNodeWithContentDescription("打开搜索").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(server.title(0))
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(15000) { !model.busy && model.results.isNotEmpty() }
        screenshot("08-search")
        rule.onNodeWithContentDescription(server.title(0)).performClick()
        rule.waitUntil(15000) { model.page == Page.DETAIL && !model.busy }
        key(KeyEvent.KEYCODE_BACK)
        assertEquals(Page.SEARCH, model.page)
        assertTrue(model.results.isNotEmpty())

        rule.runOnIdle { model.navigate(Page.LIVE) }
        rule.waitUntil(15000) { model.channels.size == 18 }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.waitUntil(15000) { model.epgPrograms.isNotEmpty() }
        screenshot("09-live")
        rule.onNodeWithContentDescription("打开片库").performClick()
        screenshot("10-library")
        rule.onNodeWithContentDescription("打开设置").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("连接与账户").assertIsFocused()
        screenshot("11-settings")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("减少动态效果：开启").assertExists()
        screenshot("12-effects")

        rule.runOnIdle { model.open(model.featured.first()) }
        rule.waitUntil(15000) { model.page == Page.DETAIL && !model.busy }
        rule.onNodeWithText("▶ 立即播放").performClick()
        rule.waitUntil(20000) { model.page == Page.PLAYER && (model.selected?.durationMs ?: 0) > 0 }
        rule.onNodeWithText("暂停").performClick()
        screenshot("13-player")
        rule.onNodeWithText("音轨").performClick()
        rule.onNodeWithText("自动选择").assertExists()
        screenshot("14-player-panel")
        key(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithText("音轨").assertIsFocused()
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_BACK)
        rule.runOnIdle {
            assertEquals(Page.DETAIL, model.page)
            assertTrue((model.selected?.positionMs ?: 0) > 0)
            assertTrue((model.selected?.durationMs ?: 0) >= 29000)
            model.navigate(Page.HOME)
            model.updateReducedMotion(false)
        }
        recordFrameMetrics()
        val metrics = rule.activity.resources.displayMetrics
        File(directory, "viewport.json").writeText(JSONObject()
            .put("widthPx", metrics.widthPixels).put("heightPx", metrics.heightPixels)
            .put("densityDpi", metrics.densityDpi).put("fixture", "Original vector artwork and synthetic H264/AAC")
            .put("scope", "TV Compose UI on Android emulator; physical TV and decoder certification excluded").toString(2))
    }
    private fun recordFrameMetrics() {
        val frames = Collections.synchronizedList(mutableListOf<Double>())
        val thread = HandlerThread("frame-evidence").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val value = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1000000.0
            if (value > 0) frames.add(value)
        }
        rule.runOnIdle { rule.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        repeat(8) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_DPAD_UP)
        }
        rule.runOnIdle { rule.activity.window.removeOnFrameMetricsAvailableListener(listener) }
        thread.quitSafely(); thread.join(2000)
        val values = synchronized(frames) { frames.toList().sorted() }
        assertTrue("Frame evidence must contain rendered frames", values.isNotEmpty())
        File(directory, "frames.json").writeText(JSONObject().put("frames", values.size)
            .put("p95Ms", values[((values.size - 1) * .95).toInt()]).put("p99Ms", values[((values.size - 1) * .99).toInt()])
            .put("over16_7ms", values.count { it > 16.7 }).put("renderer", "Android emulator / SwiftShader; informational, not hardware certification").toString(2))
    }
}
