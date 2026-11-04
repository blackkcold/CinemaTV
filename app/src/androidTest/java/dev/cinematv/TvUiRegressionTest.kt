package dev.cinematv

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
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
@OptIn(ExperimentalTestApi::class)
class TvUiRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    private lateinit var server: UiFixtureServer
    private val model get() = rule.activity.model
    private val directory get() = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?: rule.activity.getExternalFilesDir(null)!!.absolutePath, "ui-evidence").apply { mkdirs() }
    private fun key(code: Int) {
        android.util.Log.i("CinemaTVQa", "key=$code route=${model.focusRoute}")
        if (code == KeyEvent.KEYCODE_BACK && model.page != Page.PLAYER) {
            if (rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
                // Compose's dialog owns a separate Android window. Activity-root
                // selection can wait forever for focus that correctly moved here.
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.pressBack())
            } else androidx.test.espresso.Espresso.pressBack()
        }
        else {
            val composeKey = when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> Key.DirectionDown
                KeyEvent.KEYCODE_DPAD_UP -> Key.DirectionUp
                KeyEvent.KEYCODE_DPAD_LEFT -> Key.DirectionLeft
                KeyEvent.KEYCODE_DPAD_RIGHT -> Key.DirectionRight
                KeyEvent.KEYCODE_DPAD_CENTER -> Key.DirectionCenter
                KeyEvent.KEYCODE_BACK -> Key.Back
                else -> error("Unsupported test key: $code")
            }
            val target = if (rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) rule.onNode(isDialog()) else rule.onRoot()
            target.performKeyInput { pressKey(composeKey) }
        }
        rule.waitForIdle()
        val trace = rule.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() }
        File(directory, "focus-trace.txt").appendText("key=$code route=${model.focusRoute}\n$trace\n")
    }
    private fun assertFullyVisible(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        // assertIsDisplayed permits partial clipping. Compare the un-clipped
        // control rectangle with the detail LazyColumn's full-screen viewport.
        val bounds = node.getUnclippedBoundsInRoot()
        val viewport = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Control is clipped by the detail viewport: $bounds outside $viewport",
            bounds.left >= viewport.left && bounds.top >= viewport.top &&
                bounds.right <= viewport.right && bounds.bottom <= viewport.bottom)
    }

    private fun awaitBundledFonts() {
        rule.waitUntil(15000) { rule.activity.bundledFontsReady || rule.activity.fontLoadFailures.isNotEmpty() }
        assertTrue("Bundled Noto failed: ${rule.activity.fontLoadFailures}", rule.activity.bundledFontsReady)
        rule.waitForIdle()
    }
    private fun awaitPlatformImeVisible(stage: String) {
        // The editor is already composed/focused before this call. IME startup is
        // an external Android operation, not Compose work: waitUntil + runOnIdle
        // would pump an extra app frame on every probe and compete with the cold
        // keyboard's 4K software renderer. Observe real insets without advancing
        // the Compose clock, showing a keyboard, or weakening the 15 s deadline.
        val activity = rule.activity
        val keyboardConfiguration = rule.runOnUiThread {
            val config = activity.resources.configuration
            "keyboard=${config.keyboard}, hardKeyboardHidden=${config.hardKeyboardHidden}, " +
                "showImeWithHardKeyboard=${Settings.Secure.getString(activity.contentResolver, "show_ime_with_hard_keyboard")}"
        }
        val startedAt = SystemClock.uptimeMillis()
        val deadline = startedAt + 15000L
        var lastObservation = "not sampled"
        android.util.Log.i("CinemaTVQa", "ime-wait-start stage=$stage $keyboardConfiguration")
        while (true) {
            val ready = rule.runOnUiThread {
                val decor = activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor)
                val visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                lastObservation = "route=${activity.model.page}, windowFocus=${activity.hasWindowFocus()}, " +
                    "imeVisible=$visible, imeInsets=${insets?.getInsets(WindowInsetsCompat.Type.ime())}, $keyboardConfiguration"
                activity.hasWindowFocus() && visible
            }
            val now = SystemClock.uptimeMillis()
            if (ready && now <= deadline) {
                android.util.Log.i("CinemaTVQa", "ime-wait-visible stage=$stage elapsedMs=${now - startedAt} $lastObservation")
                return
            }
            if (now >= deadline) break
            SystemClock.sleep(minOf(50L, deadline - now))
        }
        val diagnostic = "IME did not become visible within 15000 ms: stage=$stage $lastObservation"
        android.util.Log.e("CinemaTVQa", diagnostic)
        File(directory, "ime-wait-failure.txt").writeText(diagnostic)
        throw AssertionError(diagnostic)
    }

    private fun awaitHiddenImeAndFullScreen() {
        // Observe production IME ownership. This helper never dismisses it.
        rule.waitUntil(15000) {
            val windowRestored = rule.runOnIdle {
                val decor = rule.activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor)
                val location = IntArray(2)
                decor.getLocationOnScreen(location)
                rule.activity.hasWindowFocus() && insets != null &&
                    !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    location.contentEquals(intArrayOf(0, 0))
            }
            val root = rule.onRoot().fetchSemanticsNode()
            windowRestored && root.positionInWindow.x == 0f && root.positionInWindow.y == 0f
        }
    }
    private fun captureWindow(): Bitmap {
        awaitBundledFonts()
        rule.waitForIdle()
        val root = rule.onRoot().fetchSemanticsNode()
        check(root.positionInWindow.x == 0f && root.positionInWindow.y == 0f) {
            "Expected a full-screen Compose root"
        }
        return TvWindowCapture.capture(rule.activity, root.size.width, root.size.height)
    }
    private fun screenshot(name: String, waitForArt: Boolean = true) {
        awaitBundledFonts()
        android.util.Log.i("CinemaTVQa", "capture=$name directory=${directory.absolutePath}")
        if (waitForArt) rule.runOnIdle { model.dismissMessage() }
        rule.waitForIdle()
        try {
          if (waitForArt && model.page != Page.PLAYER) {
            val art = (if (model.page == Page.DETAIL) model.selected else model.focused ?: model.featured.firstOrNull())?.backdrop
            if (!art.isNullOrBlank()) rule.waitUntil(8000) { rule.activity.decodedScene == art }
          }
        } finally {
          android.util.Log.i("CinemaTVQa", "capture-begin=$name")
          val bitmap = captureWindow()
          try {
            android.util.Log.i("CinemaTVQa", "readback-complete=$name width=${bitmap.width} height=${bitmap.height} bytes=${bitmap.allocationByteCount}")
            val compressed = File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(compressed) { "PNG compression failed for $name" }
            android.util.Log.i("CinemaTVQa", "PNG-complete=$name compressed=$compressed")
          } finally {
            // A 4K capture owns a large native pixel buffer; release after export.
            bitmap.recycle()
          }
        }
    }
    @Before fun connectFixture() {
        awaitBundledFonts()
        rule.waitUntil(10000) { rule.activity.hasWindowFocus() }
        server = UiFixtureServer(InstrumentationRegistry.getInstrumentation().context).apply { homeCatalog = true }
        rule.runOnIdle {
            model.updateReducedMotion(true)
            model.repo.configureMetadata(server.base, "offline-fixture-token")
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
        rule.runOnIdle { model.updateReducedMotion(false) }
        screenshot("02-focused-rail")
        rule.runOnIdle { model.updateReducedMotion(true) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(15000) { model.page == Page.DETAIL && !model.busy && model.selected?.episodes?.size == 36 }
        screenshot("03-detail")
        // This fixture has one source and no work graph. The new full-width
        // association row must not strand the playlist below the viewport.
        rule.runOnIdle { assertFalse(model.workNavigation?.visible == true); assertTrue(model.alternatives.isEmpty()) }
        rule.onNodeWithText("▶ 立即播放").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("association-open").assertIsFocused()
        assertFullyVisible(rule.onNodeWithTag("association-open"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val firstEpisode = rule.onNodeWithText(model.episodeLabel(0))
        firstEpisode.assertIsFocused()
        assertFullyVisible(firstEpisode)
        key(KeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("association-open").assertIsFocused()
        assertFullyVisible(rule.onNodeWithTag("association-open"))
        key(KeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithText("▶ 立即播放").assertIsFocused()
        assertFullyVisible(rule.onNodeWithText("▶ 立即播放"))
        val originalStatus = rule.runOnIdle { model.repo.libraryStatus(model.selected!!) }
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        rule.onNodeWithText("片库状态").assertIsFocused()
        // Restoration must remember a non-primary action too, not always Play.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("association-open").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithText("片库状态").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        // Status removal made Cancel the deliberate safe default. Cover Back AND
        // center-on-Cancel before deliberately navigating to a mutating status.
        rule.onNodeWithText("取消").assertIsFocused()
        key(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithText("片库状态").assertIsFocused()
        rule.runOnIdle { assertEquals(originalStatus, model.repo.libraryStatus(model.selected!!)) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("取消").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("加入片库").assertDoesNotExist()
        rule.onNodeWithText("片库状态").assertIsFocused()
        rule.runOnIdle { assertEquals(originalStatus, model.repo.libraryStatus(model.selected!!)) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("取消").assertIsFocused()
        if (rule.runOnIdle { model.hasLibraryStatus(model.selected!!) }) {
            key(KeyEvent.KEYCODE_DPAD_UP)
            rule.onNodeWithText("移除片库状态").assertIsFocused()
        }
        LibraryStatus.entries.reversed().forEach { status ->
            key(KeyEvent.KEYCODE_DPAD_UP)
            rule.onNodeWithText(status.title).assertIsFocused()
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(5000) { model.repo.libraryStatus(model.selected!!) == LibraryStatus.WANT_TO_WATCH }
        rule.onNodeWithText("加入片库").assertDoesNotExist()
        rule.onNodeWithText("片库状态").assertIsFocused()
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
        // Exercise the real visible editor before invoking its Search action;
        // otherwise an early hide can race with the keyboard's pending show.
        rule.onNode(hasSetTextAction()).assertIsFocused()
        awaitPlatformImeVisible("search-submit")
        android.util.Log.i("CinemaTVQa", "search-ime-visible; submitting Search action")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(15000) { !model.busy && model.results.isNotEmpty() }
        // Results alone do not establish that IME hide/adjust-pan has finished.
        awaitHiddenImeAndFullScreen()
        android.util.Log.i("CinemaTVQa", "search-ime-hidden; full-screen geometry restored")
        rule.onNodeWithContentDescription(server.title(0)).assertIsDisplayed()
        screenshot("08-search")
        rule.onNodeWithContentDescription(server.title(0)).performClick()
        rule.waitUntil(15000) { model.page == Page.DETAIL && !model.busy }
        key(KeyEvent.KEYCODE_BACK)
        assertEquals(Page.SEARCH, model.page)
        assertTrue(model.results.isNotEmpty())
        rule.onNode(hasSetTextAction()).assertIsFocused()
        // Leave the restored editor immediately, including its pending IME show.
        // The destination must recover without a test-only keyboard dismissal.
        rule.runOnIdle { model.navigate(Page.LIVE) }
        rule.waitUntil(15000) { model.channels.size == 18 }
        awaitHiddenImeAndFullScreen()
        assertEquals(Page.LIVE, model.page)
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        android.util.Log.i("CinemaTVQa", "search-exit-ime-hidden; LIVE full-screen geometry restored")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.waitUntil(15000) { model.epgPrograms.isNotEmpty() }
        awaitHiddenImeAndFullScreen()
        screenshot("09-live")

        // Also cover a fully visible keyboard exit, not only a pending show.
        rule.runOnIdle { model.navigate(Page.SEARCH) }
        rule.onNode(hasSetTextAction()).assertIsFocused()
        awaitPlatformImeVisible("search-exit")
        android.util.Log.i("CinemaTVQa", "search-exit-visible-ime; navigating LIVE")
        rule.runOnIdle { model.navigate(Page.LIVE) }
        awaitHiddenImeAndFullScreen()
        assertEquals(Page.LIVE, model.page)
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.onNode(hasText(model.channels.first().name) and hasClickAction()).assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNode(hasText(model.channels[1].name) and hasClickAction()).assertIsFocused()
        rule.waitUntil(15000) { model.epgPrograms.isNotEmpty() }
        awaitHiddenImeAndFullScreen()
        android.util.Log.i("CinemaTVQa", "search-visible-ime-exit-complete; LIVE D-pad focus preserved")
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
        rule.onNodeWithText("自动选择").assertDoesNotExist()
        rule.onNode(hasText("音轨") and hasClickAction()).assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("自动选择").assertExists()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("自动选择").assertDoesNotExist()
        rule.onNode(hasText("音轨") and hasClickAction()).assertIsFocused()
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_BACK)
        rule.runOnIdle {
            assertEquals(Page.DETAIL, model.page)
            assertTrue((model.selected?.positionMs ?: 0) > 0)
            assertTrue((model.selected?.durationMs ?: 0) >= 29000)
            model.navigate(Page.HOME)
            model.updateReducedMotion(false)
        }
        rule.onNodeWithContentDescription(selected).assertIsFocused()
        recordFrameMetrics()
        val metrics = rule.activity.resources.displayMetrics
        // Resource display metrics can exclude the phone host's hidden navigation
        // inset. Record the actual full-screen Compose raster that was captured.
        android.util.Log.i("CinemaTVQa", "capture-begin=viewport")
        val viewport = captureWindow()
        try {
            android.util.Log.i("CinemaTVQa", "readback-complete=viewport width=${viewport.width} height=${viewport.height} bytes=${viewport.allocationByteCount}")
            File(directory, "viewport.json").writeText(JSONObject()
                .put("widthPx", viewport.width).put("heightPx", viewport.height)
                .put("resourceWidthPx", metrics.widthPixels).put("resourceHeightPx", metrics.heightPixels)
                .put("densityDpi", metrics.densityDpi).put("fixture", "Original vector artwork and synthetic H264/AAC")
                .put("scope", "TV Compose UI on Android emulator; physical TV and decoder certification excluded").toString(2))
        } finally {
            viewport.recycle()
        }
    }
    private fun recordFrameMetrics() {
        android.util.Log.i("CinemaTVQa", "frame sample begin")
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
        android.util.Log.i("CinemaTVQa", "frame sample complete: ${values.size} frames")
        assertTrue("Frame evidence must contain rendered frames", values.isNotEmpty())
        File(directory, "frames.json").writeText(JSONObject().put("frames", values.size)
            .put("p95Ms", values[((values.size - 1) * .95).toInt()]).put("p99Ms", values[((values.size - 1) * .99).toInt()])
            .put("over16_7ms", values.count { it > 16.7 }).put("renderer", "Android emulator / Lavapipe (CI configuration; runtime GPU not queried); informational, not hardware certification").toString(2))
    }
}
