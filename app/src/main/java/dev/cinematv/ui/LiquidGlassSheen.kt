package dev.cinematv.ui

import android.os.Build
import android.graphics.RuntimeShader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/**
 * This AGSL layer paints an optical highlight, not a video-surface blur.
 * Advanced GPU rendering is opt-in until certified on target TV hardware.
 */
val LocalOpticalGlass = compositionLocalOf { false }

private const val SHEEN_AGSL = """
uniform float2 resolution;
uniform float gain;
half4 main(float2 xy) {
    float2 uv = xy / max(resolution, float2(1.0, 1.0));
    float top = 1.0 - smoothstep(0.0, 0.11, uv.y);
    float bottom = smoothstep(0.88, 1.0, uv.y);
    float specular = exp(-pow((uv.x - 0.32) * 4.1, 2.0))
                   * exp(-pow((uv.y - 0.04) * 4.0, 2.0));
    float opacity = (top * 0.12 + bottom * 0.04 + specular * 0.07) * gain;
    return half4(1.0, 1.0, 1.0, opacity);
}
"""

@Composable
fun GlassSheen(active: Boolean, modifier: Modifier = Modifier) {
    if (!LocalOpticalGlass.current) return
    val intensity by animateFloatAsState(if (active) 1.0f else 0.45f, label = "optical-glass-sheen")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val runtimeShader = remember { RuntimeShader(SHEEN_AGSL) }
        val brush = remember(runtimeShader) { ShaderBrush(runtimeShader) }
        Canvas(modifier) {
            runtimeShader.setFloatUniform("resolution", size.width, size.height)
            runtimeShader.setFloatUniform("gain", intensity)
            drawRect(brush)
        }
    } else {
        Box(modifier.background(Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.09f * intensity), Color.Transparent)
        )))
    }
}
