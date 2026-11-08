package dev.cinematv

import android.content.res.Configuration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Release-instrumentation acceptance harness. It attaches to the already-installed
 * production candidate and only observes Window FrameMetrics; it never rebuilds or
 * replaces the target APK. Run with -PcinematvNativeTvAcceptance=true on a TV image.
 */
@RunWith(AndroidJUnit4::class)
class TvNativePerformanceAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun productionEntryProducesValidFrameSamples() {
        val tv = (rule.activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        org.junit.Assume.assumeTrue("T-gate harness requires a television UI", tv)
        rule.waitForIdle()
        val args = InstrumentationRegistry.getArguments()
        val sourceSha = args.getString("sourceSha").orEmpty()
        val apkSha256 = args.getString("apkSha256").orEmpty()
        val renderer = args.getString("renderer").orEmpty()
        val materialTier = args.getString("materialTier").orEmpty()
        assertTrue("T-gate requires the exact source SHA", sourceSha.matches(Regex("[0-9a-f]{40}")))
        assertTrue("T-gate requires the installed APK SHA-256", apkSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue("T-gate requires the actual renderer", renderer.isNotBlank())
        assertTrue("T-gate requires the actual material tier", materialTier.isNotBlank())
        val recorder = FrameTimingRecorder(rule.activity)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val route = "HOME"
        repeat(6) { index ->
            val start = System.nanoTime()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            rule.waitForIdle()
            recorder.mark(JSONObject().put("id", index).put("key", "DPAD_DOWN")
                .put("route", route).put("receivedNs", start), start)
        }
        val out = File(rule.activity.getExternalFilesDir(null),
            "native-tv-perf/${UUID.randomUUID()}.json")
        recorder.finish("acceptance", JSONObject()
            .put("protocolVersion", 3)
            .put("materialTier", materialTier)
            .put("sourceSha", sourceSha)
            .put("apkSha256", apkSha256)
            .put("renderer", renderer), out)
        assertTrue("The harness must write a run document", out.isFile)
        val run = JSONObject(out.readText())
        assertTrue("The run must contain renderer frames", run.getJSONArray("frames").length() > 0)
        assertEquals(3, run.getInt("protocolVersion"))
        assertEquals(3, run.getInt("schema"))
        assertEquals(sourceSha, run.getString("sourceSha"))
        assertEquals(apkSha256, run.getString("apkSha256"))
        assertTrue(run.getJSONArray("inputs").length() > 0)
    }
}
