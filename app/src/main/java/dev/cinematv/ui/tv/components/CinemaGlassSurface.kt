package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Scene-backed material only when explicitly requested and in the same Compose owner.
 * Foreground remains a sibling, so fallback painting never overwrites labels/content.
 */
@Composable
fun CinemaGlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    sampleBackdrop: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier) {
        if (sampleBackdrop) Box(Modifier.matchParentSize().cinemaGlassMaterial(radius = radius, dense = true))
        else androidx.compose.runtime.CompositionLocalProvider(LocalProductionGlassScene provides null) {
            Box(Modifier.matchParentSize().cinemaGlassMaterial(radius = radius, dense = true))
        }
        content()
    }
}
