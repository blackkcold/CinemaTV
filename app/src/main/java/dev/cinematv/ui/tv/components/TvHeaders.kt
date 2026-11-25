package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.design.TvSpace
import dev.cinematv.ui.tv.design.TvText
import dev.cinematv.ui.tv.design.TvType

/** The one page masthead. A page supplies the gutter; this owns hierarchy, not margins. */
@Composable
fun TvPageHeader(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpace.md)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TvSpace.xxs)) {
            TvText(title, style = TvType.h1, color = TvColors.Text)
            if (subtitle.isNotBlank()) TvText(subtitle, style = TvType.caption, color = TvColors.Secondary)
        }
        action?.invoke()
    }
}

/** A section title inside a page, optionally paired with one local action. */
@Composable
fun TvSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpace.sm)) {
        TvText(title, style = TvType.h2, color = TvColors.Text, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}
