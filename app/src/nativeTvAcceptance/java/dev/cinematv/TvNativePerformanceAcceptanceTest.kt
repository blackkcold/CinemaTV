package dev.cinematv

import android.view.KeyEvent
import android.view.Window
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.perf.NativeGlassProbe
import dev.cinematv.perf.NativePerfProbe
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
        }
    }

    private class ProbeCallback(
        private val delegate: Window.Callback,
        private val recorder: FrameTimingRecorder,
        private val lastReceipt: AtomicLong,
        private val lastInputId: AtomicLong,
        private val route: () -> String,
    ) : Window.Callback by delegate {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
                return delegate.dispatchKeyEvent(event)
            }
            val receivedNs = System.nanoTime()
            val inputId = recorder.beginInput(event.keyCode, route(), receivedNs)
            val handled = delegate.dispatchKeyEvent(event)
            // The key is consumed synchronously on the UI thread; any focus or page state it
            // caused is already written when dispatch returns. Recomposition and its frame
            // follow, so this is a floor for the responding frame, never a ceiling.
            recorder.attachState(inputId, System.nanoTime(), route(), null, "dispatch", handled)
            lastReceipt.set(receivedNs)
            lastInputId.set(inputId)
            return handled
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

        val recorder = FrameTimingRecorder(rule.activity)
        val lastReceipt = AtomicLong(-1L)
        val lastInputId = AtomicLong(-1L)
        val originalCallback = AtomicReference<Window.Callback?>(null)
        NativePerfProbe.reset()
        NativePerfProbe.enabled = true
        NativeGlassProbe.reset()
        // Warm-up outside the sample: the first press after launch only establishes focus and
        // may legitimately draw nothing. It is never recorded as evidence.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
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
            val keys = intArrayOf(
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
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
        for (index in 0 until inputs.length()) {
            if (inputs.getJSONObject(index).optString("responseOutcome") == "paired") paired++
        }
        assertTrue("At least five of six inputs must pair with their own frame", paired >= 5)
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
