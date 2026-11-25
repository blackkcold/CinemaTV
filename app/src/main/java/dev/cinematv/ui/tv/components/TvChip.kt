package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.design.LocalTvLayout
import dev.cinematv.ui.tv.design.TvHeight

/** A 36dp filter chip. Selection belongs to GlassAction's existing semantics. */
@Composable
fun TvChip(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    GlassAction(label, modifier.height(TvHeight.chip), selected = selected, enabled = enabled, onClick = onClick)
}

/** One scrollable chip row: 36dp chips and a consistent 12dp rhythm. */
@Composable
fun TvChipRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = LocalTvLayout.current.gutter),
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth().focusRestorer(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
