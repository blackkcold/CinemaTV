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

    /**
     * Whether the up-front font load succeeded.
     *
     * Regional families are built lazily, so a ready state must not require every region to be
     * present — that condition can never become true. What readiness must still prove is that the
     * load ran and reported nothing broken: a corrupt asset, a bad TTC index or a missing file
     * all populate [failedAssets], so a failed load can never read as ready.
     *
     * Test hosts and instrumentation guards use this single definition instead of each
     * re-deriving "all five regions present". That assumption had spread to **seven** call sites
     * across five source sets (main load path aside: TvTestActivity, ProductionGlassTestActivity,
     * FocusBoundRelocationTest, FocusMediaCardMotionTest, GlassActionLayoutTest,
     * GlassBenchmarkActivity, and NotoFontsDeviceTest). Each one silently waited for a state that
     * can never arrive — the benchmark harness worst of all, because it produced no samples
     * rather than a failure. Keep this the only place that decides readiness.
     */
    val isReady: Boolean get() = regional.isNotEmpty() && failedAssets.isEmpty()
}

/**
 * Process cache: one immutable result, including failed loads. No retry on recomposition.
 *
 * Regional faces are built on demand: the device's primary region is prepared up front, and a
 * region reached later (a Traditional-Chinese title on a Simplified device) is added through
 * [familyFor] without rebuilding the cache. Building all five regions at three weights up front
 * would parse the 38 MB collection fifteen times before the first frame.
 */
internal object NotoFontCache {
    private val mutex = Mutex()
    @Volatile private var cached: CinemaNotoFonts? = null
    private val regionalExtra = java.util.concurrent.ConcurrentHashMap<NotoRegion, FontFamily>()

    suspend fun load(assets: AssetManager): CinemaNotoFonts = withContext(Dispatchers.IO) {
        mutex.withLock {
            cached ?: loadUncached(assets).also { cached = it }
        }
    }

    /**
     * The family for [region], built on first use off the main thread.
     *
     * Suspending is mandatory, not stylistic: building a family runs three
     * `Typeface.Builder` instances over a 38 MB collection, and doing that in composition put
     * the parse on the frame that first shows the region — the one frame that must not stall.
     * Callers await this from `produceState`, so a region reached mid-session costs one
     * off-main parse and one recomposition instead of a dropped frame.
     */
    suspend fun familyFor(assets: AssetManager, region: NotoRegion): FontFamily? {
        cached?.regional?.get(region)?.let { return it }
        regionalExtra[region]?.let { return it }
        return withContext(Dispatchers.IO) {
            regionalExtra[region] ?: runCatching {
                val failures = mutableSetOf<String>()
                val family = buildRegionalFamily(assets, region, failures)
                if (failures.isEmpty()) regionalExtra.putIfAbsent(region, family) ?: family
                else family.takeIf { it !== FontFamily.SansSerif }
            }.getOrNull()?.also { family ->
                if (family !== FontFamily.SansSerif) regionalExtra.putIfAbsent(region, family)
            }
        }
    }

    /**
     * Test-only convenience: [familyFor] with a non-null result, so a caller cannot accidentally
     * assert against `SansSerif`. It adds no second build path.
     */
    internal suspend fun familyForTest(assets: AssetManager, region: NotoRegion): FontFamily =
        familyFor(assets, region) ?: FontFamily.SansSerif

    internal fun loadUncached(assets: AssetManager): CinemaNotoFonts {
        val failures = mutableSetOf<String>()
        val latin = try {
            // Asset Font constructors read bytes now, on Dispatchers.IO, not on first draw.
            // Medium is a real file so Latin 500 does not fall back to 400 or get synthesised.
            FontFamily(
                Font("fonts/NotoSans-Regular.ttf", assets, FontWeight.Normal),
                Font("fonts/NotoSans-Medium.ttf", assets, FontWeight.Medium),
                Font("fonts/NotoSans-Bold.ttf", assets, FontWeight.Bold),
            )
        } catch (_: Exception) {
            failures += "NotoSans"; FontFamily.SansSerif
        }
        // Only the region this device reads is built up front. Building every region at three
        // weights would parse the 38 MB collection fifteen times on the first frame, which is
        // exactly the cold-start cost a low-RAM television cannot absorb.
        val preferred = NotoRegionPolicy.resolve(null, listOf(java.util.Locale.getDefault().toLanguageTag()))
        val regional = mapOf(preferred to buildRegionalFamily(assets, preferred, failures))
        return CinemaNotoFonts(latin, regional, failures)
    }

    /**
     * Build one region's family at the three weights the type scale uses.
     *
     * The variable collection's `wght` axis defaults to **100**, so every instantiation must set
     * the axis explicitly; a builder that omits it renders Thin, not Regular. `setWeight` alone
     * is not enough — it declares the descriptor's weight for matching, while
     * `setFontVariationSettings` selects the actual instance.
     */
    private fun buildRegionalFamily(
        assets: AssetManager,
        region: NotoRegion,
        failures: MutableSet<String>,
    ): FontFamily {
        val faces = CJK_WEIGHTS.mapNotNull { (weight, axis) ->
            try {
                val typeface = Typeface.Builder(assets, CJK_VARIABLE_FILE)
                    .setTtcIndex(region.ttcIndex)
                    // The collection's `wght` axis defaults to 100 (Thin). This line is what makes
                    // each declared weight a real instance; without it every request renders Thin.
                    .setFontVariationSettings("'wght' $axis")
                    .setWeight(axis)
                    .setItalic(false)
                    .build()
                if (typeface == null) {
                    failures += "${region.name}:$axis"; null
                } else {
                    PreloadedNotoFont(typeface, weight)
                }
            } catch (_: Exception) {
                failures += "${region.name}:$axis"; null
            }
        }
        return if (faces.isEmpty()) FontFamily.SansSerif else FontFamily(faces)
    }

    /** Requested weight -> `wght` axis value. 600 is a real interpolation, not synthetic bold. */
    private val CJK_WEIGHTS = listOf(
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
    )

    internal const val CJK_VARIABLE_FILE = "fonts/NotoSansCJK-VF.ttf.ttc"
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
    return rememberRegionalFamily(fonts, region)
}

/**
 * Resolve one region's family, building it off the main thread on first use.
 *
 * The up-front load only prepares the device's primary region; a Traditional-Chinese title on a
 * Simplified device would otherwise fall back to the system face.
 *
 * While the requested region is still being built, this returns the primary region's family
 * rather than the system font: rendering a Han glyph with the neighbouring region's shapes is
 * the pre-existing behaviour and is a small difference, whereas dropping to the platform face
 * changes weight and metrics visibly. One recomposition follows when the family arrives.
 */
@Composable
private fun rememberRegionalFamily(fonts: CinemaNotoFonts, region: NotoRegion): FontFamily {
    fonts.regional[region]?.let { return it }
    val assets = LocalContext.current.applicationContext.assets
    val prepared = produceState<FontFamily?>(initialValue = null, fonts, region, assets) {
        value = NotoFontCache.familyFor(assets, region)
    }
    return prepared.value ?: fonts.regional.values.firstOrNull() ?: fonts.cjk(region)
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
    val region = NotoRegionPolicy.resolve(contentLanguageTag, tags)
    val cjk = rememberRegionalFamily(fonts, region)
    return remember(text, region, fonts, cjk) {
        annotateNotoText(text, fonts, region, cjk)
    }
}

internal fun annotateNotoText(
    text: String,
    fonts: CinemaNotoFonts,
    region: NotoRegion,
    cjkFamily: FontFamily = fonts.cjk(region),
): AnnotatedString {
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
            SpanStyle(fontFamily = cjkFamily, localeList = LocaleList(region.languageTag)),
            start, index,
        )
    }
    return builder.toAnnotatedString()
}
