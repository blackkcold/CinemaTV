package dev.cinematv.ui.tv.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.R
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.icons.CinemaSymbol
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun FloatingNavigation(model: CinemaViewModel, onFocusWithin: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val layout = LocalTvLayout.current
    val selectedTarget = LocalNavigationFocus.current
    val contentTarget = LocalContentFocus.current
    Row(modifier.fillMaxWidth().padding(horizontal = layout.gutter)
        .onFocusChanged { onFocusWithin(it.hasFocus) }.focusGroup(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.width(120.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, modifier = Modifier.size(26.dp))
            Text("CinemaTV", color = TvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        CinemaGlassSurface(radius = 28.dp, sampleBackdrop = true) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("精选" to Page.HOME, "电影" to Page.MOVIES, "剧集" to Page.SERIES,
                    "发现" to Page.EXPLORE, "直播" to Page.LIVE).forEach { (label, destination) ->
                    val selected = model.section == destination
                    GlassAction(label, Modifier.then(if (selected) Modifier.focusRequester(selectedTarget) else Modifier)
                        .focusProperties { down = contentTarget }.width(58.dp).height(36.dp), selected = selected) {
                        model.navigate(destination)
                    }
                }
            }
        }
        Row(Modifier.width(136.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NavIcon("搜索", CinemaSymbol.Search, model, Page.SEARCH)
            NavIcon("片库", CinemaSymbol.Library, model, Page.LIBRARY)
            NavIcon("设置", CinemaSymbol.Settings, model, Page.SETTINGS)
        }
    }
}

@Composable
private fun NavIcon(label: String, icon: CinemaSymbol, model: CinemaViewModel, destination: Page) {
    val selected = model.section == destination
    val navigation = LocalNavigationFocus.current
    val content = LocalContentFocus.current
    Box(contentAlignment = Alignment.Center) {
        GlassAction("", Modifier.size(39.dp).semantics { contentDescription = "打开$label" }
            .then(if (selected) Modifier.focusRequester(navigation) else Modifier)
            .focusProperties { down = content }, selected = selected, icon = icon) { model.navigate(destination) }
    }
}
