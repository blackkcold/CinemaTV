package dev.cinematv.ui.tv

import dev.cinematv.Page
import dev.cinematv.ui.tv.design.GlassRenderMode
import dev.cinematv.ui.tv.design.TvGlassRole
import dev.cinematv.ui.tv.design.glassLevelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every surface's backend comes from its semantic role.
 *
 * The previous `GlassAction` hardcoded `GlassRenderMode.FULL`, so a dialog's action asked for
 * optics its own contract forbids and only fell back because the dialog window has no scene to
 * sample. That is intent wrong-but-masked: any future change to the scene lookup would have
 * silently promoted dialogs to options that sample a page they cannot see.
 */
class GlassRoleDerivedLevelTest {

    @Test fun actionDerivesItsLevelFromItsRoleOnEveryPage() {
        // The role-only overload and the page-qualified form must agree, because the page
        // argument is unused; if someone makes role level page-dependent, this fails.
        for (page in Page.entries) {
            for (role in TvGlassRole.entries) {
                assertEquals(
                    "role $role on $page diverges between the two forms",
                    glassLevelFor(page, role),
                    glassLevelFor(role),
                )
            }
        }
    }

    @Test fun dialogAndPlayerOverlayRolesCanNeverRequestOptics() {
        assertEquals(GlassRenderMode.BASIC, glassLevelFor(TvGlassRole.DIALOG))
        assertEquals(GlassRenderMode.BASIC, glassLevelFor(TvGlassRole.PLAYER_OVERLAY))
        for (page in Page.entries) {
            assertEquals(GlassRenderMode.BASIC, glassLevelFor(page, TvGlassRole.DIALOG))
            assertEquals(GlassRenderMode.BASIC, glassLevelFor(page, TvGlassRole.PLAYER_OVERLAY))
        }
    }

    @Test fun pageSurfacesKeepTheirFullCeiling() {
        // No pre-emptive degradation: these ceilings are the G6-reviewed contract and the
        // role refactor must not have lowered them.
        assertEquals(GlassRenderMode.FULL, glassLevelFor(TvGlassRole.NAVIGATION))
        assertEquals(GlassRenderMode.FULL, glassLevelFor(TvGlassRole.ACTION))
        assertEquals(GlassRenderMode.FULL, glassLevelFor(TvGlassRole.PANEL))
    }

    @Test fun roleCeilingNeverRaisesThePlatformDecision() {
        // The observable behaviour the refactor must preserve: for the roles that were already
        // FULL, the effective level equals the platform decision exactly as before.
        val platformLevels = GlassRenderMode.entries
        for (level in platformLevels) {
            val before = lcg(level, GlassRenderMode.FULL)   // the old hardcoded cap
            for (role in listOf(TvGlassRole.NAVIGATION, TvGlassRole.ACTION, TvGlassRole.PANEL)) {
                val after = lcg(level, glassLevelFor(role))
                assertEquals("role $role changed the drawn backend for platform $level", before, after)
            }
        }
    }

    @Test fun dialogAndPlayerOverlayActionsDrawTheOpaquePath() {
        // DIALOG/PLAYER_OVERLAY now cap to BASIC. Combined with the theme cap this can only
        // hold or lower, so a dialog action can never reach BLUR or FULL.
        for (level in GlassRenderMode.entries) {
            for (role in listOf(TvGlassRole.DIALOG, TvGlassRole.PLAYER_OVERLAY)) {
                val drawn = lcg(lcg(level, glassLevelFor(role)), GlassRenderMode.BASIC)
                assertEquals(GlassRenderMode.BASIC, drawn)
                assertTrue(drawn.ordinal <= level.ordinal)
            }
        }
    }

    private fun lcg(a: GlassRenderMode, b: GlassRenderMode) =
        dev.cinematv.ui.tv.design.effectiveGlassLevel(a, b)
}
