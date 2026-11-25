package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.cinematv.ui.tv.design.TvHeight
import dev.cinematv.ui.tv.design.TvSpace

/**
 * One horizontal action group with the shared 48dp row height. Buttons keep their own
 * explicit size; this only stops pages from re-inventing spacing and vertical alignment.
 */
@Composable
fun TvActionRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(TvSpace.sm),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.heightIn(min = TvHeight.row),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}
