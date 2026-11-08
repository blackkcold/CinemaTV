package dev.cinematv

import dev.cinematv.ui.tv.design.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The surface level is an upper bound. It may lower the platform/user decision and must
 * never raise it, force Full from AUTO, or give a dialog/player a backdrop source.
 */
class GlassSurfacePolicyTest {
    private val capable = GlassEnvironment(api = 33, lowRam = false, memoryClassMb = 256,
        hardwareAccelerated = true, windowReady = true, thermal = GlassThermalState.NONE)

    @Test fun navigationActionsAndPagePanelsMayUseFullOptics() {
        for (page in Page.entries) {
            assertEquals(GlassRenderMode.FULL, glassLevelFor(page, TvGlassRole.NAVIGATION))
            assertEquals(GlassRenderMode.FULL, glassLevelFor(page, TvGlassRole.ACTION))
            assertEquals(GlassRenderMode.FULL, glassLevelFor(page, TvGlassRole.PANEL))
        }
    }

    @Test fun dialogsAndPlayerOverlaysStayBasicOnEveryPage() {
        for (page in Page.entries) {
            assertEquals(GlassRenderMode.BASIC, glassLevelFor(page, TvGlassRole.DIALOG))
            assertEquals(GlassRenderMode.BASIC, glassLevelFor(page, TvGlassRole.PLAYER_OVERLAY))
        }
    }

    @Test fun surfaceCapOnlyEverLowersThePlatformDecision() {
        assertEquals(GlassRenderMode.FULL, effectiveGlassLevel(GlassRenderMode.FULL, GlassRenderMode.FULL))
        assertEquals(GlassRenderMode.BLUR, effectiveGlassLevel(GlassRenderMode.FULL, GlassRenderMode.BLUR))
        assertEquals(GlassRenderMode.BASIC, effectiveGlassLevel(GlassRenderMode.FULL, GlassRenderMode.BASIC))
        assertEquals(GlassRenderMode.BLUR, effectiveGlassLevel(GlassRenderMode.BLUR, GlassRenderMode.FULL))
        assertEquals(GlassRenderMode.BASIC, effectiveGlassLevel(GlassRenderMode.BASIC, GlassRenderMode.FULL))
    }

    @Test fun fullSurfaceCapCannotUpgradeAutoOrBasicPreference() {
        val auto = resolveProductionGlass(GlassQuality.AUTO, capable).mode
        assertEquals(GlassRenderMode.BLUR, effectiveGlassLevel(auto, GlassRenderMode.FULL))

        val basic = resolveProductionGlass(GlassQuality.BASIC, capable).mode
        assertEquals(GlassRenderMode.BASIC, effectiveGlassLevel(basic, GlassRenderMode.FULL))

        val unsupported = resolveProductionGlass(GlassQuality.ENHANCED, capable.copy(api = 26)).mode
        assertEquals(GlassRenderMode.BASIC, effectiveGlassLevel(unsupported, GlassRenderMode.FULL))
    }
}
