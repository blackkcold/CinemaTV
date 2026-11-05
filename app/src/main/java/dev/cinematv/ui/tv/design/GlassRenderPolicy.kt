package dev.cinematv.ui.tv.design

/** Historical bitmap V4 policy retained only for regression/migration reference.
 * Production live rendering uses ProductionGlassPolicy, never this still-only gate. */
object GlassRenderPolicy {
    const val MAX_WIDTH_DP = 600f
    const val MAX_HEIGHT_DP = 80f
    const val MAX_AREA_PX = 240_000f
    fun lensAllowed(quality: GlassQuality, api: Int, reducedMotion: Boolean,
        focused: Boolean, hasStill: Boolean, hardware: Boolean, widthDp: Float,
        heightDp: Float, areaPx: Float, failed: Boolean): Boolean =
        quality == GlassQuality.ENHANCED && api >= 33 && !reducedMotion && focused &&
            hasStill && hardware && !failed && widthDp.isFinite() && heightDp.isFinite() &&
            areaPx.isFinite() && widthDp > 0 && heightDp > 0 && areaPx > 0 &&
            widthDp <= MAX_WIDTH_DP && heightDp <= MAX_HEIGHT_DP && areaPx <= MAX_AREA_PX
}

/** Recover to the already-painted material after a synchronous shader failure.
 * A scene session does not repeatedly compile a shader that failed. Native GPU driver
 * crashes cannot be caught here; hardware certification is still required.
 */
class GlassFailureLatch {
    var failed: Boolean = false
        private set
    fun <T> attempt(block: () -> T): T? {
        if (failed) return null
        return try { block() } catch (_: RuntimeException) { failed = true; null }
    }
}
