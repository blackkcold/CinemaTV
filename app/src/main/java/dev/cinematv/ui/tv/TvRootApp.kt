package dev.cinematv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.ui.*
import dev.cinematv.ui.tv.components.*
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*
import dev.cinematv.ui.tv.motion.PosterMotionProvider
import dev.cinematv.ui.tv.pages.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvRootApp(model: CinemaViewModel, settingsInitialSection: Int = 0, onSceneDecoded: (String?) -> Unit = {}) {
    CinemaTvTheme(model.reducedMotion, model.glassQuality) {
      PosterMotionProvider(resetKey = model.focusRoute) { posterInput ->
        val keyboard = LocalSoftwareKeyboardController.current
        val imeVisible = WindowInsets.isImeVisible
        // Keep ownership in the persistent root: a restored Search editor can
        // finish showing its IME after that route has already been disposed.
        // Do not clear focus here; destination D-pad restoration owns focus.
        LaunchedEffect(model.page, imeVisible) {
            if (shouldHideTvIme(model.page, imeVisible)) keyboard?.hide()
        }
        val savedPages = rememberSaveableStateHolder()
        val registry = remember { TvFocusRegistry() }
        val navigation = remember { FocusRequester() }
        val content = remember(model.focusRoute) { FocusRequester() }
        val navigationFocusOwner = remember(model) {
            TvNavigationFocusOwner { model.page !in listOf(Page.DETAIL, Page.PLAYER, Page.SETTINGS) }
        }
        val focusObserver = LocalTvFocusOwnerObserver.current
        SideEffect { focusObserver?.invoke(navigationFocusOwner) }
        var navigationHasFocus by remember { mutableStateOf(false) }
        var decodedArt by remember { mutableStateOf<String?>(null) }
        val glassScene = rememberProductionGlassScene(model.page != Page.PLAYER)
        val showNav = navigationFocusOwner.available
        LaunchedEffect(showNav) { if (!showNav) { navigationFocusOwner.update(false); navigationHasFocus = false } }
        CompositionLocalProvider(LocalTvFocusRegistry provides registry, LocalNavigationFocus provides navigation,
            LocalContentFocus provides content, LocalNavigationHasFocus provides (navigationHasFocus && showNav),
            LocalNavigationFocusOwner provides navigationFocusOwner,
            LocalTvContentFocusOwner provides navigationFocusOwner,
            LocalBackdropArt provides decodedArt,
            LocalProductionGlassScene provides glassScene) {
            val layout = LocalTvLayout.current
            if (model.page != Page.PLAYER) ArtworkPrefetch(model.featured + model.tvShows, model.focused)
            Box(Modifier.fillMaxSize().then(posterInput).background(TvColors.Background)) {
                if (model.page != Page.PLAYER) BackdropLayer(
                    if (model.page == Page.DETAIL) model.selected else model.focused ?: model.featured.firstOrNull(),
                    modifier = Modifier.recordProductionGlass(glassScene)
                ) { decodedArt = it; onSceneDecoded(it) }
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
                        Page.SETTINGS -> SettingsScreen(model, initialSection = settingsInitialSection)
                    }
                }
                }
                if (showNav) CompositionLocalProvider(LocalTvContentFocusOwner provides null) {
                    FloatingNavigation(model, { navigationFocusOwner.update(it); navigationHasFocus = it }, Modifier.align(Alignment.TopCenter).padding(top = layout.navigationTop))
                }
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
}
