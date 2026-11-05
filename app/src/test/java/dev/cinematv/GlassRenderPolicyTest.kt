package dev.cinematv

import dev.cinematv.ui.tv.design.*
import org.junit.Assert.*
import org.junit.Test

class GlassRenderPolicyTest {
    private fun allowed(quality: GlassQuality = GlassQuality.ENHANCED, api: Int = 33,
        reduced: Boolean = false, focused: Boolean = true, still: Boolean = true,
        hardware: Boolean = true, width: Float = 160f, height: Float = 45f,
        area: Float = 28800f, failed: Boolean = false) =
        GlassRenderPolicy.lensAllowed(quality, api, reduced, focused, still, hardware, width, height, area, failed)

    @Test fun enhancedIsExplicitAndOnlyTheFocusedControlSamples() {
        assertTrue(allowed())
        for (quality in listOf(GlassQuality.BASIC, GlassQuality.AUTO, GlassQuality.BACKDROP)) assertFalse(allowed(quality))
        assertFalse(allowed(focused = false))
        assertFalse(allowed(api = 32))
        assertFalse(allowed(reduced = true))
        assertFalse(allowed(still = false))
        assertFalse(allowed(hardware = false))
        assertFalse(allowed(failed = true))
    }
    @Test fun panelsAndInvalidOrUnboundedSurfacesCannotAcquireALens() {
        assertFalse(allowed(width = 601f))
        assertFalse(allowed(height = 81f))
        assertFalse(allowed(area = 240001f))
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(allowed(width = bad)); assertFalse(allowed(height = bad)); assertFalse(allowed(area = bad))
        }
        assertTrue(allowed(width = 600f, height = 80f, area = 240000f))
    }
    @Test fun legacyStillPolicyRetainsItsOwnGatesButIsNotTheProductionResolver() {
        assertFalse(allowed(quality = resolveGlassQuality(GlassQuality.ENHANCED, true, 36, false)))
        assertEquals(GlassQuality.ENHANCED, resolveGlassQuality(GlassQuality.ENHANCED, false, 36, true))
        assertFalse(allowed(reduced = true))
    }
    @Test fun compileAndDrawFailuresLatchUntilANewSessionWhileFallbackCanRender() {
        val latch = GlassFailureLatch()
        assertEquals("success", latch.attempt { "success" })
        assertNull(latch.attempt<String> { throw IllegalArgumentException("unsupported shader") })
        assertTrue(latch.failed)
        var attemptedAgain = false
        assertNull(latch.attempt { attemptedAgain = true; "must not retry" })
        assertFalse(attemptedAgain)
        assertFalse(allowed(failed = latch.failed))
        assertFalse(GlassFailureLatch().failed)
    }
}
