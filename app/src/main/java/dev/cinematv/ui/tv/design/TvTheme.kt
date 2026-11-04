package dev.cinematv.ui.tv.design

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.tv.material3.MaterialTheme
import dev.cinematv.ui.typography.cinemaUiFontFamily
import dev.cinematv.ui.typography.withCinemaNoto
import androidx.tv.material3.darkColorScheme
import dev.cinematv.ui.LocalOpticalGlass

@Composable
fun CinemaTvTheme(reducedMotion: Boolean, quality: GlassQuality, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    val lowRam = remember(context) {
        context.getSystemService(ActivityManager::class.java)?.let {
            it.isLowRamDevice || it.memoryClass <= 128
        } ?: true
    }
    val effects = TvEffects(reducedMotion, resolveGlassQuality(quality, lowRam, Build.VERSION.SDK_INT, reducedMotion))
    MaterialTheme(typography = MaterialTheme.typography.withCinemaNoto(cinemaUiFontFamily()), colorScheme = darkColorScheme(
        background = TvColors.Background, surface = TvColors.Surface,
        onBackground = TvColors.Text, onSurface = TvColors.Text,
        primary = TvColors.Text, onPrimary = TvColors.Background
    )) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalTvLayout provides TvLayout(maxWidth, maxHeight),
                LocalTvEffects provides effects,
                LocalOpticalGlass provides (effects.glass == GlassQuality.ENHANCED),
                content = content
            )
        }
    }
}
