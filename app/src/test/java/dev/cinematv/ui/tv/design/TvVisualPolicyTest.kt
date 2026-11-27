package dev.cinematv.ui.tv.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visual-theme contract.
 *
 * Two independent axes: a theme decides material/colour/shadow/depth/duration, the glass
 * backend decides how one face is rendered. Neither may raise the other. These tests are the
 * executable form of that rule; a comment in a document is not enforcement.
 */
class TvVisualPolicyTest {

    // ---- Axis separation -------------------------------------------------------------

    @Test fun everyThemeCapsTheBackendAndNeverRaisesIt() {
        for (visual in listOf(CinemaVisual, PosterVisual)) {
            for (level in GlassRenderMode.entries) {
                val capped = effectiveGlassLevel(level, visual.maxGlass)
                assertTrue(
                    "${visual.theme}: capping $level by ${visual.maxGlass} raised the backend",
                    capped.ordinal <= level.ordinal,
                )
            }
        }
    }

    @Test fun cinemaIsTheIdentityOnTheBackendDecision() {
        // This is the whole P0 no-op argument: Cinema's ceiling is FULL, so adding the
        // theme layer cannot change any surface's rendered backend.
        for (level in GlassRenderMode.entries) {
            assertEquals(level, effectiveGlassLevel(level, CinemaVisual.maxGlass))
        }
    }

    @Test fun posterCanNeverProduceBlurOrFullOptics() {
        assertEquals(GlassRenderMode.BASIC, PosterVisual.maxGlass)
        for (level in GlassRenderMode.entries) {
            assertEquals(
                "Poster must stay on the opaque path",
                GlassRenderMode.BASIC,
                effectiveGlassLevel(level, PosterVisual.maxGlass),
            )
        }
    }

    @Test fun cappingIsIdempotentAndOrderIndependent() {
        for (a in GlassRenderMode.entries) {
            for (b in GlassRenderMode.entries) {
                assertEquals(
                    effectiveGlassLevel(effectiveGlassLevel(a, b), b),
                    effectiveGlassLevel(a, b),
                )
                assertEquals(
                    "role cap and theme cap must commute",
                    effectiveGlassLevel(effectiveGlassLevel(a, b), CinemaVisual.maxGlass),
                    effectiveGlassLevel(effectiveGlassLevel(a, CinemaVisual.maxGlass), b),
                )
            }
        }
    }

    // ---- Resolution -----------------------------------------------------------------

    @Test fun resolutionNeverReturnsAuto() {
        for (requested in VisualTheme.entries) {
            assertNotEquals(VisualTheme.AUTO, resolveVisualTheme(requested))
        }
    }

    @Test fun explicitThemesResolveToThemselves() {
        assertEquals(VisualTheme.CINEMA, resolveVisualTheme(VisualTheme.CINEMA))
        assertEquals(VisualTheme.POSTER, resolveVisualTheme(VisualTheme.POSTER))
    }

    /**
     * Locks this version's default: AUTO is always Cinema, so no existing user's appearance
     * changes on upgrade.
     *
     * **This is a temporary product decision, not the design end state.** The reviewed rule
     * (AUTO prefers Poster where the platform cannot render the optical material) is implemented
     * as [resolvedAutoThemeByCapability] and keeps its own tests; switching AUTO onto it is a
     * one-line change after a 10-foot review. When that happens, this test is expected to be
     * rewritten — and its replacement must still pin a decision rather than assert nothing.
     */
    @Test fun autoResolvesToCinemaOnEveryLowCapabilityEnvironment() {
        val cases = listOf(
            "API 26" to GlassEnvironment(api = 26, lowRam = false, memoryClassMb = 512, hardwareAccelerated = true),
            "API 30" to GlassEnvironment(api = 30, lowRam = false, memoryClassMb = 512, hardwareAccelerated = true),
            "lowRam" to GlassEnvironment(api = 33, lowRam = true, memoryClassMb = 256, hardwareAccelerated = true),
            "lowMemory heap" to GlassEnvironment(api = 33, lowRam = false, memoryClassMb = 96, hardwareAccelerated = true),
            "software rendered" to GlassEnvironment(api = 33, lowRam = false, memoryClassMb = 512, hardwareAccelerated = false),
            "fully capable" to GlassEnvironment(api = 34, lowRam = false, memoryClassMb = 512, hardwareAccelerated = true),
        )
        for ((label, environment) in cases) {
            assertEquals(
                "AUTO must resolve to CINEMA this version ($label)",
                VisualTheme.CINEMA,
                resolveVisualTheme(VisualTheme.AUTO, environment),
            )
        }
    }

    @Test fun theReviewedCapabilityRuleStillWorksSoItCanBeEnabledLater() {
        // The rule behind the pending decision, proven at its boundaries. It is not wired into
        // resolveVisualTheme this version; this keeps it correct and visible.
        assertEquals(VisualTheme.POSTER, resolvedAutoThemeByCapability(
            GlassEnvironment(api = 30, lowRam = false, memoryClassMb = 512, hardwareAccelerated = true)))
        assertEquals(VisualTheme.POSTER, resolvedAutoThemeByCapability(
            GlassEnvironment(api = 34, lowRam = true, memoryClassMb = 512, hardwareAccelerated = true)))
        assertEquals(VisualTheme.POSTER, resolvedAutoThemeByCapability(
            GlassEnvironment(api = 34, lowRam = false, memoryClassMb = 96, hardwareAccelerated = true)))
        assertEquals(VisualTheme.POSTER, resolvedAutoThemeByCapability(
            GlassEnvironment(api = 34, lowRam = false, memoryClassMb = 512, hardwareAccelerated = false)))
        assertEquals(VisualTheme.CINEMA, resolvedAutoThemeByCapability(
            GlassEnvironment(api = 31, lowRam = false, memoryClassMb = 512, hardwareAccelerated = true)))
    }

    @Test fun settingsOffersOnlyDrawableThemes() {
        // AUTO is a resolution state, not a user choice.
        assertEquals(listOf(VisualTheme.CINEMA, VisualTheme.POSTER), selectableVisualThemes)
        assertTrue(!selectableVisualThemes.contains(VisualTheme.AUTO))
    }

    @Test fun resolutionIsPureAndRepeatable() {
        for (requested in VisualTheme.entries) {
            assertEquals(resolveVisualTheme(requested), resolveVisualTheme(requested))
        }
    }

    @Test fun everyResolvedThemeHasAVisual() {
        for (requested in VisualTheme.entries) {
            val theme = resolveVisualTheme(requested)
            val visual = visualFor(theme)
            // visualFor may only fall back for AUTO, which resolution already excludes.
            assertEquals(theme, visual.theme)
        }
    }

    // ---- The layout/type independence guard -----------------------------------------

    /**
     * A theme decides material, not geometry and not type. If someone later adds a
     * `cardWidth`, `fontSize`, `gutter` or `heroHeight` field here, switching themes would
     * start triggering measure/layout — the exact regression the shared [TvLayout] exists
     * to prevent. Names are checked because the field set has no other structural guard.
     */
    @Test fun visualCarriesNoGeometryOrType() {
        val forbidden = listOf(
            "width", "height", "gutter", "size", "padding", "spacing", "space",
            "font", "type", "text", "sp", "lineHeight", "letterSpacing", "radius",
            "aspect", "column", "row", "count",
        )
        val members = TvVisual::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("TvVisual exposed no fields; the guard would be vacuous", members.isNotEmpty())
        for (name in members) {
            for (token in forbidden) {
                assertTrue(
                    "TvVisual.$name looks like geometry/type; it belongs to the shared TvLayout/TvType",
                    !name.contains(token),
                )
            }
        }
    }

    /**
     * A theme change must be one value swap, so the value type must be structurally
     * immutable: no `var` fields, and no mutable collection members that could be edited in
     * place behind a stable value. `@Immutable` itself has BINARY retention and is therefore
     * invisible to reflection, so the structure is the honest thing to assert.
     */
    @Test fun visualIsStructurallyImmutable() {
        val fields = TvVisual::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue("TvVisual exposed no instance fields; the guard would be vacuous", fields.isNotEmpty())
        val mutable = fields.filter {
            !java.lang.reflect.Modifier.isFinal(it.modifiers) ||
                java.util.Collection::class.java.isAssignableFrom(it.type) ||
                java.util.Map::class.java.isAssignableFrom(it.type)
        }
        assertTrue("TvVisual must hold only final, non-mutable members, found: $mutable", mutable.isEmpty())
    }

    // ---- Field-level specification --------------------------------------------------

    @Test fun posterIsFlatterAndQuickerThanCinema() {
        assertTrue("Poster must not animate depth", !PosterVisual.cardDepth)
        assertTrue("Poster must not raise a shadow", !PosterVisual.cardShadow)
        assertTrue(
            "Poster scene transition must be shorter",
            PosterVisual.sceneMillis < CinemaVisual.sceneMillis,
        )
        assertTrue(
            "Poster focus must not grow more than Cinema",
            PosterVisual.focusScale <= CinemaVisual.focusScale,
        )
        assertTrue(
            "Poster lift must not exceed Cinema's",
            PosterVisual.focusLiftDp >= CinemaVisual.focusLiftDp,
        )
    }

    @Test fun posterFacesAreOpaqueAndCinemaFacesStayUntouched() {
        assertTrue("Poster panel must be opaque", PosterVisual.panelFill.alpha > .99f)
        assertTrue("Poster navigation must be opaque", PosterVisual.navigationFill.alpha > .99f)
        // Cinema draws its faces inside the glass material; adding fill here would double-draw.
        assertEquals(0f, CinemaVisual.panelFill.alpha, 0f)
        assertEquals(0f, CinemaVisual.navigationFill.alpha, 0f)
    }

    @Test fun focusScalesStayInsideTheMeasuredBand() {
        // Above ~1.08 neighbouring cards overlap; below ~1.02 focus stops reading at 3 m.
        for (visual in listOf(CinemaVisual, PosterVisual)) {
            assertTrue(
                "${visual.theme} card focus scale ${visual.focusScale} outside 1.02..1.08",
                visual.focusScale in 1.02f..1.08f,
            )
            assertTrue(
                "${visual.theme} action focus scale ${visual.actionFocusScale} outside 1.02..1.08",
                visual.actionFocusScale in 1.02f..1.08f,
            )
        }
    }
}
