package dev.cinematv.ui.tv.design

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dev.cinematv.ui.LocalOpticalGlass

@Composable
fun CinemaTvTheme(reducedMotion: Boolean, quality: GlassQuality, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lowRam = remember(context) {
        context.getSystemService(ActivityManager::class.java)?.let {
            it.isLowRamDevice || it.memoryClass <= 128
        } ?: true
    }
    val effects = TvEffects(reducedMotion, resolveGlassQuality(quality, lowRam, Build.VERSION.SDK_INT, reducedMotion))
    MaterialTheme(colorScheme = darkColorScheme(
        background = TvColors.Background, surface = TvColors.Surface,
        onBackground = TvColors.Text, onSurface = TvColors.Text,
        primary = TvColors.Text, onPrimary = TvColors.Background
    )) {
        BoxWithConstraints {
            CompositionLocalProvider(
                LocalTvLayout provides TvLayout(maxWidth, maxHeight),
                LocalTvEffects provides effects,
                LocalOpticalGlass provides (effects.glass == GlassQuality.ENHANCED),
                content = content
            )
        }
    }
}
