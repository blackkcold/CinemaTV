package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.design.GlassRenderMode

/**
 * Scene-backed material only when the surface allows it and the Compose owner matches
 * the recording owner. Foreground stays a sibling, so fallback painting never
 * overwrites labels/content. The default stays Basic: a caller must opt into a role.
 */
@Composable
fun CinemaGlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    level: GlassRenderMode = GlassRenderMode.BASIC,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier) {
        if (level == GlassRenderMode.BASIC) {
            // No effect node at all, not even an inert backdrop lookup.
            CompositionLocalProvider(LocalProductionGlassScene provides null) {
                Box(Modifier.matchParentSize().cinemaGlassMaterial(radius = radius, dense = true,
                    level = GlassRenderMode.BASIC))
            }
        } else {
            Box(Modifier.matchParentSize().cinemaGlassMaterial(radius = radius, dense = true, level = level))
        }
        content()
    }
}
