package dev.cinematv.ui.tv.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-23 golden put the unselected navigation labels at roughly 2.2–3.2:1 over a
 * bright hero. These are the numbers that make the darker navigation face mandatory, and
 * they fail the build if someone lightens it again.
 */
class NavigationGlassFaceTest {
    private val accent = TvColors.Accent
    private val white = Color.White
    // The measured p90 of the 01-home navigation band, not a guessed worst case.
    private val brightestMeasuredPill = Color(0xFF808080)

    @Test fun everyNavigationLabelClearsAaTextContrast() {
        val surface = NavigationGlassFace.surfaceOver(brightestMeasuredPill)
        assertTrue(
            "Unselected label contrast was ${contrast(accent, surface)}",
            contrast(accent, surface) >= 4.5f,
        )
        assertTrue(
            "Selected label contrast was ${contrast(white, surface)}",
            contrast(white, surface) >= 4.5f,
        )
    }

    @Test fun navigationGlyphsClearNonTextContrastOnPureWhiteArt() {
        // The right-hand icon buttons sit where the page scrim is transparent.
        val surface = NavigationGlassFace.surfaceOver(Color.White)
        assertTrue(
            "Icon contrast on white art was ${contrast(white, surface)}",
            contrast(white, surface) >= 3f,
        )
    }

    @Test fun theNavigationFaceStaysDarkerThanThePanelFace() {
        val navigation = relativeLuminance(NavigationGlassFace.surfaceOver(brightestMeasuredPill))
        val panel = relativeLuminance(panelFaceOver(brightestMeasuredPill))
        assertTrue("Navigation face $navigation is not meaningfully darker than panel $panel",
            panel - navigation >= .10f)
    }
}
