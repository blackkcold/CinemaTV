package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun ContentRail(title: String, items: List<MediaItem>, model: CinemaViewModel, portrait: Boolean = false, continueWatching: Boolean = false) {
    if (items.isEmpty()) return
    val layout = LocalTvLayout.current
    val region = "rail:$title"
    val state = rememberTvListState(model, region)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp)) {
        Text(title, color = TvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = layout.gutter, bottom = 4.dp))
        LazyRow(state = state, modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(items, key = { it.presentationKey }) { item ->
                val target = rememberFocusTarget(model, region, item.presentationKey)
                FocusMediaCard(item, onFocus = { model.onMediaFocus(item, region) }, onClick = { model.open(item) },
                    portrait = portrait, continueWatching = continueWatching,
                    modifier = Modifier.focusRequester(target).width(if (portrait) layout.posterWidth else layout.landscapeWidth))
            }
        }
    }
}
