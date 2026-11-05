package dev.cinematv.ui.tv.components

import androidx.compose.runtime.compositionLocalOf

/** Test-only observer: counters do not invalidate or drive UI. */
internal class GlassDrawDiagnostics {
    var successfulLensDraws: Int = 0
        internal set
    var blurDraws: Int = 0
        internal set
    var basicDraws: Int = 0
        internal set
}
internal val LocalGlassDrawDiagnostics = compositionLocalOf<GlassDrawDiagnostics?> { null }
