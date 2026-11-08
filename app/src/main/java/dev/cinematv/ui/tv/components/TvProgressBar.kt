package dev.cinematv.ui.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.design.TvRadius

/**
 * A 4dp read-only progress line. It exposes a range only, never a touch target or an
 * adjustable action; consumers that really need adjustment must build one explicitly.
 */
@Composable
fun TvProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val value = progress.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(4.dp)
        .background(TvColors.Text.copy(alpha = .12f), RoundedCornerShape(TvRadius.badge))
        .progressSemantics(value)) {
        Box(Modifier.fillMaxWidth(value).height(4.dp)
            .background(TvColors.Accent, RoundedCornerShape(TvRadius.badge)))
    }
}

