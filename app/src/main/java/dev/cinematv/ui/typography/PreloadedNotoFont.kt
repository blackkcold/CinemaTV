package dev.cinematv.ui.typography

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Declares a cached regional face at a specific weight in a FontListFontFamily.
 *
 * `FontFamily(Typeface)` is an already-resolved face: Compose 1.10.6 deliberately ignores a
 * requested weight/style on that path. A descriptor lets Compose's matcher choose between the
 * weights we register, and `FontSynthesis.None` then makes a missing weight visible instead of
 * silently faking it.
 *
 * [weight] must differ across the members of one family. If every member claims Normal, the
 * matcher returns the first one for every request and all weight variation disappears — a
 * failure that renders without any error.
 */
internal class PreloadedNotoFont(
    val typeface: Typeface,
    override val weight: FontWeight,
) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = Loader,
    variationSettings = FontVariation.Settings(),
) {
    override val style: FontStyle = FontStyle.Normal

    private object Loader : TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface =
            (font as PreloadedNotoFont).typeface

        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
            loadBlocking(context, font)
    }
}
