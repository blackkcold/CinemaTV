package dev.cinematv.ui.tv.design

import androidx.compose.ui.graphics.Color

/**
 * The floating navigation cannot borrow the panel face. A 13% white face over a bright
 * hero leaves 14sp labels around 2.2–3.2:1, measured on the approved golden. This face
 * darkens the pill itself so the artwork keeps its light; it never changes page scrims.
 *
 * The constants are public inside the module so a unit test can prove the composite
 * instead of trusting a screenshot by eye.
 */
internal object NavigationGlassFace {
    const val WHITE = .045f
    const val BLUE = .02f
    const val DENSE_DARK = .10f
    const val VEIL = .34f

    val veilColor = Color(0xFF05070A)
    val blue = Color(0xFF6E9DBB)
    val dense = Color(0xFF06111D)

    /** The surface the navigation draws, over a page colour that already has its scrim. */
    fun surfaceOver(page: Color, white: Float = WHITE, veil: Float = VEIL): Color {
        var c = page
        c = blend(Color.White, white, c)
        c = blend(blue, BLUE, c)
        c = blend(dense, DENSE_DARK, c)
        return blend(veilColor, veil, c)
    }

    private fun blend(top: Color, alpha: Float, base: Color): Color = Color(
        red = top.red * alpha + base.red * (1 - alpha),
        green = top.green * alpha + base.green * (1 - alpha),
        blue = top.blue * alpha + base.blue * (1 - alpha),
        alpha = 1f,
    )
}

/** The panel face, kept here only so a test can prove the navigation is darker than it. */
internal fun panelFaceOver(page: Color): Color = Color(
    red = .13f + page.red * .87f,
    green = .13f + page.green * .87f,
    blue = .13f + page.blue * .87f,
    alpha = 1f,
)

internal fun relativeLuminance(color: Color): Float {
    fun linear(channel: Float): Float =
        if (channel <= .04045f) channel / 12.92f else ((channel + .055f) / 1.055f).let { it * it * it }
    return .2126f * linear(color.red) + .7152f * linear(color.green) + .0722f * linear(color.blue)
}

internal fun contrast(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + .05f) / (lo + .05f)
}
