package dev.cinematv.ui.tv.design

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dev.cinematv.ui.typography.cinemaUiFontFamily
import dev.cinematv.ui.typography.withCinemaNoto
import dev.cinematv.ui.tv.components.ProductionGlassProvider

@Composable
fun CinemaTvTheme(
    reducedMotion: Boolean,
    quality: GlassQuality,
    /** Visual language preference; resolved inside the glass provider's capability snapshot. */
    visualTheme: VisualTheme = VisualTheme.AUTO,
    content: @Composable () -> Unit,
) {
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    // The theme is resolved inside the provider because that is where the capability snapshot
    // already lives; resolving it here would need a second snapshot and could disagree.
    ProductionGlassProvider(quality, reducedMotion, visualTheme) {
        MaterialTheme(
            typography = MaterialTheme.typography.withCinemaNoto(cinemaUiFontFamily()),
            colorScheme = darkColorScheme(
                background = TvColors.Background, surface = TvColors.Surface,
                onBackground = TvColors.Text, onSurface = TvColors.Text,
                primary = TvColors.Text, onPrimary = TvColors.Background,
            ),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Geometry and type stay outside the theme: one layout for every language.
                CompositionLocalProvider(
                    LocalTvLayout provides TvLayout(maxWidth, maxHeight),
                    content = content,
                )
            }
        }
    }
}
