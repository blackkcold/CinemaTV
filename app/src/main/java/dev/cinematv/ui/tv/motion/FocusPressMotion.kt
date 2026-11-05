package dev.cinematv.ui.tv.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import dev.cinematv.ui.tv.design.LocalTvEffects

/** Values are read by layer/draw callbacks, never by the card's composition. */
@Stable
class FocusPressMotion internal constructor(
    private val scaleState: State<Float>,
    private val liftState: State<Float>,
    private val x: State<Float>,
    private val y: State<Float>,
    private val policy: PosterMotionPolicy,
) {
    val scale: Float get() = if (policy.enabled) scaleState.value else 1f
    val liftDp: Float get() = if (policy.enabled) liftState.value else 0f
    val depthX: Float get() = policy.depth(x.value, focused = true)
    val depthY: Float get() = policy.depth(y.value, focused = true)
    val rotationX: Float get() = -depthY * PosterMotionTokens.ROTATION_DEGREES
    val rotationY: Float get() = depthX * PosterMotionTokens.ROTATION_DEGREES
    val artworkOverscanDp: Float get() = policy.artworkOverscanDp
}

@Composable
fun rememberFocusPressMotion(
    focused: Boolean,
    pressed: Boolean,
    quality: PosterMotionQuality = PosterMotionQuality.LAYERED,
): FocusPressMotion {
    // ProductionGlassProvider already observes animator scale and lifecycle once.
    val policy = PosterMotionPolicy(LocalTvEffects.current.reducedMotion, quality)
    val scale = animateFloatAsState(
        policy.scale(focused, pressed),
        if (policy.enabled) spring(PosterMotionTokens.FOCUS_DAMPING, PosterMotionTokens.FOCUS_STIFFNESS) else snap(),
        label = "poster-focus-press",
    )
    val lift = animateFloatAsState(
        policy.lift(focused),
        if (policy.enabled) spring(PosterMotionTokens.FOCUS_DAMPING, PosterMotionTokens.FOCUS_STIFFNESS) else snap(),
        label = "poster-focus-lift",
    )
    val input = LocalPosterMotionInput.current
    // Unfocused cards do not subscribe to each key event. The new focus sees the
    // same root event as the previous one, even if a rail consumes it for traversal.
    val direction = if (focused && policy.depthEnabled) input?.direction() ?: PosterDirection.None else PosterDirection.None
    val depthSpec = if (policy.depthEnabled)
        spring<Float>(PosterMotionTokens.DEPTH_DAMPING, PosterMotionTokens.DEPTH_STIFFNESS) else snap()
    // Reversal and loss of focus settle from the current spring value/velocity.
    // Reduced Motion alone immediately disables geometry and snaps private state.
    val x = animateFloatAsState(direction.x, depthSpec, label = "poster-depth-x")
    val y = animateFloatAsState(direction.y, depthSpec, label = "poster-depth-y")
    return remember(scale, lift, x, y, policy) { FocusPressMotion(scale, lift, x, y, policy) }
}

/** No extra Offscreen cache around Coil bitmaps; artwork stays in its natural layer. */
fun Modifier.posterArtworkMotion(motion: FocusPressMotion): Modifier = graphicsLayer {
    // A static artwork crop is unchanged by Reduce Motion; only animated geometry stops.
    val distance = motion.artworkOverscanDp * density
    // Animations are clamped before projection, so this bounds even spring overshoot.
    translationX = motion.depthX * distance
    translationY = motion.depthY * distance
    val overscan = posterArtworkScale(size.width, size.height, distance)
    scaleX = overscan
    scaleY = overscan
}

/** Typography remains live and separate from the artwork layer. */
fun Modifier.posterTitleMotion(motion: FocusPressMotion): Modifier = graphicsLayer {
    translationX = motion.depthX * PosterMotionTokens.TITLE_PARALLAX_DP * density
    translationY = motion.depthY * PosterMotionTokens.TITLE_PARALLAX_DP * density
}
