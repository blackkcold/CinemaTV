package dev.cinematv

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.BackdropFailureState
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

    @Test fun materialSurvivesFocusPressReducedMotionMissingArtAndFailure() {
        val hasScene = mutableStateOf(true)
        val phase = mutableStateOf(0f)
        val focused = mutableStateOf(false)
        val pressed = mutableStateOf(false)
        val reduced = mutableStateOf(false)
        val mode = mutableStateOf(GlassQuality.ENHANCED)
        val failure = BackdropFailureState()
        val diagnostics = GlassDrawDiagnostics()
        val materialDraws = AtomicInteger()
        fun render(stage: String, mutation: () -> Unit) = renderStage(stage, materialDraws, mutation)
        render("initial") {
            compose.setContent {
                CompositionLocalProvider(LocalProductionGlassFailure provides failure,
                    LocalGlassDrawDiagnostics provides diagnostics,
                    LocalTvEffects provides TvEffects(reduced.value, mode.value)) {
                    val scene = rememberProductionGlassScene(hasScene.value)
                    CompositionLocalProvider(LocalProductionGlassScene provides scene) {
                        Box(Modifier.size(640.dp, 360.dp)) {
                            // A real recorded Compose scene, never a copied framebuffer.
                            Canvas(Modifier.matchParentSize().recordProductionGlass(scene)) {
                                drawRect(Color(0xFF203C57))
                                for (x in 0..20) drawLine(Color.White, Offset(x * 24f + phase.value, 0f),
                                    Offset(x * 24f + phase.value, size.height), strokeWidth = 3f)
                            }
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
            }
        }
        render("focused") { compose.runOnIdle { focused.value = true } }
        compose.runOnIdle {
            assertFalse("Effect failed before intentional failure", failure.failed)
            if (Build.VERSION.SDK_INT >= 33) assertTrue("Full must actually draw", diagnostics.successfulLensDraws > 0)
            else if (Build.VERSION.SDK_INT >= 31) assertTrue("Blur must actually draw", diagnostics.blurDraws > 0)
            else assertTrue("Old Android must use safe material", diagnostics.basicDraws > 0)
        }
        val beforePress = compose.runOnIdle { diagnostics.successfulLensDraws }
        render("pressed") { compose.runOnIdle { pressed.value = true; phase.value = 7f } }
        compose.runOnIdle { if (Build.VERSION.SDK_INT >= 33) assertTrue(diagnostics.successfulLensDraws > beforePress) }
        render("blur") { compose.runOnIdle { mode.value = GlassQuality.BACKDROP; pressed.value = false } }
        compose.runOnIdle { if (Build.VERSION.SDK_INT >= 31) assertTrue(diagnostics.blurDraws > 0) }
        render("full-return") { compose.runOnIdle { mode.value = GlassQuality.ENHANCED; pressed.value = true } }
        val beforeReduced = compose.runOnIdle { diagnostics.successfulLensDraws }
        render("reduced-motion") { compose.runOnIdle { reduced.value = true; phase.value = 14f; focused.value = false } }
        compose.runOnIdle {
            // Reduced motion disables geometry animation, not the selected optical material.
            if (Build.VERSION.SDK_INT >= 33) assertTrue(diagnostics.successfulLensDraws > beforeReduced)
        }
        val beforeMissing = compose.runOnIdle { diagnostics.successfulLensDraws }
        render("missing-art") { compose.runOnIdle {
            hasScene.value = false; reduced.value = false; pressed.value = false
        } }
        compose.runOnIdle { assertEquals(beforeMissing, diagnostics.successfulLensDraws) }
        render("scene-restored") { compose.runOnIdle { hasScene.value = true; focused.value = true } }
        val beforeFailure = compose.runOnIdle { diagnostics.successfulLensDraws }
        render("latched-failure") { compose.runOnIdle {
            // Fail an active optical source, rather than an already identical Basic
            // frame. Press is also a visible native interaction on the API26 tier.
            failure.fail("test", IllegalArgumentException("simulated shader failure"))
            pressed.value = true
        } }
        render("unfocused") { compose.runOnIdle {
            assertTrue(failure.failed)
            assertEquals(beforeFailure, diagnostics.successfulLensDraws)
            focused.value = false
        } }
    }
}
