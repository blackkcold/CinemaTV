package dev.cinematv.ui.tv.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.cinematv.ui.tv.design.TvColors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text over artwork must stay readable, and that must be measured — but measured against the
 * **production** scrim, not a copy of it.
 *
 * The stops come from [BackdropScrim], the same object `BackdropLayer` builds its gradients
 * from. An earlier version of this test carried its own duplicated numbers; it could only fail
 * if someone edited the production value *and* the copy, which is the one case it must catch.
 * Reading the shared constants removes that blind spot.
 *
 * This reproduces the defect that was actually observed: the first Poster capture measured
 * 1.11:1 for the subtitle over a warm still, because the lighter look had dropped the text
 * column protection. [theUnprotectedScrimIsUnreadable] keeps that arithmetic alive so the
 * assertions above cannot quietly stop measuring anything.
 */
class PosterScrimContrastTest {

    private fun luminance(c: Color): Float {
        fun linear(channel: Float) =
            if (channel <= .04045f) channel / 12.92f else ((channel + .055f) / 1.055f).let { it * it * it }
        return .2126f * linear(c.red) + .7152f * linear(c.green) + .0722f * linear(c.blue)
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + .05f) / (minOf(la, lb) + .05f)
    }

    /**
     * Worst-case artwork under the text column: a bright warm still, the honest adversary.
     * A dark backdrop would pass any scrim and prove nothing.
     */
    private val worstCaseArtwork = Color(0xFFC69866)

    /** The tint layer BackdropLayer draws between artwork and scrim in Poster. */
    private val tintAlpha = .03f

    private fun composedBackground(horizontal: List<Pair<Float, Float>>, xFraction: Float, yFraction: Float): Color {
        var c = lerp(worstCaseArtwork, TvColors.Accent, tintAlpha)
        c = lerp(c, TvColors.Background, BackdropScrim.alphaAt(horizontal, xFraction))
        c = lerp(c, TvColors.Background, BackdropScrim.alphaAt(BackdropScrim.posterVertical, yFraction))
        return c
    }

    @Test fun posterSubtitleOverWarmArtworkStaysReadable() {
        for (profile in listOf(BackdropScrim.posterHorizontal, BackdropScrim.posterHorizontalDense)) {
            // Body text sits in the left text column, a little below the page title.
            for (x in listOf(.04f, .10f, .18f, .26f)) {
                for (y in listOf(.55f, .58f, .62f)) {
                    val ratio = contrast(TvColors.Secondary, composedBackground(profile, x, y))
                    assertTrue(
                        "Poster subtitle contrast at x=$x y=$y is $ratio:1, below the 4.5:1 body-text gate",
                        ratio >= 4.5f,
                    )
                }
            }
        }
    }

    @Test fun posterPageTitleOverWarmArtworkStaysReadable() {
        for (profile in listOf(BackdropScrim.posterHorizontal, BackdropScrim.posterHorizontalDense)) {
            for (x in listOf(.04f, .10f, .18f)) {
                for (y in listOf(.36f, .42f, .48f)) {
                    val ratio = contrast(TvColors.Text, composedBackground(profile, x, y))
                    assertTrue(
                        "Poster page-title contrast at x=$x y=$y is $ratio:1, below the 4.5:1 gate",
                        ratio >= 4.5f,
                    )
                }
            }
        }
    }

    @Test
    fun theUnprotectedScrimIsUnreadable() {
        // Guards the guard. Two facts keep the assertions above honest:
        //
        // 1. With a transparent first stop — what a "lighter look" edit produces — the same
        //    arithmetic yields 2.37:1, still below the 4.5:1 gate. So the test can fail.
        // 2. With no scrim at all the subtitle over the raw still is 1.11:1, which is the value
        //    actually measured on the first Poster capture. That is where the defect came from.
        //
        // If either case starts passing the gate, the assertions above have stopped measuring.
        val unprotected = listOf(0f to 0f, .34f to .52f, .62f to .22f, 1f to 0f)
        val unprotectedRatio = contrast(TvColors.Secondary, composedBackground(unprotected, .04f, .58f))
        assertTrue(
            "a transparent text column must fall below the 4.5:1 gate, got $unprotectedRatio:1",
            unprotectedRatio < 4.5f,
        )

        val bareArtworkRatio = contrast(TvColors.Secondary, worstCaseArtwork)
        assertTrue(
            "raw still contrast should reproduce the observed ~1.11:1, got $bareArtworkRatio:1",
            bareArtworkRatio in 1.0f..1.3f,
        )
    }

    @Test
    fun everyProfileStartsFromAnOpaqueTextColumn() {
        // A cheap structural guard: both poster profiles must protect the left edge.
        for (profile in listOf(BackdropScrim.posterHorizontal, BackdropScrim.posterHorizontalDense)) {
            val first = profile.first().second
            assertTrue("poster text column must start protected, found alpha=$first", first >= .5f)
            val last = profile.last().second
            assertTrue("poster right edge must stay open so the artwork keeps its light", last <= .05f)
        }
    }
}
