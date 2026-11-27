package dev.cinematv.ui.tv.design

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class TvTokensTest {
    @Test fun controlHeightsHaveOneNameEachAndNoAdHocSizes() {
        assertEquals(36.dp, TvHeight.chip)
        assertEquals(44.dp, TvHeight.button)
        assertEquals(48.dp, TvHeight.row)
        assertEquals(52.dp, TvHeight.field)
    }

    @Test fun microMotionStaysBelowTheExistingControlStep() {
        assertEquals(110, TvMotion.microMs)
        assertEquals(180, TvMotion.controlMs)
    }

    @Test fun everyTypeLevelMapsToTheSameTextStyleValues() {
        val levels = listOf(
            TvType.display, TvType.hero, TvType.h1, TvType.h2, TvType.h3,
            TvType.body, TvType.label, TvType.caption, TvType.meta, TvType.eyebrow,
        )
        levels.forEach { level ->
            val style = level.toTextStyle()
            assertEquals(level.size.sp, style.fontSize)
            assertEquals(level.lineHeight.sp, style.lineHeight)
            assertEquals(level.weight, style.fontWeight)
            assertEquals(level.tracking, style.letterSpacing)
        }
        assertEquals(FontWeight.SemiBold, TvType.h2.toTextStyle().fontWeight)
        // 700 is deliberately absent from the type scale: the bundled CJK family declares
        // 400/500/600, so a Bold token would be matched down to 600 while claiming a weight the
        // face does not have. Each level states the weight it actually renders.
        assertEquals(FontWeight.SemiBold, TvType.display.toTextStyle().fontWeight)
        assertEquals(FontWeight.SemiBold, TvType.hero.toTextStyle().fontWeight)
    }
}
