package dev.cinematv.ui.tv.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The card must draw its procedural gradient only where it can be seen.
 *
 * `AmbientArtwork` is a radial gradient plus seven lines. Under a full-bleed landscape or
 * portrait image it is covered completely, so drawing it is pure invisible work on every
 * focus-driven repaint of every card in every rail. The remaining branch — no landscape, but
 * a poster shown as a 43% right-hand strip over a translucent scrim — leaves the left side
 * visible, so that branch must keep drawing it.
 *
 * This is a source guard because the visible/invisible decision lives in the card's own
 * branch structure, not in a pure function; asserting on the structure is the honest check.
 * It fails if the guard is removed or inverted, and it also fails if the branch set changes
 * without someone re-deciding what is covered.
 */
class CardArtworkCoverageTest {

    @Test fun gradientIsDrawnOnlyWhereTheArtworkDoesNotCoverIt() {
        val source = cardSource()
        assumeTrue("FocusMediaCard.kt not reachable from the test working dir", source != null)
        val text = source!!.readText()

        // The gate exists and is the inverted form (draw only when NOT covered).
        assertTrue(
            "AmbientArtwork must be gated by a coverage condition",
            text.contains("coveredByFullBleedArt"),
        )
        assertTrue(
            "The gate must not draw the gradient when art covers it",
            text.contains("if (!coveredByFullBleedArt) AmbientArtwork"),
        )
        // The condition must be exactly the two full-bleed branches.
        assertTrue(
            "coverage must be 'portrait && poster' or 'landscape != null'",
            text.contains("(portrait && item.poster.isNotBlank()) || (!portrait && landscape != null)"),
        )
        // The uncovered branch must survive: the 43% poster strip relies on the gradient.
        assertTrue(
            "the poster-strip branch must remain",
            text.contains("fillMaxWidth(.43f)"),
        )
        // And the raw pre-refactor form must be gone.
        assertFalse(
            "ungated AmbientArtwork call is back",
            Regex("""\n\s*AmbientArtwork\(Modifier\.matchParentSize\(\)""").containsMatchIn(text),
        )
    }

    private fun cardSource(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/dev/cinematv/ui/tv/components/FocusMediaCard.kt")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }
}
