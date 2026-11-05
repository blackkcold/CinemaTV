package dev.cinematv

import dev.cinematv.ui.tv.components.GlassDrawDiagnostics
import dev.cinematv.ui.tv.components.ScopedGlassDraws
import dev.cinematv.ui.tv.design.GlassRenderMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassDrawDiagnosticsTest {
    @Test fun outgoingHomeDrawCannotContaminateNewDetailScope() {
        val draws = GlassDrawDiagnostics()
        draws.record(GlassRenderMode.FULL, "HOME", sourcePresent = true)
        draws.clearScope("DETAIL")
        // An outgoing Home frame is legal even after the user has begun navigation.
        draws.record(GlassRenderMode.BLUR, "HOME", sourcePresent = true)
        draws.record(GlassRenderMode.BASIC, "DETAIL", sourcePresent = false)
        draws.record(GlassRenderMode.FULL, "HOME", sourcePresent = true)
        assertEquals(ScopedGlassDraws(basicDraws = 1), draws.scopedSnapshot("DETAIL"))
        assertEquals(ScopedGlassDraws(fullDraws = 2, blurDraws = 1, sourcePresentDraws = 3), draws.scopedSnapshot("HOME"))
        assertEquals(2, draws.successfulLensDraws)
        assertEquals(1, draws.blurDraws)
        assertEquals(1, draws.basicDraws)
    }

    @Test fun sourcePresenceRemainsVisibleEvenWhenMaterialFallsBack() {
        val draws = GlassDrawDiagnostics()
        draws.record(GlassRenderMode.BASIC, "DETAIL", sourcePresent = true)
        assertEquals(ScopedGlassDraws(basicDraws = 1, sourcePresentDraws = 1), draws.scopedSnapshot("DETAIL"))
    }

    @Test fun clearingScopeKeepsOtherPagesAndGlobalCompatibilityCounters() {
        val draws = GlassDrawDiagnostics()
        draws.record(GlassRenderMode.FULL, "HOME", sourcePresent = true)
        draws.record(GlassRenderMode.BASIC, "DETAIL", sourcePresent = false)
        draws.clearScope("DETAIL")
        assertEquals(ScopedGlassDraws(), draws.scopedSnapshot("DETAIL"))
        assertEquals(1, draws.scopedSnapshot("HOME").fullDraws)
        assertEquals(1, draws.basicDraws)
        assertEquals(1, draws.successfulLensDraws)
    }

    @Test fun defaultNullScopeOnlyUsesExistingGlobalCounters() {
        val draws = GlassDrawDiagnostics()
        draws.record(GlassRenderMode.FULL, null, sourcePresent = true)
        draws.record(GlassRenderMode.BLUR, null, sourcePresent = true)
        draws.record(GlassRenderMode.BASIC, null, sourcePresent = false)
        assertEquals(1, draws.successfulLensDraws)
        assertEquals(1, draws.blurDraws)
        assertEquals(1, draws.basicDraws)
        assertEquals(ScopedGlassDraws(), draws.scopedSnapshot("DETAIL"))
    }
}
