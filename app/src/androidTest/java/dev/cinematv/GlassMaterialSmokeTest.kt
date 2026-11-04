package dev.cinematv

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.components.*
import dev.cinematv.ui.tv.design.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Runtime smoke only, not visual fidelity, GPU timing, or physical-device certification. */
class GlassMaterialSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Observe a real window frame without forceRedraw, PixelCopy or bitmap readback.
     * The latch wait runs on the instrumentation thread, never the UI thread.
     */
    private fun awaitHardwareFrame() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = compose.activity
        val thread = HandlerThread("glass-smoke-frame").apply { start() }
        val completed = CountDownLatch(1)
        val requestedAt = System.nanoTime()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= requestedAt) completed.countDown()
        }
        var registered = false
        try {
            instrumentation.runOnMainSync {
                val decor = activity.window.decorView
                check(!activity.isFinishing && !activity.isDestroyed && decor.isAttachedToWindow)
                check(decor.isHardwareAccelerated) { "Glass draw smoke requires a hardware window" }
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                registered = true
                decor.postInvalidateOnAnimation()
            }
            assertTrue("No completed hardware frame within 10 seconds", completed.await(10, TimeUnit.SECONDS))
        } finally {
            if (registered) instrumentation.runOnMainSync {
                activity.window.removeOnFrameMetricsAvailableListener(listener)
            }
            thread.quitSafely()
        }
    }

    @Test fun agslCompilesOnSupportedAndroidRuntime() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        RuntimeShader(STILL_LENS_AGSL)
    }

    @Test fun materialSurvivesFocusPressReducedMotionMissingArtAndFailure() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            val paint = android.graphics.Paint()
            for (x in 0 until 640 step 16) {
                paint.color = if (x % 32 == 0) android.graphics.Color.WHITE else android.graphics.Color.BLUE
                canvas.drawRect(x.toFloat(), 0f, (x + 16).toFloat(), 360f, paint)
            }
        }
        val frame = mutableStateOf<GlassBackdropFrame?>(GlassBackdropFrame(bitmap, Offset.Zero, Size(640f, 360f)))
        val focused = mutableStateOf(false)
        val pressed = mutableStateOf(false)
        val reduced = mutableStateOf(false)
        val failure = GlassFailureLatch()
        val diagnostics = GlassDrawDiagnostics()
        compose.setContent {
            CompositionLocalProvider(LocalGlassBackdrop provides frame.value,
                LocalGlassFailureLatch provides failure,
                LocalGlassDrawDiagnostics provides diagnostics,
                LocalTvEffects provides TvEffects(reduced.value, GlassQuality.ENHANCED)) {
                Box(Modifier.size(160.dp, 45.dp).testTag("glass")) {
                    GlassControlMaterial(focused.value, pressed.value, false, false, Modifier.matchParentSize())
                }
            }
        }
        fun render() { awaitHardwareFrame() }
        render()
        compose.runOnIdle { focused.value = true }; render()
        compose.runOnIdle {
            assertFalse("Shader failed before intentional failure", failure.failed)
            if (Build.VERSION.SDK_INT >= 33) assertTrue(
                "Supported hardware test must exercise the lens draw, not silently skip it",
                diagnostics.successfulLensDraws > 0
            )
        }
        val focusedDraws = compose.runOnIdle { diagnostics.successfulLensDraws }
        compose.runOnIdle { pressed.value = true }; render()
        val pressedDraws = compose.runOnIdle {
            if (Build.VERSION.SDK_INT >= 33) assertTrue(diagnostics.successfulLensDraws > focusedDraws)
            diagnostics.successfulLensDraws
        }
        compose.runOnIdle { pressed.value = false; reduced.value = true }; render()
        compose.runOnIdle { assertEquals(pressedDraws, diagnostics.successfulLensDraws) }
        compose.runOnIdle { frame.value = null; reduced.value = false }; render()
        compose.runOnIdle { assertEquals(pressedDraws, diagnostics.successfulLensDraws) }
        compose.runOnIdle {
            failure.attempt<Unit> { throw IllegalArgumentException("simulated shader failure") }
            frame.value = GlassBackdropFrame(bitmap, Offset.Zero, Size(640f, 360f))
        }; render()
        compose.runOnIdle {
            assertTrue(failure.failed)
            assertEquals(pressedDraws, diagnostics.successfulLensDraws)
            focused.value = false
        }; render()
        // Coil owns real frames; this fixture remains alive through composition disposal.
    }
}
