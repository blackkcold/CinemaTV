package dev.cinematv.ui.shared

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.cinematv.R

/**
 * Non-interactive, six-second A1 startup treatment. Place in a bounded decorative
 * area, never above controls or as a splash gate. No focus/input or completion
 * dependency: content can be used on the very first frame.
 *
 * Original CSS ease-in-out: slow -90..150 / 12s, medium -170..240 / 9s,
 * fast -260..360 / 6s, all on a 1920px canvas. Only the first forward sweep
 * is played; the logo stays fixed. Preblurred layers avoid runtime filters.
 */
@Composable
fun CinemaBrandSession(
    reducedMotion: Boolean,
    lowCost: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val capabilities = LocalBrandMotionCapabilities.current
    val deviceLowCost = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.let {
            brandDeviceNeedsStaticMotion(it.isLowRamDevice, it.memoryClass)
        } ?: true
    }
    val lowRam = capabilities?.let { brandDeviceNeedsStaticMotion(it.lowRam, it.memoryClassMb) } ?: deviceLowCost
    val animatorsEnabled = capabilities?.animatorsEnabled ?: ValueAnimator.areAnimatorsEnabled()
    var finished by rememberSaveable { mutableStateOf(false) }
    val elapsed = remember { Animatable(if (finished) 6000f else 0f) }
    val animate = !reducedMotion && !lowCost && !lowRam && animatorsEnabled && !finished
    LaunchedEffect(animate) {
        if (animate) {
            try {
                elapsed.animateTo(6000f, tween(6000, easing = LinearEasing))
            } finally {
                // Preference changes cancel motion permanently for this session.
                finished = true
            }
        }
    }
    CompositionLocalProvider(LocalBrandMotion provides BrandMotion(elapsed, !reducedMotion && !lowCost && !lowRam && animatorsEnabled, finished)) {
        content()
    }
}

/** Internal capability seam for runtime tests; production always reads Android services. */
internal data class BrandMotionCapabilities(val lowRam: Boolean, val memoryClassMb: Int, val animatorsEnabled: Boolean)
internal val LocalBrandMotionCapabilities = staticCompositionLocalOf<BrandMotionCapabilities?> { null }

internal data class BrandMotion(
    val elapsed: Animatable<Float, AnimationVector1D>,
    val enabled: Boolean,
    val finished: Boolean,
)
internal val LocalBrandMotion = staticCompositionLocalOf<BrandMotion?> { null }

/** Header-only, decorative treatment: no overlay and no new focus target. */
@Composable
fun CinemaBrandStartup(modifier: Modifier = Modifier) {
    val motion = LocalBrandMotion.current
    if (motion == null || !motion.enabled) {
        CinemaBrandLockup(modifier)
        return
    }
    val ease = remember { CubicBezierEasing(0.42f, 0f, 0.58f, 1f) }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val layers = listOf(
            BrandTrail(R.drawable.cinema_brand_slow, -90f, 150f, 12000f),
            BrandTrail(R.drawable.cinema_brand_medium, -170f, 240f, 9000f),
            BrandTrail(R.drawable.cinema_brand_fast, -260f, 360f, 6000f),
        )
        layers.forEach { trail ->
            Image(
                painter = painterResource(trail.resource),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clearAndSetSemantics { }.graphicsLayer {
                    // Read animation state in the layer, not composition. Once
                    // finished, retain the final frame without an active clock.
                    val t = motion.elapsed.value
                    val offset = trail.start + (trail.end - trail.start) * ease.transform((t / trail.duration).coerceIn(0f, 1f))
                    translationX = offset / 1920f * size.width
                },
            )
        }
        CinemaBrandLockup(Modifier.fillMaxSize())
    }
}

private data class BrandTrail(val resource: Int, val start: Float, val end: Float, val duration: Float)
