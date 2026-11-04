package dev.cinematv.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.icons.CinemaIcon
import dev.cinematv.ui.icons.CinemaSymbol

/** Reuse each existing button's label and content contrast; no extra focus/click target. */
@Composable
internal fun MobileIconLabel(symbol: CinemaSymbol, label: String, selected: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CinemaIcon(symbol, Modifier.size(20.dp), selected = selected,
            onLightSurface = LocalContentColor.current.luminance() < .5f)
        Text(label.removePrefix("← "), modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(label) })
    }
}
