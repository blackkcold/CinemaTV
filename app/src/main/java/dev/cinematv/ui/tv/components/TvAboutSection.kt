package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.design.TvSpace
import dev.cinematv.ui.tv.design.TvText
import dev.cinematv.ui.tv.design.TvType

/**
 * The one "about this work" block: prose first, then only facts the app actually has.
 * It never invents a rating, cast list or season summary from an absent provider field.
 */
@Composable
fun TvAboutSection(
    description: String,
    modifier: Modifier = Modifier,
    title: String = "关于这部作品",
    facts: List<Pair<String, String>> = emptyList(),
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TvSpace.xs)) {
        TvSectionHeader(title)
        if (description.isNotBlank()) {
            TvText(description, style = TvType.body, color = TvColors.Secondary, maxLines = 5)
        }
        facts.filter { it.second.isNotBlank() }.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.xs)) {
                TvText(label, style = TvType.meta, color = TvColors.Secondary, modifier = Modifier.width(88.dp))
                TvText(value, style = TvType.caption, color = TvColors.Text)
            }
        }
    }
}
