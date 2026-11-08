package dev.cinematv.ui.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.design.TvSpace
import dev.cinematv.ui.tv.design.TvText
import dev.cinematv.ui.tv.design.TvType

/** Loading is a distinct state from "no data"; pages must not fake one with the other. */
@Composable
fun TvLoadingState(label: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TvSpace.sm)) {
        Box(Modifier.size(18.dp).background(TvColors.Accent.copy(alpha = .35f), CircleShape))
        TvText(label, style = TvType.caption, color = TvColors.Secondary)
    }
}

/** A request failure is recoverable in place; the retry action is always explicit. */
@Composable
fun TvErrorState(
    title: String,
    detail: String = "",
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TvSpace.sm)) {
        Box(Modifier.size(56.dp).background(TvColors.Danger.copy(alpha = .16f), CircleShape))
        TvText(title, style = TvType.h3, color = TvColors.Text)
        if (detail.isNotBlank()) TvText(detail, style = TvType.caption, color = TvColors.Secondary)
        if (onRetry != null) GlassAction(retryLabel, prominent = true, onClick = onRetry)
    }
}

/** One quiet status sentence; no box, no action, no invented progress. */
@Composable
fun TvStatusLine(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    TvText(text, style = TvType.meta, color = if (error) TvColors.Danger else TvColors.Secondary,
        modifier = modifier.fillMaxWidth())
}
