package dev.cinematv.ui.typography

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Declares the cached regional face as a regular font in a FontListFontFamily.
 * FontFamily(Typeface) is an already-resolved face: Compose 1.10.6 deliberately
 * ignores requested weight/style on that path. A descriptor lets Compose apply
 * its normal synthetic bold/italic rules without another font file or disk read.
 */
internal class PreloadedNotoFont(val typeface: Typeface) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = Loader,
    variationSettings = FontVariation.Settings(),
) {
    override val weight: FontWeight = FontWeight.Normal
    override val style: FontStyle = FontStyle.Normal

    private object Loader : TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface =
            (font as PreloadedNotoFont).typeface

        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
            loadBlocking(context, font)
    }
}
