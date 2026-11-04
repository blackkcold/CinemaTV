package dev.cinematv

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.components.*
import dev.cinematv.ui.tv.design.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Runtime smoke only, not visual fidelity, GPU timing, or physical-device certification. */
class GlassMaterialSmokeTest {
    @get:Rule val compose = createComposeRule()

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
        fun capture() { assertTrue(compose.onNodeWithTag("glass").captureToImage().width > 0) }
        capture()
        compose.runOnIdle { focused.value = true }; capture()
        compose.runOnIdle {
            assertFalse("Shader failed before intentional failure", failure.failed)
            if (Build.VERSION.SDK_INT >= 33) assertTrue(
                "Supported hardware test must exercise the lens draw, not silently skip it",
                diagnostics.successfulLensDraws > 0
            )
        }
        compose.runOnIdle { pressed.value = true }; capture()
        compose.runOnIdle { pressed.value = false; reduced.value = true }; capture()
        compose.runOnIdle { frame.value = null; reduced.value = false }; capture()
        compose.runOnIdle {
            failure.attempt<Unit> { throw IllegalArgumentException("simulated shader failure") }
            frame.value = GlassBackdropFrame(bitmap, Offset.Zero, Size(640f, 360f))
        }; capture()
        compose.runOnIdle { focused.value = false }; capture()
        // Coil owns real frames; this fixture remains alive through composition disposal.
    }
}
