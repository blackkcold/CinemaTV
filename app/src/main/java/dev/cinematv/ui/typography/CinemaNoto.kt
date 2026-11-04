package dev.cinematv.ui.typography

import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Immutable
data class CinemaNotoFonts(
    val latin: FontFamily = FontFamily.SansSerif,
    val regional: Map<NotoRegion, FontFamily> = emptyMap(),
    val failedAssets: Set<String> = emptySet(),
) {
    fun cjk(region: NotoRegion): FontFamily = regional[region] ?: FontFamily.SansSerif
}

/** Process cache: one immutable result, including failed loads. No retry on recomposition. */
internal object NotoFontCache {
    private val mutex = Mutex()
    @Volatile private var cached: CinemaNotoFonts? = null

    suspend fun load(assets: AssetManager): CinemaNotoFonts = withContext(Dispatchers.IO) {
        mutex.withLock {
            cached ?: loadUncached(assets).also { cached = it }
        }
    }

    internal fun loadUncached(assets: AssetManager): CinemaNotoFonts {
        val failures = mutableSetOf<String>()
        val latin = try {
            // Asset Font constructors read bytes now, on Dispatchers.IO, not on first draw.
            FontFamily(
                Font("fonts/NotoSans-Regular.ttf", assets, FontWeight.Normal),
                Font("fonts/NotoSans-Bold.ttf", assets, FontWeight.Bold),
            )
        } catch (_: Exception) {
            failures += "NotoSans"; FontFamily.SansSerif
        }
        val regional = NotoRegion.entries.associateWith { region ->
            try {
                val typeface = Typeface.Builder(assets, "fonts/NotoSansCJK-Regular.ttc")
                    .setTtcIndex(region.ttcIndex).setWeight(400).setItalic(false).build()
                if (typeface == null) {
                    failures += region.name; FontFamily.SansSerif
                } else FontFamily(PreloadedNotoFont(typeface))
            } catch (_: Exception) {
                failures += region.name; FontFamily.SansSerif
            }
        }
        return CinemaNotoFonts(latin, regional, failures)
    }
}

val LocalCinemaNoto = staticCompositionLocalOf { CinemaNotoFonts() }
val LocalCinemaLanguageTags = staticCompositionLocalOf { listOf("en") }

/** Install above BOTH Material themes. First frame uses system fonts; loading never blocks input. */
@Composable
fun CinemaNotoProvider(content: @Composable () -> Unit) {
    val assets = LocalContext.current.applicationContext.assets
    val configuration = LocalConfiguration.current
    val tags = remember(configuration) {
        (0 until configuration.locales.size()).map { configuration.locales[it].toLanguageTag() }
    }
    val fonts by produceState(CinemaNotoFonts(), assets) { value = NotoFontCache.load(assets) }
    CompositionLocalProvider(
        LocalCinemaNoto provides fonts,
        LocalCinemaLanguageTags provides tags,
        content = content,
    )
}

/** Font-only theme hook; callers retain their sizes, line heights and weights. */
@Composable
fun cinemaUiFontFamily(): FontFamily {
    val fonts = LocalCinemaNoto.current
    val region = NotoRegionPolicy.resolve(null, LocalCinemaLanguageTags.current)
    // Editable text and library-owned labels also need explicit Han coverage on English devices.
    return fonts.cjk(region)
}

/**
 * Explicit glyph selection for mixed Latin/CJK content. Pass the language of THIS displayed text,
 * not a movie's original language if the displayed title has been translated. Text is unchanged.
 * Non-CJK scripts fall through Noto Sans to Android's system fallback (not bundled coverage).
 */
@Composable
fun cinemaNotoText(text: String, contentLanguageTag: String? = null): AnnotatedString {
    val fonts = LocalCinemaNoto.current
    val tags = LocalCinemaLanguageTags.current
    return remember(text, contentLanguageTag, fonts, tags) {
        annotateNotoText(text, fonts, NotoRegionPolicy.resolve(contentLanguageTag, tags))
    }
}

internal fun annotateNotoText(text: String, fonts: CinemaNotoFonts, region: NotoRegion): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    builder.addStyle(SpanStyle(fontFamily = fonts.latin), 0, text.length)
    var index = 0
    while (index < text.length) {
        val start = index
        val cjk = NotoRegionPolicy.isCjk(text.codePointAt(index))
        index += Character.charCount(text.codePointAt(index))
        while (index < text.length && (NotoRegionPolicy.isContinuation(text.codePointAt(index)) ||
                NotoRegionPolicy.isCjk(text.codePointAt(index)) == cjk)) {
            index += Character.charCount(text.codePointAt(index))
        }
        if (cjk) builder.addStyle(
            SpanStyle(fontFamily = fonts.cjk(region), localeList = LocaleList(region.languageTag)),
            start, index,
        )
    }
    return builder.toAnnotatedString()
}
