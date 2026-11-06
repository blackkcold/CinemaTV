package dev.cinematv.ui.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.design.TvRadius
import dev.cinematv.ui.tv.design.TvSpace
import dev.cinematv.ui.tv.design.TvType
import dev.cinematv.ui.typography.CinemaTvText as Text

/**
 * The one empty state for every route: a quiet tile, one sentence, one optional action.
 * Before this, each page wrote its own line and none of them said why the page was empty.
 */
@Composable
fun TvEmptyState(
    title: String,
    detail: String = "",
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TvSpace.sm)
    ) {
        Box(Modifier.size(56.dp).background(TvColors.Surface, RoundedCornerShape(TvRadius.card))) {
            Box(
                Modifier.align(Alignment.Center).size(18.dp)
                    .background(TvColors.Secondary.copy(alpha = .35f), RoundedCornerShape(TvRadius.badge))
            )
        }
        Text(title, color = TvColors.Text, fontSize = TvType.h3.size.sp, fontWeight = TvType.h3.weight)
        if (detail.isNotBlank()) {
            Text(detail, color = TvColors.Secondary, fontSize = TvType.caption.size.sp,
                lineHeight = TvType.caption.lineHeight.sp)
        }
        action?.invoke()
    }
}
