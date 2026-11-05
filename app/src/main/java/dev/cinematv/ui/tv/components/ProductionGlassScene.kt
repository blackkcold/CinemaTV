package dev.cinematv.ui.tv.components

import android.os.Build
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import com.kyant.backdrop.BackdropFailureState
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberSafeLayerBackdrop
import dev.cinematv.ui.LocalOpticalGlass
import dev.cinematv.ui.tv.design.*

/** A scene-only recording. Its provider must never contain this modifier's consumers. */
class ProductionGlassScene internal constructor(val backdrop: LayerBackdrop, val owner: View, val failure: BackdropFailureState?)
val LocalProductionGlassScene = staticCompositionLocalOf<ProductionGlassScene?> { null }
val LocalProductionGlassFailure = staticCompositionLocalOf<BackdropFailureState?> { null }
val LocalGlassStatus = staticCompositionLocalOf { "基础材质：未提供背景采样源。" }

/** Passive test observations never drive rendering, admission, focus or invalidation. */
internal data class ProductionGlassObservation(
    val requested: GlassQuality,
    val environment: GlassEnvironment,
    val decision: GlassDecision,
    val actualMode: GlassRenderMode,
    val failed: Boolean,
)
internal val LocalProductionGlassObserver = staticCompositionLocalOf<((ProductionGlassObservation) -> Unit)?> { null }
internal val LocalProductionSceneDecoded = staticCompositionLocalOf<((String?) -> Unit)?> { null }

@Composable
fun ProductionGlassProvider(requested: GlassQuality, reducedMotion: Boolean, content: @Composable () -> Unit) {
    val environment = rememberGlassEnvironment(reducedMotion)
    val decision = resolveProductionGlass(requested, environment)
    val failure = remember { BackdropFailureState() }
    val mode = if (failure.failed) GlassRenderMode.BASIC else decision.mode
    val quality = when (mode) {
        GlassRenderMode.BASIC -> GlassQuality.BASIC
        GlassRenderMode.BLUR -> GlassQuality.BACKDROP
        GlassRenderMode.FULL -> GlassQuality.ENHANCED
    }
    val observer = LocalProductionGlassObserver.current
    SideEffect { observer?.invoke(ProductionGlassObservation(requested, environment, decision, mode, failure.failed)) }
    val status = if (failure.failed) "效果初始化或绘制失败，本次运行已回落基础材质。" else decision.reason.description
    CompositionLocalProvider(
        LocalTvEffects provides TvEffects(!decision.animationsEnabled, quality),
        LocalOpticalGlass provides (mode == GlassRenderMode.FULL),
        LocalProductionGlassFailure provides failure,
        LocalGlassStatus provides status,
        content = content,
    )
}

@Composable
fun rememberProductionGlassScene(enabled: Boolean = true): ProductionGlassScene? {
    val effects = LocalTvEffects.current
    // No effect/recording nodes below31 or in the low-cost tier. The root may keep
    // its lightweight scene drawing without retaining a full-resolution GPU layer.
    if (!enabled || Build.VERSION.SDK_INT < 31 || effects.glass == GlassQuality.BASIC) return null
    val owner = LocalView.current
    val failure = LocalProductionGlassFailure.current ?: return null
    val backdrop = rememberSafeLayerBackdrop(failure) ?: return null
    return remember(backdrop, owner, failure) { ProductionGlassScene(backdrop, owner, failure) }
}

fun Modifier.recordProductionGlass(scene: ProductionGlassScene?): Modifier =
    if (scene == null) this else layerBackdrop(scene.backdrop, failureState = scene.failure)

@Composable
internal fun currentProductionGlassScene(): ProductionGlassScene? {
    val scene = LocalProductionGlassScene.current
    val owner = LocalView.current
    // Android Dialog has another Compose owner/window even though locals are inherited.
    // Replay across these roots is not a validated source, so use the safe material.
    return scene?.takeIf { it.owner === owner && owner.isHardwareAccelerated && Build.VERSION.SDK_INT >= 31 }
}
