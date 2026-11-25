package dev.cinematv.perf

import dev.cinematv.ui.tv.components.GlassDrawDiagnostics
import dev.cinematv.ui.tv.components.ProductionGlassObservation

/**
 * Passive evidence for the glass tier that was actually drawn.
 *
 * Production never installs it: the locals stay null unless [enabled] was set before the
 * activity composed. The acceptance lane enables it and reads the counters after the run,
 * so `actualTier` is a drawn fact rather than a value passed on the command line.
 */
internal object NativeGlassProbe {
    @Volatile
    var enabled: Boolean = false

    /** Reused by the production composition; counters are written on the draw path. */
    val diagnostics = GlassDrawDiagnostics()

    private val lock = Any()
    private var observation: ProductionGlassObservation? = null

    fun reset() {
        // Keep the latest policy observation: it is emitted on composition and does not
        // re-emit merely because measurement started. Only the draw counters restart.
        diagnostics.clear()
    }

    fun onObservation(value: ProductionGlassObservation) {
        if (!enabled) return
        synchronized(lock) { observation = value }
    }

    fun observation(): ProductionGlassObservation? = synchronized(lock) { observation }

    /** Highest tier that actually drew at least once; null when no glass consumer drew. */
    fun drawnTier(): String? {
        val lens = diagnostics.successfulLensDraws
        val blur = diagnostics.blurDraws
        val basic = diagnostics.basicDraws
        return when {
            lens > 0 -> "FULL"
            blur > 0 -> "BLUR"
            basic > 0 -> "BASIC"
            else -> null
        }
    }

    fun sourcePresentDraws(): Int = diagnostics.sourcePresentDrawsTotal

    fun drawCounts(): Map<String, Int> = mapOf(
        "full" to diagnostics.successfulLensDraws,
        "blur" to diagnostics.blurDraws,
        "basic" to diagnostics.basicDraws,
    )
}
