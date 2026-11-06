package dev.cinematv.ui.tv.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TvColors {
    val Background = Color(0xFF090A0D)
    val Surface = Color(0xFF1B1D23)
    val Text = Color(0xFFF5F5F7)
    val Secondary = Color(0xFFA8A9B0)
    val Accent = Color(0xFFD5DDE9)
    /** Roles the old palette only expressed inline; new UI must use these, never a raw hex. */
    val Rating = Color(0xFFF4F1D9)
    val Success = Color(0xFF8CD3A6)
    val Warning = Color(0xFFD1A978)
    val Danger = Color(0xFFE06C75)
}

/** Nine type levels replace the 21 ad-hoc sizes; new UI must not invent another one. */
data class TvTextStyle(
    val size: Int,
    val lineHeight: Int,
    val weight: FontWeight,
    val tracking: TextUnit = 0.sp
)

object TvType {
    val display = TvTextStyle(44, 52, FontWeight.Bold, (-.5).sp)
    val hero = TvTextStyle(42, 48, FontWeight.Bold, (-.5).sp)
    val h1 = TvTextStyle(32, 38, FontWeight.SemiBold, (-.3).sp)
    val h2 = TvTextStyle(22, 28, FontWeight.Bold)
    val h3 = TvTextStyle(20, 26, FontWeight.SemiBold)
    val body = TvTextStyle(15, 22, FontWeight.Normal)
    val label = TvTextStyle(14, 18, FontWeight.SemiBold)
    val caption = TvTextStyle(13, 18, FontWeight.Medium)
    val meta = TvTextStyle(11, 16, FontWeight.Normal)
    val eyebrow = TvTextStyle(12, 16, FontWeight.SemiBold, 2.sp)
}

/** Eight spacing steps and the two layout anchors. */
object TvSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 44.dp
}

/** Five radii: badge, card, control, panel, dialog. */
object TvRadius {
    val badge = 5.dp
    val card = 12.dp
    val control = 22.dp
    val panel = 22.dp
    val dialog = 26.dp
}

/** Four control heights replace the fourteen inline ones. */
object TvHeight {
    val chip = 36.dp
    val button = 44.dp
    val field = 52.dp
}

object TvMotion {
    const val microMs = 120
    const val controlMs = 180
    const val sceneMs = 380
    const val toastMs = 6_000L
}

/** Screen constraints are dp, never a hard-coded 1920px canvas. */
data class TvLayout(val width: Dp = 960.dp, val height: Dp = 540.dp) {
    val gutter: Dp get() = width * .055f
    val heroHeight: Dp get() = height * .59f
    val landscapeWidth: Dp get() = ((width - gutter * 2) / 3.8f).coerceIn(180.dp, 300.dp)
    val posterWidth: Dp get() = ((width - gutter * 2) / 6.1f).coerceIn(112.dp, 190.dp)
    val navigationTop: Dp get() = (height * .035f).coerceIn(16.dp, 28.dp)
    val contentTop: Dp get() = navigationTop + 72.dp
}

val LocalTvLayout = staticCompositionLocalOf { TvLayout() }
