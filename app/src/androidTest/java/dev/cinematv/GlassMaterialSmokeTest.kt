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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
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

    /** Arm before the measured mutation: waiting for idle first can consume its only
     * frame, and invalidating DecorView alone may leave cached Compose content clean.
     * No force-redraw, PixelCopy or bitmap readback is used.
     */
    private fun renderStage(stage: String, materialDraws: AtomicInteger, mutation: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = compose.activity
        fun foregroundOnMain(): Boolean {
            val decor = activity.window.decorView
            return !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() &&
                decor.isAttachedToWindow && decor.isShown && decor.isHardwareAccelerated
        }
        fun foreground(): Boolean {
            var ready = false
            instrumentation.runOnMainSync {
                ready = foregroundOnMain()
            }
            return ready
        }
        // Wait for this test activity, never dismiss or steal focus from another window.
        compose.waitUntil(timeoutMillis = 10_000) { foreground() }
        val previousDraws = materialDraws.get()
        val thread = HandlerThread("glass-smoke-frame").apply { start() }
        val completed = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val lastVsync = AtomicLong(-1)
        val requestedAt = System.nanoTime()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            callbacks.incrementAndGet()
            val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            lastVsync.set(vsync)
            if (vsync >= requestedAt) completed.countDown()
        }
        var registered = false
        try {
            instrumentation.runOnMainSync {
                check(foregroundOnMain()) { "$stage: hardware test activity lost foreground before mutation" }
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                registered = true
            }
            android.util.Log.i("CinemaTVGlassQa", "Armed frame stage=$stage")
            mutation() // Includes initial setContent; listener is already installed.
            compose.waitForIdle()
            val rendered = completed.await(10, TimeUnit.SECONDS)
            check(foreground()) { "$stage: test activity lost foreground while awaiting frame" }
            assertTrue("$stage: no fresh hardware frame in 10s; callbacks=${callbacks.get()}, " +
                "lastVsync=${lastVsync.get()}, requestedAt=$requestedAt", rendered)
            assertTrue("$stage: material did not draw on hardware after mutation", materialDraws.get() > previousDraws)
            android.util.Log.i("CinemaTVGlassQa", "Completed frame stage=$stage callbacks=${callbacks.get()} materialDraws=${materialDraws.get()}")
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
        val materialDraws = AtomicInteger()
        fun render(stage: String, mutation: () -> Unit) = renderStage(stage, materialDraws, mutation)
        render("initial") {
            compose.setContent {
                CompositionLocalProvider(LocalGlassBackdrop provides frame.value,
                    LocalGlassFailureLatch provides failure,
                    LocalGlassDrawDiagnostics provides diagnostics,
                    LocalTvEffects provides TvEffects(reduced.value, GlassQuality.ENHANCED)) {
                    Box(Modifier.size(160.dp, 45.dp).testTag("glass")) {
                        GlassControlMaterial(focused.value, pressed.value, false, false,
                            Modifier.matchParentSize().drawWithContent {
                                drawContent()
                                if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) materialDraws.incrementAndGet()
                            })
                    }
                }
            }
        }
        render("focused") { compose.runOnIdle { focused.value = true } }
        compose.runOnIdle {
            assertFalse("Shader failed before intentional failure", failure.failed)
            if (Build.VERSION.SDK_INT >= 33) assertTrue(
                "Supported hardware test must exercise the lens draw, not silently skip it",
                diagnostics.successfulLensDraws > 0
            )
        }
        val focusedDraws = compose.runOnIdle { diagnostics.successfulLensDraws }
        render("pressed") { compose.runOnIdle { pressed.value = true } }
        val pressedDraws = compose.runOnIdle {
            if (Build.VERSION.SDK_INT >= 33) assertTrue(diagnostics.successfulLensDraws > focusedDraws)
            diagnostics.successfulLensDraws
        }
        render("reduced-motion") { compose.runOnIdle { pressed.value = false; reduced.value = true } }
        compose.runOnIdle { assertEquals(pressedDraws, diagnostics.successfulLensDraws) }
        render("missing-art") { compose.runOnIdle { frame.value = null; reduced.value = false } }
        compose.runOnIdle { assertEquals(pressedDraws, diagnostics.successfulLensDraws) }
        render("latched-failure") { compose.runOnIdle {
            failure.attempt<Unit> { throw IllegalArgumentException("simulated shader failure") }
            frame.value = GlassBackdropFrame(bitmap, Offset.Zero, Size(640f, 360f))
        } }
        render("unfocused") { compose.runOnIdle {
            assertTrue(failure.failed)
            assertEquals(pressedDraws, diagnostics.successfulLensDraws)
            focused.value = false
        } }
        // Coil owns real frames; this fixture remains alive through composition disposal.
    }
}
