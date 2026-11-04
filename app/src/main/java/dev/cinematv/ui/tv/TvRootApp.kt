package dev.cinematv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.ui.*
import dev.cinematv.ui.tv.components.*
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*
import dev.cinematv.ui.tv.pages.*

@Composable
fun TvRootApp(model: CinemaViewModel) {
    CinemaTvTheme(model.reducedMotion, model.glassQuality) {
        val savedPages = rememberSaveableStateHolder()
        val registry = remember { TvFocusRegistry() }
        val navigation = remember { FocusRequester() }
        val content = remember(model.focusRoute) { FocusRequester() }
        var navigationHasFocus by remember { mutableStateOf(false) }
        var decodedArt by remember { mutableStateOf<String?>(null) }
        val showNav = model.page !in listOf(Page.DETAIL, Page.PLAYER, Page.SETTINGS)
        LaunchedEffect(showNav) { if (!showNav) navigationHasFocus = false }
        CompositionLocalProvider(LocalTvFocusRegistry provides registry, LocalNavigationFocus provides navigation,
            LocalContentFocus provides content, LocalNavigationHasFocus provides (navigationHasFocus && showNav),
            LocalBackdropArt provides decodedArt) {
            val layout = LocalTvLayout.current
            if (model.page != Page.PLAYER) ArtworkPrefetch(model.featured + model.tvShows, model.focused)
            Box(Modifier.fillMaxSize().background(TvColors.Background)) {
                if (model.page != Page.PLAYER) BackdropLayer(
                    if (model.page == Page.DETAIL) model.selected else model.focused ?: model.featured.firstOrNull()
                ) { decodedArt = it }
                savedPages.SaveableStateProvider(model.focusRoute) {
                  key(model.focusRoute) {
                    when (model.page) {
                        Page.HOME -> HomeScreen(model)
                        Page.EXPLORE -> ExploreScreen(model)
                        Page.MOVIES -> CatalogScreen(model, false)
                        Page.SERIES -> CatalogScreen(model, true)
                        Page.SEARCH -> SearchScreen(model)
                        Page.DETAIL -> DetailScreen(model)
                        Page.PLAYER -> TvPlayerPage(model)
                        Page.LIVE -> LiveScreen(model)
                        Page.LIBRARY -> LibraryScreen(model)
                        Page.SETTINGS -> SettingsScreen(model)
                    }
                }
                }
                if (showNav) FloatingNavigation(model, { navigationHasFocus = it }, Modifier.align(Alignment.TopCenter).padding(top = layout.navigationTop))
                if (model.message.isNotBlank() && model.page != Page.PLAYER) {
                    Text(model.message, color = TvColors.Text, fontSize = 14.sp, maxLines = 3,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = layout.gutter, vertical = 12.dp)
                            .background(TvColors.Surface.copy(alpha = .96f), RoundedCornerShape(15.dp)).padding(horizontal = 22.dp, vertical = 12.dp))
                    LaunchedEffect(model.message) { kotlinx.coroutines.delay(5500); model.dismissMessage() }
                }
            }
        }
    }
}
