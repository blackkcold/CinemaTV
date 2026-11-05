package dev.cinematv.ui.tv.motion

/** Production tuning approved in the motion lab, not claimed Apple timing. */
object PosterMotionTokens {
    const val FOCUS_SCALE = 1.06f
    const val FOCUSED_PRESS_SCALE = 1.015f
    const val TOUCH_PRESS_SCALE = .97f
    const val FOCUS_LIFT_DP = -3f
    const val FOCUS_DAMPING = .78f
    const val FOCUS_STIFFNESS = 460f
    const val DEPTH_DAMPING = .86f
    const val DEPTH_STIFFNESS = 520f
    const val ROTATION_DEGREES = 3f
    const val ARTWORK_PARALLAX_DP = 4f
    const val TITLE_PARALLAX_DP = 7f
    const val DIRECTION_HINT_MILLIS = 110L
}

/** Independent of glass rendering. No RAM/core-count heuristic stands in for a GPU measurement. */
enum class PosterMotionQuality { FOCUS_ONLY, LAYERED }

data class PosterDirection(val x: Float = 0f, val y: Float = 0f) {
    companion object { val None = PosterDirection() }
}

/** [reducedMotion] includes the shared root's system animator, power and environment guards. */
data class PosterMotionPolicy(
    val reducedMotion: Boolean,
    val quality: PosterMotionQuality = PosterMotionQuality.LAYERED,
) {
    val enabled: Boolean get() = !reducedMotion
    val depthEnabled: Boolean get() = enabled && quality == PosterMotionQuality.LAYERED
    // Static composition/crop is independent of the Reduce Motion switch.
    val artworkOverscanDp: Float get() =
        if (quality == PosterMotionQuality.LAYERED) PosterMotionTokens.ARTWORK_PARALLAX_DP else 0f

    fun scale(focused: Boolean, pressed: Boolean): Float = when {
        !enabled -> 1f
        pressed -> if (focused) PosterMotionTokens.FOCUSED_PRESS_SCALE else PosterMotionTokens.TOUCH_PRESS_SCALE
        focused -> PosterMotionTokens.FOCUS_SCALE
        else -> 1f
    }

    fun lift(focused: Boolean): Float = if (enabled && focused) PosterMotionTokens.FOCUS_LIFT_DP else 0f

    fun depth(input: Float, focused: Boolean): Float =
        if (depthEnabled && focused && input.isFinite()) input.coerceIn(-1f, 1f) else 0f
}

/** Native Android key codes, kept pure for host tests. Only a discrete digital hint, never a touch surface. */
fun posterDirection(keyCode: Int): PosterDirection = when (keyCode) {
    21 -> PosterDirection(x = -1f)
    22 -> PosterDirection(x = 1f)
    19 -> PosterDirection(y = -1f)
    20 -> PosterDirection(y = 1f)
    else -> PosterDirection.None
}

/** Overscan the actual artwork dimensions, not a guessed fixed percentage. */
fun posterArtworkScale(extentPx: Float, maxTranslationPx: Float): Float =
    if (extentPx > 0f && extentPx.isFinite() && maxTranslationPx > 0f && maxTranslationPx.isFinite()) {
        (1.0 + 2.0 * maxTranslationPx / extentPx).toFloat().takeIf { it.isFinite() } ?: 1f
    } else 1f

/** A single uniform scale preserves the poster's aspect ratio while covering both axes. */
fun posterArtworkScale(widthPx: Float, heightPx: Float, maxTranslationPx: Float): Float =
    maxOf(posterArtworkScale(widthPx, maxTranslationPx), posterArtworkScale(heightPx, maxTranslationPx))

/** Expiry also guards newly mounted or restored cards against a stale direction. */
fun posterHintIsFresh(issuedAtMillis: Long, nowMillis: Long): Boolean =
    nowMillis >= issuedAtMillis && nowMillis - issuedAtMillis in 0L until PosterMotionTokens.DIRECTION_HINT_MILLIS

/** Pure event reducer. Cancelling an old timeout can never cancel a newer/reversed key. */
internal data class PosterMotionInputState(
    val direction: PosterDirection = PosterDirection.None,
    val issuedAtMillis: Long = 0L,
    val generation: Long = 0L,
) {
    fun keyDown(keyCode: Int, nowMillis: Long): PosterMotionInputState {
        val next = posterDirection(keyCode)
        return if (next == PosterDirection.None) clear()
            else PosterMotionInputState(next, nowMillis, generation + 1)
    }
    fun clear(): PosterMotionInputState = copy(direction = PosterDirection.None)
    fun expire(expectedGeneration: Long): PosterMotionInputState =
        if (generation == expectedGeneration) clear() else this
    fun directionAt(nowMillis: Long): PosterDirection =
        if (posterHintIsFresh(issuedAtMillis, nowMillis)) direction else PosterDirection.None
}
