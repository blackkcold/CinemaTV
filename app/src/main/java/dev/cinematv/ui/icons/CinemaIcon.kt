package dev.cinematv.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.cinematv.R

/** Approved V3 only. Keep the 48×48 viewport, including Play's optical translation. */
enum class CinemaSymbol(
    @DrawableRes val defaultResource: Int,
    @DrawableRes val selectedResource: Int,
    @DrawableRes val monochromeResource: Int,
    val autoMirrorRtl: Boolean = false
) {
    Home(R.drawable.cinema_v3_home_default, R.drawable.cinema_v3_home_selected, R.drawable.cinema_v3_home_mono),
    Search(R.drawable.cinema_v3_search_default, R.drawable.cinema_v3_search_selected, R.drawable.cinema_v3_search_mono),
    Play(R.drawable.cinema_v3_play_default, R.drawable.cinema_v3_play_selected, R.drawable.cinema_v3_play_mono),
    Pause(R.drawable.cinema_v3_pause_default, R.drawable.cinema_v3_pause_selected, R.drawable.cinema_v3_pause_mono),
    Library(R.drawable.cinema_v3_library_default, R.drawable.cinema_v3_library_selected, R.drawable.cinema_v3_library_mono),
    Bookmark(R.drawable.cinema_v3_bookmark_default, R.drawable.cinema_v3_bookmark_selected, R.drawable.cinema_v3_bookmark_mono),
    Back(R.drawable.cinema_v3_back_default, R.drawable.cinema_v3_back_selected, R.drawable.cinema_v3_back_mono, true),
    More(R.drawable.cinema_v3_more_default, R.drawable.cinema_v3_more_selected, R.drawable.cinema_v3_more_mono),
    Info(R.drawable.cinema_v3_info_default, R.drawable.cinema_v3_info_selected, R.drawable.cinema_v3_info_mono),
    Settings(R.drawable.cinema_v3_settings_default, R.drawable.cinema_v3_settings_selected, R.drawable.cinema_v3_settings_mono);
}

/** No focus input: selection is navigation/content state, never D-pad focus. */
enum class CinemaIconTier { Compact, Monochrome }

fun cinemaIconTier(onLightSurface: Boolean, lowCost: Boolean): CinemaIconTier =
    if (onLightSurface || lowCost) CinemaIconTier.Monochrome else CinemaIconTier.Compact

@DrawableRes
fun cinemaIconResource(symbol: CinemaSymbol, selected: Boolean, tier: CinemaIconTier): Int = when (tier) {
    CinemaIconTier.Monochrome -> symbol.monochromeResource
    CinemaIconTier.Compact -> if (selected) symbol.selectedResource else symbol.defaultResource
}

/**
 * Native cached vector painter, not runtime SVG or a filtered graphics layer. Compact is
 * inexpensive on baseline TVs too. Monochrome is available for constrained renderers and
 * light button surfaces; it uses the approved aperture geometry rather than flattening layers.
 * The enclosing control owns its label, hit target, selected semantics and focus behavior.
 */
@Composable
fun CinemaIcon(
    symbol: CinemaSymbol,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLightSurface: Boolean = false,
    lowCost: Boolean = false,
    contentDescription: String? = null
) {
    val tier = cinemaIconTier(onLightSurface, lowCost)
    val tint = when {
        selected -> Color(0xFF3D87DE)
        onLightSurface -> Color(0xFF090A0D)
        else -> Color(0xFFF5F5F7)
    }
    Image(
        painterResource(cinemaIconResource(symbol, selected, tier)),
        contentDescription = contentDescription,
        modifier = modifier.size(24.dp),
        colorFilter = if (tier == CinemaIconTier.Monochrome) ColorFilter.tint(tint) else null
    )
}
