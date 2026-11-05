package dev.cinematv.ui.tv.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.cinematv.ui.tv.design.GlassRenderMode

internal data class ScopedGlassDraws(
    val fullDraws: Int = 0,
    val blurDraws: Int = 0,
    val basicDraws: Int = 0,
    val sourcePresentDraws: Int = 0,
)

/** Test-only observer: ordinary counters/maps never invalidate or drive UI. */
internal class GlassDrawDiagnostics {
    var successfulLensDraws: Int = 0
        internal set
    var blurDraws: Int = 0
        internal set
    var basicDraws: Int = 0
        internal set
    private val scoped = mutableMapOf<String, ScopedGlassDraws>()

    fun record(mode: GlassRenderMode, scope: String?, sourcePresent: Boolean) {
        when (mode) {
            GlassRenderMode.FULL -> successfulLensDraws++
            GlassRenderMode.BLUR -> blurDraws++
            GlassRenderMode.BASIC -> basicDraws++
        }
        // Null in ordinary app/benchmark hosts. Scope is captured at composition,
        // never looked up from a changing model during this draw callback.
        if (scope != null) {
            val previous = scoped[scope] ?: ScopedGlassDraws()
            scoped[scope] = previous.copy(
                fullDraws = previous.fullDraws + if (mode == GlassRenderMode.FULL) 1 else 0,
                blurDraws = previous.blurDraws + if (mode == GlassRenderMode.BLUR) 1 else 0,
                basicDraws = previous.basicDraws + if (mode == GlassRenderMode.BASIC) 1 else 0,
                sourcePresentDraws = previous.sourcePresentDraws + if (sourcePresent) 1 else 0,
            )
        }
    }

    fun scopedSnapshot(scope: String): ScopedGlassDraws = scoped[scope] ?: ScopedGlassDraws()
    fun clearScope(scope: String) { scoped.remove(scope) }
}
internal val LocalGlassDrawDiagnostics = compositionLocalOf<GlassDrawDiagnostics?> { null }
internal val LocalGlassDrawScope = staticCompositionLocalOf<String?> { null }
