package dev.cinematv.ui.tv.motion

import androidx.compose.runtime.staticCompositionLocalOf

/** Optional passive instrumentation. No observer in production and no state/invalidation here. */
internal data class PosterMotionObservation(
    val presentationKey: String,
    val focused: Boolean,
    val pressed: Boolean,
    val selected: Boolean,
    val loading: Boolean,
    val failed: Boolean,
    val scale: Float,
    val liftDp: Float,
    val rotationX: Float,
    val rotationY: Float,
    val artworkX: Float,
    val artworkY: Float,
)
internal interface PosterMotionObserver {
    fun onFrame(observation: PosterMotionObservation)
    fun onComposition(presentationKey: String) {}
}
internal val LocalPosterMotionObserver = staticCompositionLocalOf<PosterMotionObserver?> { null }
