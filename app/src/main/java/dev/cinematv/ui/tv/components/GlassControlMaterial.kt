package dev.cinematv.ui.tv.components

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.design.GlassRenderPolicy
import dev.cinematv.ui.tv.design.LocalTvEffects

/** Original V4 material. Only the background layer is refracted, never the label/icon. */
@Composable
fun GlassControlMaterial(focused: Boolean, pressed: Boolean, selected: Boolean,
    prominent: Boolean, modifier: Modifier = Modifier) {
    val frame = LocalGlassBackdrop.current
    val effects = LocalTvEffects.current
    val failure = LocalGlassFailureLatch.current
    val diagnostics = LocalGlassDrawDiagnostics.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Construct only after opt-in and focus. Size and hardware checks occur before drawing.
    val boundedStill = frame != null && !frame.bitmap.isRecycled &&
        frame.bitmap.width <= 1600 && frame.bitmap.height <= 900
    val candidate = effects.glass == dev.cinematv.ui.tv.design.GlassQuality.ENHANCED &&
        !effects.reducedMotion && focused && boundedStill && Build.VERSION.SDK_INT >= 33
    val lens = remember(candidate, frame, failure) {
        if (candidate && Build.VERSION.SDK_INT >= 33) failure.attempt { StillLens(frame!!) } else null
    }
    Canvas(modifier.onGloballyPositioned { coordinates = it }) {
        val radius = minOf(26.dp.toPx(), size.height / 2, size.width / 2)
        val emphasis = focused || selected || prominent
        val top = if (pressed) Color(0xE52E3C46) else if (emphasis) Color(0xD54B5A65) else Color(0xC52A343D)
        val bottom = if (pressed) Color(0xEE18232B) else if (emphasis) Color(0xE32A3741) else Color(0xE51B242B)
        // Always draw the safe base first, including no-art, software and shader-failure paths.
        drawRoundRect(Brush.verticalGradient(listOf(top, bottom)), cornerRadius = CornerRadius(radius))
        val coords = coordinates?.takeIf { it.isAttached }
        if (Build.VERSION.SDK_INT >= 33 && lens != null && frame != null && coords != null && GlassRenderPolicy.lensAllowed(effects.glass,
                Build.VERSION.SDK_INT, effects.reducedMotion, focused, !frame.bitmap.isRecycled,
                drawContext.canvas.nativeCanvas.isHardwareAccelerated, size.width / density,
                size.height / density, size.width * size.height, failure.failed)) {
            failure.attempt {
                val origin = coords.localToRoot(Offset.Zero)
                val far = coords.localToRoot(Offset(size.width, size.height))
                val controlScale = Offset((far.x - origin.x) / size.width, (far.y - origin.y) / size.height)
                lens.update(size, origin - frame.origin, controlScale, radius, density, pressed)
                drawRoundRect(lens.brush, cornerRadius = CornerRadius(radius))
                diagnostics?.let { it.successfulLensDraws++ }
            }
        }
        // Wide inner transmission band, restrained edge, no exterior bloom or shadow.
        val inset = 2.dp.toPx()
        if (size.width > inset * 2 && size.height > inset * 2) {
            drawRoundRect(Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (focused) .10f else .055f),
                Color.Transparent, Color(0xFF060C11).copy(alpha = .22f)
            )), topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)), style = Stroke(4.dp.toPx()))
        }
        drawRoundRect(Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (focused) .62f else .26f),
            Color.White.copy(alpha = .07f), Color.White.copy(alpha = if (pressed) .10f else .21f)
        ), end = Offset(size.width, size.height)), topLeft = Offset(.5.dp.toPx(), .5.dp.toPx()),
            size = Size((size.width - 1.dp.toPx()).coerceAtLeast(0f), (size.height - 1.dp.toPx()).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((radius - .5.dp.toPx()).coerceAtLeast(0f)),
            style = Stroke(if (focused) 1.25.dp.toPx() else .75.dp.toPx()))
    }
}

@RequiresApi(33)
private class StillLens(private val frame: GlassBackdropFrame) {
    private val shader = RuntimeShader(STILL_LENS_AGSL)
    val brush = ShaderBrush(shader)
    init {
        shader.setInputShader("still", BitmapShader(frame.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        })
        shader.setFloatUniform("bitmapSize", frame.bitmap.width.toFloat(), frame.bitmap.height.toFloat())
        shader.setFloatUniform("viewport", frame.viewport.width, frame.viewport.height)
    }
    fun update(size: Size, origin: Offset, controlScale: Offset, radius: Float, density: Float, pressed: Boolean) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("origin", origin.x, origin.y)
        shader.setFloatUniform("controlScale", controlScale.x, controlScale.y)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("rim", minOf(10f * density, radius * .65f))
        shader.setFloatUniform("depth", (if (pressed) 3.5f else 7f) * density)
    }
}

/** Capsule signed distance drives continuous edge displacement, with RGB sampling only
 * at the lens rim. Scene crop and both scrims match BackdropLayer's still presentation.
 * This does not sample composited UI, ambient fallback artwork or any video Surface.
 */
internal const val STILL_LENS_AGSL = """
uniform shader still;
uniform float2 bitmapSize;
uniform float2 viewport;
uniform float2 resolution;
uniform float2 origin;
uniform float2 controlScale;
uniform float radius;
uniform float rim;
uniform float depth;
half3 scene(float2 p) {
    float scale = max(viewport.x / bitmapSize.x, viewport.y / bitmapSize.y);
    float2 crop = (bitmapSize * scale - viewport) * 0.5;
    half3 c = still.eval((p + crop) / scale).rgb;
    float2 uv = clamp(p / viewport, 0.0, 1.0);
    float h = uv.x < 0.46 ? mix(0.94, 0.42, uv.x / 0.46)
        : mix(0.42, 0.0, (uv.x - 0.46) / 0.54);
    float v = uv.y < 0.30 ? mix(0.46, 0.0, uv.y / 0.30)
        : (uv.y < 0.63 ? mix(0.0, 0.32, (uv.y - 0.30) / 0.33)
        : mix(0.32, 1.0, (uv.y - 0.63) / 0.37));
    half3 ink = half3(9.0/255.0, 10.0/255.0, 13.0/255.0);
    return mix(mix(c, ink, half(h)), ink, half(v));
}
half4 main(float2 xy) {
    float2 p = xy - resolution * 0.5;
    float2 q = abs(p) - (resolution * 0.5 - radius);
    float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float2 n = q.x > 0.0 || q.y > 0.0 ? normalize(max(q, 0.0) + 0.0001) * sign(p)
        : (q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y)));
    float edge = 1.0 - smoothstep(0.0, rim, -distance);
    float bend = sin(edge * 1.5707963);
    float2 displaced = (xy - n * depth * bend) * controlScale + origin;
    float2 dispersion = n * bend * 0.38 * controlScale;
    half3 c = half3(scene(displaced + dispersion).r, scene(displaced).g, scene(displaced - dispersion).b);
    c = mix(c, half3(0.22, 0.28, 0.32), half(0.26 + edge * 0.13));
    float light = max(0.0, dot(n, normalize(float2(-0.45, -0.9))));
    c += half3(light * edge * 0.075);
    // Bound transmission so bright artwork cannot wash out the separate white label.
    c = min(c, half3(0.42));
    return half4(c, 1.0);
}
"""
