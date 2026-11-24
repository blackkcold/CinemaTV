package dev.cinematv.ui.tv.design

import androidx.compose.ui.graphics.Color
import dev.cinematv.ui.Ink
import dev.cinematv.ui.Ivory
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.absoluteValue

/**
 * A focus ring is a non-text indicator: WCAG asks for 3:1 against its surroundings. One
 * colour cannot survive both a white poster and black artwork, so the pair must guarantee
 * that at least one rim stays visible on every possible backdrop.
 */
class FocusRingContrastTest {
    @Test fun theInkAndIvoryPairStaysVisibleOnEveryLuminance() {
        var worst = Float.MAX_VALUE
        var worstAt = 0f
        for (step in 0..100) {
            val channel = step / 100f
            val background = Color(channel, channel, channel)
            val best = maxOf(contrast(Ink, background), contrast(Ivory, background))
            if (best < worst) { worst = best; worstAt = channel }
        }
        assertTrue("Worst focus-ring contrast was $worst at grey $worstAt", worst >= 3f)
    }

    @Test fun eachRimOwnsOneExtreme() {
        // The dark rim is for light backdrops; the bright core is for dark ones.
        assertTrue("Ink on white was ${contrast(Ink, Color.White)}", contrast(Ink, Color.White) >= 3f)
        assertTrue("Ivory on black was ${contrast(Ivory, Color.Black)}", contrast(Ivory, Color.Black) >= 3f)
        assertTrue("The pair must differ for the two rims to be distinguishable",
            (relativeLuminance(Ivory) - relativeLuminance(Ink)).absoluteValue >= .5f)
    }
}
