package dev.cinematv

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Window
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.Page
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import dev.cinematv.data.library.LocalMediaLibrary
import dev.cinematv.perf.NativeGlassProbe
import dev.cinematv.perf.NativePerfProbe
import dev.cinematv.ui.tv.design.GlassQuality
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Production-entry acceptance for the native TV measurement lane.
 *
 * The receipt is taken at `Window.Callback.dispatchKeyEvent`, the caused state is read from
 * [NativePerfProbe] after composition apply, and pairing happens in the recorder once every
 * FrameMetrics callback has drained. An input that changed nothing is recorded as such and
 * can never be paired with a later frame.
 */
@RunWith(AndroidJUnit4::class)
class TvNativePerformanceAcceptanceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        /** Must run before the activity rule composes: MainActivity installs the probes at onCreate. */
        @JvmStatic
        @BeforeClass
        fun enableProbesBeforeTheActivityLaunches() {
            NativePerfProbe.enabled = true
            NativeGlassProbe.enabled = true
            seedFocusableHomeContent()
        }

        /**
         * A fresh install has no rails, so HOME exposes only the empty-state action and the
         * floating navigation: D-pad presses can be handled without an applied content focus
         * key, which makes an input-to-state receipt impossible. Seed a small local history
         * so the production HOME renders real focus targets. Synthetic data stays on the
         * disposable emulator; it is never shipped.
         */
        private fun seedFocusableHomeContent() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val library = LocalMediaLibrary(context)
            runBlocking {
                withTimeout(30_000) {
                    library.initialize()
                    repeat(4) { index ->
                        val item = MediaItem(
                            id = "acceptance-focus-$index",
                            title = "验收片源 ${index + 1}",
                            year = "2026",
                            category = "剧情",
                            source = TvSource("acceptance", "验收片源", "http://127.0.0.1/acceptance"),
                            episodes = listOf("http://127.0.0.1/stream.mp4"),
                        )
                        library.cacheItem(item)
                        library.saveProgress(item, 60_000L, 0, 600_000L)
                    }
                    val history = library.snapshot.history()
                    android.util.Log.i("CinemaTVQa", "seedHistory=${history.size} titles=${history.map { it.title }}")
                    check(history.isNotEmpty()) { "Seeded history is empty" }
                }
            }
        }
    }

    private class ProbeCallback(
        private val delegate: Window.Callback,
        private val recorder: FrameTimingRecorder,
        private val lastReceipt: AtomicLong,
        private val lastInputId: AtomicLong,
        private val route: () -> String,
        private val handler: Handler = Handler(Looper.getMainLooper()),
    ) : Window.Callback by delegate {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
                return delegate.dispatchKeyEvent(event)
            }
            val receivedNs = System.nanoTime()
            val inputId = recorder.beginInput(event.keyCode, route(), receivedNs)
            val handled = delegate.dispatchKeyEvent(event)
            val fallbackNs = System.nanoTime()
            // The applied focus/page state is written by composition after dispatch. Poll the
            // probe briefly, then fall back to the dispatch floor; never back-fill a later frame.
            handler.postDelayed({ attachProbeState(inputId, receivedNs, handled, fallbackNs) }, 80L)
            lastReceipt.set(receivedNs)
            lastInputId.set(inputId)
            return handled
        }

        private fun attachProbeState(inputId: Long, receivedNs: Long, handled: Boolean, fallbackNs: Long) {
            val state = NativePerfProbe.firstAfter(receivedNs)
            if (state != null) {
                recorder.attachState(inputId, state.appliedNs, state.page, state.focusKey, "probe", handled)
            } else if (System.nanoTime() - fallbackNs < 240_000_000L) {
                handler.postDelayed({ attachProbeState(inputId, receivedNs, handled, fallbackNs) }, 80L)
            } else {
                recorder.attachState(inputId, fallbackNs, route(), null, "dispatch", handled)
            }
        }
    }

    @Test
    fun productionEntryPairsEachInputWithTheFrameThatCarriedItsState() {
        val tv = (rule.activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        assumeTrue("The native TV lane requires a television UI", tv)
        rule.waitForIdle()

        val args = InstrumentationRegistry.getArguments()
        val sourceSha = args.getString("sourceSha").orEmpty()
        val apkSha256 = args.getString("apkSha256").orEmpty()
        val renderer = args.getString("renderer").orEmpty()
        assertTrue("The lane requires the exact source SHA", sourceSha.matches(Regex("[0-9a-f]{40}")))
        assertTrue("The lane requires the installed APK SHA-256", apkSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue("The lane requires the actual renderer", renderer.isNotBlank())

        // The persisted quality decides which tier is requested. Without this the run only ever
        // exercises AUTO -> BLUR, so a tier-direct FULL assertion would prove nothing.
        val requestedQuality = args.getString("glassQuality").orEmpty().uppercase()
        if (requestedQuality.isNotBlank()) {
            val quality = runCatching { GlassQuality.valueOf(requestedQuality) }
                .getOrElse { error("Unknown glassQuality=$requestedQuality") }
            rule.runOnIdle {
                ViewModelProvider(rule.activity)[CinemaViewModel::class.java].updateGlassQuality(quality)
            }
            rule.waitUntil(10_000) { NativeGlassProbe.observation()?.requested == quality }
            rule.waitForIdle()
        }

        rule.runOnUiThread {
            ViewModelProvider(rule.activity)[CinemaViewModel::class.java].navigate(Page.HOME)
        }
        rule.waitForIdle()
        val recorder = FrameTimingRecorder(rule.activity)
        val lastReceipt = AtomicLong(-1L)
        val lastInputId = AtomicLong(-1L)
        val originalCallback = AtomicReference<Window.Callback?>(null)
        NativePerfProbe.reset()
        NativePerfProbe.enabled = true
        NativeGlassProbe.reset()
        // Warm-up outside the sample: put focus on a real seeded card so the measured
        // right/left presses change the model focus instead of only local nav focus.
        rule.waitUntil(15_000) {
            rule.onAllNodesWithContentDescription("验收片源 1").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("验收片源 1").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        Thread.sleep(300)
        rule.runOnUiThread {
            val window = rule.activity.window
            originalCallback.set(window.callback)
            window.callback = ProbeCallback(
                window.callback, recorder, lastReceipt, lastInputId,
                { NativePerfProbe.snapshot().lastOrNull()?.page ?: "HOME" },
            )
        }

        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            // Alternate directions so a short page still has a reachable target for most
            // presses; unhandled presses are excluded by the host protocol, not counted.
            // Enter the seeded history rail, then move between distinct cards. Vertical
            // down/up alone can keep the same card focused, which changes no model state and
            // cannot produce an input-to-state receipt.
            val keys = intArrayOf(
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
            )
            keys.forEachIndexed { index, keyCode ->
                lastReceipt.set(-1L)
                lastInputId.set(-1L)
                instrumentation.sendKeyDownUpSync(keyCode)
                check(awaitReceipt(lastReceipt) > 0L) { "input $index was never dispatched" }
                // One in-flight input: close this window before the next receipt so two
                // inputs can never claim the same frame or overlap each other.
                Thread.sleep(FrameTimingRecorder.ACTIVE_NS / 1_000_000 + 50)
            }
        } finally {
            NativePerfProbe.enabled = false
            rule.runOnUiThread { originalCallback.get()?.let { rule.activity.window.callback = it } }
        }

        val tierDirect = args.getString("tierDirect").orEmpty().uppercase()
        val drawnTier = NativeGlassProbe.drawnTier()
        val policyTier = NativeGlassProbe.observation()?.actualMode?.name
        val sourcePresent = NativeGlassProbe.sourcePresentDraws() > 0
        if (tierDirect.isNotBlank()) {
            assertEquals(
                "Directed tier $tierDirect was not drawn (policy=$policyTier, counts=${NativeGlassProbe.drawCounts()})",
                tierDirect,
                drawnTier,
            )
        }
        val out = File(rule.activity.getExternalFilesDir(null), "native-tv-perf/${UUID.randomUUID()}.json")
        recorder.finish("acceptance", JSONObject()
            .put("schema", 4)
            .put("purpose", if (tierDirect.isBlank()) "input-causality" else "tier-direct")
            .put("sourceSha", sourceSha)
            .put("apkSha256", apkSha256)
            .put("renderer", renderer)
            .put("sourceReady", sourcePresent)
            .put("policyTier", policyTier ?: JSONObject.NULL)
            .put("requestedTier", if (tierDirect.isBlank()) JSONObject.NULL else tierDirect)
            .put("actualTier", drawnTier ?: JSONObject.NULL)
            .put("drawnTiers", JSONObject(NativeGlassProbe.drawCounts())), out)
        assertTrue("The lane must write a run document", out.isFile)

        val run = JSONObject(out.readText())
        assertEquals(4, run.getInt("schema"))
        assertEquals(4, run.getInt("protocolVersion"))
        assertEquals(sourceSha, run.getString("sourceSha"))
        assertEquals(apkSha256, run.getString("apkSha256"))
        val inputs = run.getJSONArray("inputs")
        var paired = 0
        var observedFocusKey = false
        for (index in 0 until inputs.length()) {
            val input = inputs.getJSONObject(index)
            if (input.optString("responseOutcome") == "paired") paired++
            if (input.optString("stateFocusKey").isNotBlank()) observedFocusKey = true
        }
        assertTrue("At least five of six inputs must pair with their own frame", paired >= 5)
        assertTrue("The lane must observe at least one applied focus key", observedFocusKey)
    }

    private fun awaitReceipt(value: AtomicLong): Long {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val receipt = value.get()
            if (receipt > 0L) return receipt
            Thread.sleep(8)
        }
        return -1L
    }
}
