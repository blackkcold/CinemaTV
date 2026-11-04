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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
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
    val frame = LocalGlassBackdrop.current
    val effects = LocalTvEffects.current
    var position by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { position = it.positionInRoot() }.clip(shape)) {
        if (sampleBackdrop && frame != null && !frame.bitmap.isRecycled && effects.glass != GlassQuality.BASIC) {
            val bitmap = remember(frame.bitmap) { frame.bitmap.asImageBitmap() }
            Canvas(Modifier.matchParentSize().blur(20.dp)) {
                val viewport = frame.viewport
                val scale = max(viewport.width / bitmap.width, viewport.height / bitmap.height)
                val drawing = Size(bitmap.width * scale, bitmap.height * scale)
                val local = position - frame.origin
                drawImage(bitmap,
                    dstOffset = IntOffset(((viewport.width - drawing.width) / 2 - local.x).roundToInt(),
                        ((viewport.height - drawing.height) / 2 - local.y).roundToInt()),
                    dstSize = IntSize(drawing.width.roundToInt(), drawing.height.roundToInt()), alpha = .35f)
            }
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color(0xC52D3037), Color(0xDD15171C))
        )).border(.75.dp, Color.White.copy(alpha = .16f), shape))
        content()
    }
}
