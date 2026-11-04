package dev.cinematv.ui.tv.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import dev.cinematv.ui.GlassSheen
import dev.cinematv.ui.tv.design.*
import kotlin.math.max

/** Bounded still-image sampling. No window capture or video Surface sampling. */
@Composable
fun CinemaGlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    sampleBackdrop: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val art = LocalBackdropArt.current
    val effects = LocalTvEffects.current
    val layout = LocalTvLayout.current
    val density = LocalDensity.current
    var position by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { position = it.positionInRoot() }.clip(shape)) {
        if (sampleBackdrop && art != null && effects.glass != GlassQuality.BASIC) {
            val painter = rememberAsyncImagePainter(art)
            val viewport = with(density) { Size(layout.width.toPx(), layout.height.toPx()) }
            Canvas(Modifier.matchParentSize().blur(20.dp)) {
                val intrinsic = painter.intrinsicSize
                if (intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.width > 0f && intrinsic.height > 0f) {
                    val scale = max(viewport.width / intrinsic.width, viewport.height / intrinsic.height)
                    val drawing = Size(intrinsic.width * scale, intrinsic.height * scale)
                    withTransform({ translate((viewport.width - drawing.width) / 2 - position.x, (viewport.height - drawing.height) / 2 - position.y) }) {
                        with(painter) { draw(drawing, alpha = .55f) }
                    }
                }
            }
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color(0xC52D3037), Color(0xDD15171C))
        )).border(.75.dp, Color.White.copy(alpha = .16f), shape))
        GlassSheen(active = true, modifier = Modifier.matchParentSize())
        content()
    }
}
