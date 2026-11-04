package dev.cinematv.ui.tv.design

import androidx.compose.runtime.staticCompositionLocalOf

enum class GlassQuality(val title: String) {
    AUTO("自动"), BASIC("基础"), BACKDROP("背景磨砂"), ENHANCED("镜片折射")
}

data class TvEffects(val reducedMotion: Boolean = false, val glass: GlassQuality = GlassQuality.BASIC) {
    val focusMillis: Int get() = if (reducedMotion) 0 else 180
    val sceneMillis: Int get() = if (reducedMotion) 0 else 380
    val focusScale: Float get() = if (reducedMotion) 1f else 1.065f
}

/** Conservative, deterministic policy; enhanced mode is an explicit opt-in. */
fun resolveGlassQuality(requested: GlassQuality, lowRam: Boolean, api: Int, reducedMotion: Boolean): GlassQuality =
    when {
        lowRam || requested == GlassQuality.BASIC -> GlassQuality.BASIC
        requested == GlassQuality.ENHANCED && api >= 33 && !reducedMotion -> GlassQuality.ENHANCED
        api < 31 -> GlassQuality.BASIC
        else -> GlassQuality.BACKDROP
    }

val LocalTvEffects = staticCompositionLocalOf { TvEffects() }
