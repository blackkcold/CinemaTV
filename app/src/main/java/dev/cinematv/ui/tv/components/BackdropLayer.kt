package dev.cinematv.ui.tv.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.landscapeArt
import dev.cinematv.ui.tv.design.*

val LocalBackdropArt = compositionLocalOf<String?> { null }

/**
 * Scrim stops, shared by the production gradient and the contrast test.
 *
 * A stop is `position to blackAlpha`: the gradient paints [TvColors.Background] at that alpha,
 * so the artwork shows through by `1 - alpha`. Keeping these as data — instead of literals at
 * the call site plus a copy in the test — is what makes the readability test able to fail when
 * production lightens the scrim.
 */
internal object BackdropScrim {
    /** Poster: lighter than Cinema, but the text column must still clear 4.5:1. */
    val posterHorizontal = listOf(0f to .88f, .34f to .52f, .62f to .22f, 1f to 0f)
    val posterHorizontalDense = listOf(0f to .92f, .34f to .62f, .62f to .22f, 1f to 0f)
    val posterVertical = listOf(0f to .48f, .36f to .12f, .70f to .52f, 1f to 1f)

    /** Cinema: the approved profile. */
    val cinemaHorizontal = listOf(0f to .96f, .28f to .60f, .50f to .30f, 1f to 0f)
    val cinemaHorizontalDense = listOf(0f to .97f, .40f to .56f, 1f to 0f)
    val cinemaVertical = listOf(0f to .28f, .30f to 0f, .63f to .30f, 1f to 1f)
    val cinemaVerticalDense = listOf(0f to .62f, .32f to 0f, .66f to .48f, 1f to 1f)

    fun horizontal(stops: List<Pair<Float, Float>>): Brush = Brush.horizontalGradient(
        *stops.map { (position, alpha) -> position to scrimColor(alpha) }.toTypedArray(),
    )

    fun vertical(stops: List<Pair<Float, Float>>): Brush = Brush.verticalGradient(
        *stops.map { (position, alpha) -> position to scrimColor(alpha) }.toTypedArray(),
    )

    /** Alpha 0 must be fully transparent, not black-with-zero-alpha, to keep the blend exact. */
    fun scrimColor(alpha: Float): Color =
        if (alpha <= 0f) Color.Transparent else TvColors.Background.copy(alpha = alpha)

    /** Sample the profile at [fraction]; used by the contrast test on the same data. */
    fun alphaAt(stops: List<Pair<Float, Float>>, fraction: Float): Float {
        if (stops.isEmpty()) return 0f
        val f = fraction.coerceIn(0f, 1f)
        var previous = stops.first()
        for (stop in stops) {
            if (f <= stop.first) {
                val span = stop.first - previous.first
                if (span <= 0f) return stop.second
                return previous.second + (stop.second - previous.second) * ((f - previous.first) / span)
            }
            previous = stop
        }
        return stops.last().second
    }
}

/** Original atmospheric fallback. Never claims to be a still from a film. */
@Composable
fun AmbientArtwork(modifier: Modifier = Modifier, seed: Int = 0) {
    val tint = remember(seed) {
        listOf(Color(0xFF253B50), Color(0xFF45323C), Color(0xFF253F42), Color(0xFF353249))[seed.and(Int.MAX_VALUE) % 4]
    }
    Canvas(modifier.background(TvColors.Background)) {
        drawRect(Brush.radialGradient(listOf(tint, Color.Transparent), center = Offset(size.width * .76f, size.height * .36f), radius = size.width * .65f))
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x803D5361), Color.Transparent), start = Offset(0f, size.height * .64f), end = Offset(size.width, size.height * .16f)))
        for (i in 0..6) {
            val y = size.height * (.30f + i * .047f)
            drawLine(Color.White.copy(alpha = .012f + i * .002f), Offset(size.width * .30f, y), Offset(size.width, y - size.height * .12f), strokeWidth = (i + 1) * 2f)
        }
    }
}

/** Decode before swapping; a failed or portrait-sized image resolves to fallback. */
@Composable
fun BackdropLayer(
    item: MediaItem?,
    modifier: Modifier = Modifier,
    denseText: Boolean = false,
    onDecodedArt: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val effects = LocalTvEffects.current
    val visual = LocalTvVisual.current
    val poster = visual.theme == VisualTheme.POSTER
    val decodeWidth = if (effects.glass == GlassQuality.BASIC) 960 else BACKDROP_DECODE_WIDTH
    val decodeHeight = if (effects.glass == GlassQuality.BASIC) 540 else BACKDROP_DECODE_HEIGHT
    val requested = landscapeArt(item)
    val latest by rememberUpdatedState(requested)
    val decodedObserver = LocalProductionSceneDecoded.current
    val notify by rememberUpdatedState<(String?) -> Unit> { art -> onDecodedArt(art); decodedObserver?.invoke(art) }
    var decoded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(requested) {
        if (requested == null) { decoded = null; notify(null) }
    }
    // One scrim policy, two profiles. HOME protects only the text column so the artwork keeps
    // its light on the right; routes that put body text over the artwork get a stronger
    // reading scrim. Pages never invent their own overlay.
    //
    // The stops live in [BackdropScrim] as real constants rather than literals here, so the
    // contrast test reads the production numbers instead of a copy of them. A test that mirrors
    // a constant can only fail when someone edits both — which is the one case it must catch.
    val horizontalScrim = remember(denseText, poster) {
        BackdropScrim.horizontal(
            when {
                poster && denseText -> BackdropScrim.posterHorizontalDense
                poster -> BackdropScrim.posterHorizontal
                denseText -> BackdropScrim.cinemaHorizontalDense
                else -> BackdropScrim.cinemaHorizontal
            },
        )
    }
    val verticalScrim = remember(denseText, poster) {
        BackdropScrim.vertical(
            when {
                poster -> BackdropScrim.posterVertical
                denseText -> BackdropScrim.cinemaVerticalDense
                else -> BackdropScrim.cinemaVertical
            },
        )
    }
    Box(modifier.fillMaxSize()) {
        AmbientArtwork(Modifier.matchParentSize(), item?.title?.hashCode() ?: 0)
        if (requested != null) {
            val request = remember(requested, decodeWidth) {
                ImageRequest.Builder(context).data(requested)
                    .size(decodeWidth, decodeHeight).build()
            }
            AsyncImage(model = request, contentDescription = null,
                modifier = Modifier.size(1.dp).alpha(0f),
                onSuccess = { state ->
                    if (latest == requested) {
                        val image = state.result.image
                        val ratio = image.width.toFloat() / image.height.coerceAtLeast(1)
                        decoded = requested.takeIf { image.width >= 640 && ratio in 1.3f..2.5f }
                        notify(decoded)
                    }
                },
                onError = { if (latest == requested) { decoded = null; notify(null) } })
        }
        Crossfade(targetState = decoded, animationSpec = tween(if (effects.reducedMotion) 0 else visual.sceneMillis), label = "decoded-backdrop") { art ->
            if (art != null) AsyncImage(
                model = ImageRequest.Builder(context).data(art).size(decodeWidth, decodeHeight).build(),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.matchParentSize().background(horizontalScrim))
        if (poster) {
            // 3% artwork-derived tint: one full-screen fill, no blur, no extra pass. It keeps
            // the flat background from reading as a solid colour block.
            Box(Modifier.matchParentSize().background(TvColors.Accent.copy(alpha = .03f)))
        }
        Box(Modifier.matchParentSize().background(verticalScrim))
    }
}
