package dev.cinematv.ui.tv.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The visual language of the TV surfaces.
 *
 * A theme decides **material, colour, shadow, depth and motion duration only**. It must not
 * decide geometry or type: [TvLayout] and [TvType] are shared by every theme, so switching
 * themes can never trigger a measure/layout pass or shift the goldens' structure. The
 * reflection guard in `TvVisualPolicyTest` enforces this for [TvVisual]'s members.
 */
enum class VisualTheme(val title: String) {
    /** Resolve from stable platform capability. Never reads transient signals. */
    AUTO("自动"),
    /** Full glass, 3D poster depth, deep scene transitions. The existing language. */
    CINEMA("影院"),
    /** Opaque faces, flat depth, poster-driven. Declared here; drawn from P2 onward. */
    POSTER("画报"),
}

/**
 * Resolved, never [VisualTheme.AUTO].
 *
 * Only the lower-tier-capable fields live here. Geometry, type scale, spacing and radii are
 * deliberately absent: they belong to the one shared layout, not to a theme.
 */
@Immutable
data class TvVisual(
    val theme: VisualTheme,
    /** The highest render backend this theme may ever request. A cap, never a mandate. */
    val maxGlass: GlassRenderMode,
    /** Opaque face fill. CINEMA's glass draws its own face, so this stays transparent. */
    val panelFill: Color,
    /** 1dp boundary drawn in place of a shadow. Transparent when the theme uses shadows. */
    val panelBorder: Color,
    /** Navigation bar fill. CINEMA uses its glass face instead. */
    val navigationFill: Color,
    /** Focus scale for content cards. */
    val focusScale: Float,
    /** Focus scale for action buttons; a different control size, not a duplicate token. */
    val actionFocusScale: Float,
    /** Focus lift in dp. */
    val focusLiftDp: Float,
    /** Scene crossfade duration. */
    val sceneMillis: Int,
    /** Whether poster frames take a 3D rotation/parallax pose. */
    val cardDepth: Boolean,
    /** Whether poster frames raise a shadow on focus. */
    val cardShadow: Boolean,
)

/**
 * The existing language. Every value equals what production already draws, so adopting
 * this provider is a no-op by construction — including [TvVisual.maxGlass] = FULL, which
 * makes `effectiveGlassLevel(x, FULL) == x`.
 */
val CinemaVisual = TvVisual(
    theme = VisualTheme.CINEMA,
    maxGlass = GlassRenderMode.FULL,
    panelFill = Color.Transparent,
    panelBorder = Color.Transparent,
    navigationFill = Color.Transparent,
    focusScale = 1.06f,
    actionFocusScale = 1.045f,
    focusLiftDp = -3f,
    sceneMillis = 380,
    cardDepth = true,
    cardShadow = true,
)

/**
 * The poster language. Field values are the approved specification; the drawing that
 * consumes them arrives in P2. It never requests a backdrop source, so it can never
 * produce BLUR or FULL.
 *
 * [TvVisual.panelFill] is the approved opaque face. The 3% backdrop tint is a separate
 * backdrop-layer token and lands with its drawing in P2.
 */
val PosterVisual = TvVisual(
    theme = VisualTheme.POSTER,
    maxGlass = GlassRenderMode.BASIC,
    panelFill = Color(0xFF141417),
    panelBorder = Color(0x14FFFFFF),
    navigationFill = Color(0xFF141417),
    focusScale = 1.04f,
    actionFocusScale = 1.03f,
    focusLiftDp = -2f,
    sceneMillis = 180,
    cardDepth = false,
    cardShadow = false,
)

val LocalTvVisual = staticCompositionLocalOf { CinemaVisual }

/**
 * Resolve the requested theme to a drawable one. Never returns [VisualTheme.AUTO].
 *
 * **AUTO resolves to CINEMA in this version.** The capability branch below is deliberately kept
 * wired but not reached: it is the reviewed replacement, one line away, and switching it on is
 * a product decision that needs 10-foot evidence, not an implementation detail.
 *
 * Why it is off: the branch triggers on `!hardwareAccelerated || api < 31 || lowMemory`, and
 * API 26–30 falls entirely inside it. API 26 is a supported platform (constraint 1), so
 * enabling the branch would silently change the default appearance of every Android 8–11 device
 * to a theme nobody has yet reviewed on a television — against constraint 17 (UX is the product
 * gate). That the glass backend already resolves to BASIC on those devices only proves Poster
 * loses no optics; it does not prove Poster is the better-looking default.
 *
 * The branch must keep reading **stable** capability only when it is enabled. A theme that
 * flipped mid-session would cost a full-subtree recomposition plus a visible jump; transient
 * signals (`windowReady`, `powerSave`, `thermal`) already drive the glass backend.
 */
fun resolveVisualTheme(requested: VisualTheme, environment: GlassEnvironment): VisualTheme =
    when (requested) {
        VisualTheme.CINEMA -> VisualTheme.CINEMA
        VisualTheme.POSTER -> VisualTheme.POSTER
        // Reviewed capability rule, intentionally not enabled this version:
        //   !hardwareAccelerated || api < 31 || lowMemory -> POSTER
        // It stays mapped through the function below so the rule keeps its own tests.
        VisualTheme.AUTO -> VisualTheme.CINEMA
    }

/**
 * The reviewed AUTO rule. Not reached this version; [resolveVisualTheme] maps AUTO to CINEMA.
 *
 * Kept callable so its monotonicity and its independence from transient signals stay proven
 * while the default decision is pending 10-foot review. Enabling it is a one-line change in
 * [resolveVisualTheme] once that review exists.
 */
fun resolvedAutoThemeByCapability(environment: GlassEnvironment): VisualTheme = when {
    !environment.hardwareAccelerated -> VisualTheme.POSTER
    environment.api < 31 -> VisualTheme.POSTER
    environment.lowMemory -> VisualTheme.POSTER
    else -> VisualTheme.CINEMA
}

/** Resolve without a capability snapshot; used by callers that only fix the user's choice. */
fun resolveVisualTheme(requested: VisualTheme): VisualTheme =
    if (requested == VisualTheme.AUTO) VisualTheme.CINEMA else requested

/** Themes the settings surface offers. AUTO stays in the enum but is not a user choice. */
val selectableVisualThemes: List<VisualTheme> = listOf(VisualTheme.CINEMA, VisualTheme.POSTER)

/** The visual for a resolved theme. [VisualTheme.AUTO] is never a resolved value. */
fun visualFor(theme: VisualTheme): TvVisual = when (theme) {
    VisualTheme.POSTER -> PosterVisual
    VisualTheme.AUTO, VisualTheme.CINEMA -> CinemaVisual
}
