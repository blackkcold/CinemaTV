package dev.cinematv.ui.tv.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.icons.CinemaSymbol
import dev.cinematv.ui.shared.CinemaBrandStartup
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*
import dev.cinematv.ui.typography.CinemaTvText as Text
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun FloatingNavigation(model: CinemaViewModel, onFocusWithin: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val layout = LocalTvLayout.current
    Row(modifier.fillMaxWidth().padding(horizontal = layout.gutter)
        .onFocusChanged { onFocusWithin(it.hasFocus) }.focusGroup(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        CinemaBrandStartup(Modifier.width(120.dp).height(29.dp))
        MainNavigationTabs(model)
        Row(Modifier.width(136.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NavIcon("搜索", CinemaSymbol.Search, model, Page.SEARCH)
            NavIcon("片库", CinemaSymbol.Library, model, Page.LIBRARY)
            NavIcon("设置", CinemaSymbol.Settings, model, Page.SETTINGS)
        }
    }
}

@Composable
private fun MainNavigationTabs(model: CinemaViewModel) {
    val entries = listOf("精选" to Page.HOME, "电影" to Page.MOVIES, "剧集" to Page.SERIES,
        "发现" to Page.EXPLORE, "直播" to Page.LIVE)
    val selected = entries.indexOfFirst { it.second == model.section }
    val navigation = LocalNavigationFocus.current
    val content = LocalContentFocus.current
    val effects = LocalTvEffects.current
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val target = (focusedIndex ?: selected.coerceAtLeast(0)).toFloat()
    val position = remember { Animatable(selected.coerceAtLeast(0).toFloat()) }
    var lensVelocity by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(target, effects.reducedMotion) {
        if (effects.reducedMotion) { position.snapTo(target); lensVelocity = 0f }
        else {
            position.animateTo(target, spring(.68f, 300f)) { lensVelocity = velocity }
            lensVelocity = 0f
        }
    }
    val source = currentProductionGlassScene()
    val containerScene = rememberProductionGlassScene(source != null && effects.glass == GlassQuality.ENHANCED)
    val combined = if (source != null && containerScene != null)
        rememberCombinedBackdrop(source.backdrop, containerScene.backdrop) else null
    val itemWidth = 58.dp
    val itemPx = with(LocalDensity.current) { itemWidth.toPx() }
    Box(Modifier.width(itemWidth * entries.size + 8.dp).height(44.dp).testTag("tv-main-navigation")) {
        // Source graph: scene -> glass container -> lens -> crisp foreground labels.
        Box(Modifier.matchParentSize().recordProductionGlass(containerScene)
            .cinemaGlassMaterial(radius = 22.dp, dense = true))
        if (selected >= 0 || focusedIndex != null) Box(Modifier.padding(4.dp).offset { IntOffset((position.value * itemPx).roundToInt(), 0) }
            .width(itemWidth).height(36.dp).cinemaNavigationLens(combined) {
                val squash = if (effects.reducedMotion) 0f else (abs(lensVelocity) * .016f).coerceAtMost(.12f)
                scaleX = 1f + squash; scaleY = 1f - squash * .6f
            })
        Row(Modifier.fillMaxSize().padding(4.dp)) {
            entries.forEachIndexed { index, (label, destination) ->
                val interactions = remember { MutableInteractionSource() }
                Box(Modifier.width(itemWidth).fillMaxHeight()
                    .then(if (index == selected) Modifier.focusRequester(navigation) else Modifier)
                    .focusProperties { canFocus = true; down = content }
                    .onFocusChanged { if (it.isFocused) focusedIndex = index else if (focusedIndex == index) focusedIndex = null }
                    .selectable(index == selected, interactions, indication = null, role = Role.Tab) { model.navigate(destination) },
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (index == selected || index == focusedIndex) Color.White else TvColors.Secondary,
                        fontSize = 14.sp, maxLines = 1)
                }
            }
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
            .focusProperties { canFocus = true; down = content }, selected = selected, icon = icon) { model.navigate(destination) }
    }
}
