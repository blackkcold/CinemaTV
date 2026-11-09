package dev.cinematv

import android.view.KeyEvent
import android.view.Window
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.perf.NativePerfProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    private class ProbeCallback(
        private val delegate: Window.Callback,
        private val onReceipt: (KeyEvent, Long) -> Unit,
    ) : Window.Callback by delegate {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onReceipt(event, System.nanoTime())
            }
            return delegate.dispatchKeyEvent(event)
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
        rule.runOnUiThread {
            val window = rule.activity.window
            originalCallback.set(window.callback)
            window.callback = ProbeCallback(window.callback) { event, receivedNs ->
                lastReceipt.set(receivedNs)
                val page = NativePerfProbe.snapshot().lastOrNull()?.page ?: "HOME"
                lastInputId.set(recorder.beginInput(event.keyCode, page, receivedNs))
            }
        }
        NativePerfProbe.reset()
        NativePerfProbe.enabled = true

        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            repeat(6) {
                lastReceipt.set(-1L)
                lastInputId.set(-1L)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                val receipt = awaitReceipt(lastReceipt)
                if (receipt < 0L) return@repeat
                val applied = awaitState(receipt) ?: return@repeat
                recorder.attachState(lastInputId.get(), applied.appliedNs, applied.page, applied.focusKey)
            }
        } finally {
            NativePerfProbe.enabled = false
            rule.runOnUiThread { originalCallback.get()?.let { rule.activity.window.callback = it } }
        }

        val out = File(rule.activity.getExternalFilesDir(null), "native-tv-perf/${UUID.randomUUID()}.json")
        recorder.finish("acceptance", JSONObject()
            .put("schema", 4)
            .put("purpose", "input-causality")
            .put("sourceSha", sourceSha)
            .put("apkSha256", apkSha256)
            .put("renderer", renderer)
            .put("sourceReady", true)
            .put("requestedTier", args.getString("materialTier").orEmpty())
            .put("actualTier", JSONObject.NULL), out)
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
        assertTrue("At least one input must pair with a frame that carried its state", paired > 0)
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

    private fun awaitState(receipt: Long): NativePerfProbe.StateEvent? {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            NativePerfProbe.firstAfter(receipt)?.let { return it }
            Thread.sleep(8)
        }
        return null
    }
}
