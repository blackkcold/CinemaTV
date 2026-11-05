package dev.cinematv.ui.tv.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.design.TvColors

/** The frame clips oversized artwork; labels, border and progress are separate live siblings. */
@Composable
fun LayeredPosterFrame(
    motion: FocusPressMotion,
    focused: Boolean,
    modifier: Modifier = Modifier,
    artwork: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier
        .graphicsLayer {
            rotationX = motion.rotationX
            rotationY = motion.rotationY
            cameraDistance = 900f * density
            // Focus changes this once; never animate shadow radius/elevation.
            shadowElevation = if (focused) 16.dp.toPx() else 0f
            this.shape = shape
            clip = false
        }
        .clip(shape)
        .background(TvColors.Surface)) {
        Box(Modifier.fillMaxSize().posterArtworkMotion(motion), content = artwork)
        // A live, independent face sheen. Reduced Motion retains its static focus
        // material; neither artwork bitmaps nor typography are raster-cached here.
        if (focused) Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width * (.5f + motion.rotationY / 20f), -size.height * .45f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .18f), Color.Transparent),
                center = center, radius = size.width), radius = size.width,
                center = Offset(size.width * .5f, -size.height * .45f))
        }
        content()
    }
}
