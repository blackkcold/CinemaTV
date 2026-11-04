package dev.cinematv.ui.tv.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object TvColors {
    val Background = Color(0xFF090A0D)
    val Surface = Color(0xFF1B1D23)
    val Text = Color(0xFFF5F5F7)
    val Secondary = Color(0xFFA8A9B0)
    val Accent = Color(0xFFD5DDE9)
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
