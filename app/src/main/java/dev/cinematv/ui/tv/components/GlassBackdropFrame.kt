package dev.cinematv.ui.tv.components

import android.graphics.Bitmap
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.cinematv.ui.tv.design.GlassFailureLatch

/** Borrowed from Coil's existing scene decode. Never recycled or copied by controls. */
data class GlassBackdropFrame(val bitmap: Bitmap, val origin: Offset, val viewport: Size)
val LocalGlassBackdrop = compositionLocalOf<GlassBackdropFrame?> { null }
val LocalGlassFailureLatch = compositionLocalOf { GlassFailureLatch() }

/** Optional non-state test observer. Recording a completed draw never invalidates UI. */
internal class GlassDrawDiagnostics {
    var successfulLensDraws: Int = 0
        internal set
}
internal val LocalGlassDrawDiagnostics = compositionLocalOf<GlassDrawDiagnostics?> { null }
