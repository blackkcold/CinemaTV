# Offline Noto UI/content typography

## Scope and integration

`CinemaNotoProvider` owns a process-wide, mutex-protected cache loaded on
`Dispatchers.IO`. It never downloads fonts at runtime. The first frame and a failed
asset use Android sans-serif, preserving usable navigation rather than blocking
startup. Failures are cached and exposed as `CinemaNotoFonts.failedAssets`; there is
no retry/reload in recomposition. Editable controls and library-owned labels use the selected CJK family (including
its built-in Latin glyphs), even on English devices; explicit Text adapters use the
separate Latin family. Both phone and TV typography preserve the existing
size, weight, tracking and leading. The approved outlined CinemaTV brand remains
untouched and is not re-typeset.

`CinemaText` / `CinemaTvText` are String-Text adapters. They apply the actual Noto
Sans Latin font to ordinary runs and the selected Noto Sans CJK face to Han, kana,
hangul, bopomofo and CJK/fullwidth punctuation. Supplementary-plane characters are
walked by code point, not split UTF-16 halves. Text, semantics, focus, line limits,
callbacks and content are preserved. `cinemaNotoText` offers the same behavior for
explicit AnnotatedString callers; it accepts the language of the displayed text.
The existing MediaItem contract does not include that field. We do not incorrectly
use a movie's original language for translated display text or infer nationality
from Han characters.

Policy is content language metadata, then the first CJK language in the ordered
app/user locale list, then SC for untagged Han. Explicit Hans selects SC even in HK;
Hant selects TC except HK/MO selects HK. zh-CN/SG selects SC; zh-TW selects TC;
zh-HK/MO and yue select HK; ja selects JP; ko selects KR. No simplified/traditional
conversion occurs. A changed Android configuration updates locale selection.

## Licensed original assets and size

All font bytes are unmodified, not locally subsetted:

- Noto Sans CJK Regular **2.004**, one 19,484,784-byte OpenType Collection.
  The first five faces are JP=0, KR=1, SC=2, TC=3, HK=4. The ten-face upstream
  collection also contains five Mono faces, unused but sharing the large CFF table.
  Keeping the upstream file intact makes provenance and upstream signature/hash
  verification simple; it is much smaller than separate regional OTF files.
- Noto Sans Regular and Bold **2.008**, 569,208 and 575,740 bytes.
- Total font payload: **20,629,732 bytes**; local DEFLATE estimate **16,087,259 bytes**.
  Font extensions are now explicitly `noCompress`, so the intended font ZIP entries
  occupy the full **20,629,732 bytes**, plus ZIP/alignment overhead. That is about
  **4,542,473 bytes more** than the DEFLATE estimate. This is not a measured total
  APK delta; check the built artifact before release.
- Latin 400 and 700 are real font files. CJK ships only 400; requested weights 600 and above and italic
  use Android/Compose synthetic styling. Requested 500 stays at the regular face
  under Compose's standard synthesis policy. No claim of true CJK 500/600/700 masters.
  This deliberately avoids another approximately 20 MB collection on TV devices.

Both original copyright/OFL 1.1 licenses ship in `assets/fonts/`. Fonts remain under
OFL, separate from the application's MIT source license. Do not strip the licenses.

## Weight-resolution correction

The original `FontFamily(android.graphics.Typeface)` adapter was incorrect for CJK
weight requests. The built APK identifies Compose ui-text **1.10.6**. Its exact
published source shows `AndroidTypefaceWrapper.getNativeTypeface` returns its
wrapped face unchanged and ignores requested weight/style/synthesis. Therefore the
regular CJK face stayed regular in bold headings, while the separate Latin font
list correctly selected its real bold file. This was a real mixed-weight regression,
not an intended visual difference of Noto. Native specimens that explicitly set
`Paint.isFakeBoldText` did not test that Compose path.

Regional faces now use `FontFamily(PreloadedNotoFont(typeface))`: a public-API
`AndroidFont` descriptor advertising weight 400/style normal. Its blocking loader
only returns the already-loaded native object, with no filesystem/network access.
Compose's font-list resolver then applies its built-in requested-weight/style
synthesis and honors `FontSynthesis.None`. API26/27 use the platform style-creation
path; API28+ use weight/style creation. The shared TTC index and original bytes,
background load, cache and failure fallback remain unchanged. No new font files.

`NotoFontsDeviceTest.composeCjkBoldHonorsWeightAndSynthesisOnBothSurfaces` checks
resolved native bold flags and captures real `CinemaText` and `CinemaTvText` output
for each regional face, comparing regular/bold/synthesis-disabled CJK-only samples
at the same size. It requires greater stroke coverage for synthetic bold, and the
regular raster when synthesis is disabled, comparing crop dimensions and SHA256 of
raw ARGB pixels as well as ink. It writes the display PNG, three specimen crops and
ink/pixel-hash JSON to `ui-evidence/noto/compose-weight`.

On API30/33, capture uses one bounded UiAutomation compositor screenshot for each
region/wrapper pair (ten total), then crops all three semantics rectangles from that
same display image. This capture scope is unchanged. No version uses Compose
force-redraw/captureToImage. Before each
capture, a draw-observed epoch is changed with a frame listener armed; the test waits
for that epoch to draw, a fresh window frame, and Compose idle. Foreground activity
and accessibility package are checked around the readback. Late/abandoned bitmaps
are recycled. The dedicated Noto test Activity is not assumed fullscreen: the Compose
host's measured screen and window positions translate `boundsInWindow` into display
coordinates. Out-of-display/clipped rectangles fail. Evidence records full display
dimensions, window/screen offsets, exact crop bounds and the fresh draw epoch. No
scaling or guessed (0,0) origin is permitted.

### Window-entry readiness and strict background guards

Exact-main `5942260`, run `41891554892`, artifact `ui-evidence-tv4k`, failed the
first SC regular/no-synthesis ink comparison. Its `phone-sc-display.png` and three
specimen crops show a compositor-blended background: explicitly black areas became
RGB (195,195,195), and white glyph interiors became RGB (251,251,251). All 22,565
regular/no-synthesis pixel differences were at crop x=1006..1119, to the right of
the glyphs, and only darkened the background by at most 19 levels. Glyph foreground
masks matched exactly. The expected/actual ink totals were 57,134,411 / 57,304,430,
versus 6,345,833 for both unblended SC crops in the earlier passing 4K run. Native
weight/synthesis flags had already passed. This is contaminated display evidence,
not evidence of different CJK strokes. The blend is consistent with an unfinished
WindowManager entrance transition; the artifact does not identify the specific
compositor layer. The readiness gate covered Compose idle and a freshly drawn app
frame, but did not wait for the separate window-entry completion.

The debug-only, non-exported `NotoTestActivity` now records the real
`onEnterAnimationComplete()` callback in a per-instance latch. The instrumentation
thread waits at most ten seconds before creating/capturing the specimen content.
There is no fixed sleep, injected callback or screenshot retry. A host that stops is rejected rather than reusing stale readiness; recreation
creates a new latch. The callback is not itself a physical-presentation fence, so
the existing fresh-frame, Compose-idle, focus/package and coordinate gates remain.
The full-app TV test host and device animation settings are unchanged.

Three inset guard pixels in each specimen's empty right margin must be exactly
opaque black before the unchanged native flags, ink, dimensions and raw-pixel SHA
assertions are accepted. The display/region PNG, all crops and a per-pair
`*-capture.json` with guard values and capture provenance are written before any
raster assertion can fail. A blended frame now fails explicitly as capture
contamination instead of being misdiagnosed as a font-weight change.

`noAnimationLaunchStillReportsEnterTransitionComplete` exercises a real
`FLAG_ACTIVITY_NO_ANIMATION` launch without auto-passing. The public callback is
available since API21. AOSP API26 explicitly notifies immediately visible and
non-animating opening windows; API30/33 retain corresponding completion paths.
The new host and guard checks still require exact-commit device execution.

Callback sources:
- https://developer.android.com/reference/android/app/Activity#onEnterAnimationComplete()
- https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r36/services/core/java/com/android/server/wm/AppWindowToken.java
- https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/services/core/java/com/android/server/wm/AppTransitionController.java

### v0.7 specimen launch and epoch fence

Candidate run `41964523868` exposed a smaller tonal contamination on API33 TV1080:
black became RGB (1,1,1) and glyph interiors (251,251,251), despite the real entry
callback. Against the passing v0.6 same-target images, background and white glyph
masks had identical geometry, and each old gray value mapped to just one new gray
value (approximately `new = 0.9806 * old + 1`, RMS 0.07 gray levels). The font,
host and capture source had not changed. This is evidence of compositor tonal
contamination, not a CJK stroke or weight change; the specific layer is not proven.

The font specimen rule now requests `FLAG_ACTIVITY_NO_ANIMATION` only for its
own `NotoTestActivity` launch. This test isolates font rendering and is not an
Activity-transition acceptance test. Product activities, the shared host and global
animation settings are unchanged. The real entry callback, uninterrupted-host
requirement and exact opaque-black/pixel-hash assertions remain mandatory.

The previous fresh-frame fence could accept a late old metrics callback after a
new epoch had drawn. The replacement arms before mutation, takes timestamps from
public `Choreographer.FrameCallback` callbacks, records only the first actual draw
of the target epoch, and waits at most ten seconds for a matching-or-later
`FrameMetrics.VSYNC_TIMESTAMP`. Unarmed/wrong epochs, stale metrics and redraws
that would move the first-draw fence are covered by a device regression test.
Callbacks/listeners are removed in `finally`; no pixel retries or relaxed gates
are introduced. Each capture JSON records target epoch, first-draw timestamp and
matched actual-vsync timestamp. This proves the app-frame ordering, **not** physical
SurfaceFlinger presentation; display readback and black-background guards remain
separate requirements. Exact-candidate full device execution is still required.

### API26 readback compatibility

API26 has a narrow test-only readback route. In main `5576a10` run `41887458013`,
SurfaceFlinger logged `size mismatch (1920,1080) > (1080,1920)` after framebuffer
permission was granted; UiAutomation returned null before any specimen raster
assertion. The other 12 API26 tests passed. This is evidence of an emulator display
size/orientation readback failure, not a font/weight failure or permission denial.

For API26 only, the helper directly requests PixelCopy of the three specimen
rectangles' union from our own focused, non-floating Activity window. It refuses
FLAG_SECURE windows and never clears security flags. It retains the fresh-frame /
Compose-idle barrier, package/focus checks, measured window coordinates and exact
weight/ink/pixel-hash assertions. The bounded readback is at most two million pixels
with no resize; native copy and callback wait run off-main under a ten-second
caller deadline. A late result is recycled only after the native copy returns;
errors or timeouts fail rather than skip the test. There is no retry with weaker
assertions, no altered device orientation and no display-security workaround.

API26 evidence is explicitly a `*-window-region.png`, not a full display screenshot.
JSON records route, API, union-window rectangle, capture dimensions/origin and each
crop's window/screen/capture coordinates; full-display dimensions are null for this
route. Ten union captures still produce all thirty specimen crops. API26 passed
all 13 tests on main `5942260` in run `41891554892`, including the exact raster
comparisons. Subsequent test-host changes must pass their own device gate.
The API26 method contract and synchronous-copy/callback behavior were checked in
Android 8.0 source:
https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/graphics/java/android/view/PixelCopy.java
 No assertion compares different regional faces
or relies on a platform-specific golden. The native specimen remains useful for
asset/glyph checks, but cannot replace this Compose regression gate. Device/CI
execution of this correction is still required before accepting new goldens.

Exact dependency source used to verify the behavior (no dependency was added):
https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.10.6/ui-text-android-1.10.6-sources.jar
(`AndroidTypefaceWrapper.android.kt`, `AndroidFont.android.kt`, `FontSynthesis.android.kt`).

## Packaging and low-RAM tradeoff

AOSP AAPT2's current default non-compression extension list does not include TTF or
TTC. We therefore explicitly set `androidResources.noCompress` for both rather than
rely on AGP/default behavior. `AssetManager.openFd` requires an uncompressed asset;
stored entries permit direct file-backed access without first inflating the entire
font asset. This trades approximately 4.54 MB of APK size versus our DEFLATE estimate
for avoiding that compression/decompression step. Font bytes remain unmodified.

The device evidence opens the actual installed APK, requires each font ZIP entry to
be STORED, records actual compressed/uncompressed entry sizes, and requires a valid
`openFd` descriptor with matching length. It records the asset offset/alignment.
These are packaging/mapping-eligibility checks, **not** measurements of RSS, PSS,
startup time or Typeface internal allocations. We do not assume five regional
Typeface builders share all native data merely because their TTC tables are shared.
Native parsing, shaping and glyph caches may still allocate per face; low-RAM device
profiling remains required. The build from PR #5 at `dbef072` produced a **44,732,874-byte APK**
(SHA256 `a9f02d931597b6aa4ff5310394faa7d720bdd05d722469637b54f3b60dd30ab3`).
Direct archive inspection verified all three font entries are STORED with the
locked hashes and both OFL files are present. Device `openFd` and native rendering
checks remain separately gated; these archive facts are not a runtime pass.

## Coverage boundaries

The pinned CJK fonts contain 44,810 mapped Unicode code points each, with the
regional glyph differences in the shared collection. The Latin files map 2,840
code points including Latin, Greek and Cyrillic. The complete upstream repertoire
is retained; there is no UI-text-only subset that loses arbitrary film titles.
This is not all of Unicode: characters absent from these fonts (including newer
CJK extensions and many historic/rare characters), emoji, Arabic, Indic and other
unbundled scripts depend on Android system fallback. We do not promise coverage for
all code points or identical emoji across OEMs. Existing shaping/bidi is delegated
to Android/Compose, not replaced with manual glyph positioning.

## Reproducible build and offline behavior

`app/noto-fonts.gradle` is a standard Groovy applied script (avoiding the observed
Kotlin applied-script UAST crash in Lint, without disabling any Lint checks). It creates `prepareNotoFonts`; `preBuild` depends on it.
`app/build/generated/notoAssets` is an Android assets source. The exact immutable
GitHub revisions, URLs, byte counts and SHA256 are recorded in
`scripts/fonts/noto-fonts.lock.json`. No unpinned CDN URL, runtime download or font
provider dependency is involved. The task checks every existing file before reuse,
rejects redirects, non-200 responses, excess length or hash mismatch, has connect/
read timeouts, and downloads to a temporary file before replacing the final file.
Invalid input fails the build. No partial file becomes a packaged font.

A cold build without verified local assets requires access to raw.githubusercontent.com.
The durable download cache lives in `.cache/cinematv-noto-fonts`, outside both
`build/` task outputs and `.gradle/`. Actions restores only that directory with an
exact lock-hash `noto-fonts-v2` key. The task checks size and SHA256 before reuse,
then copies through a verified temporary file into generated assets. Only generated
assets enter the APK, so the cache does not add another packaged font copy.

This separation fixes a reproduced Gradle 8.13 lifecycle issue: when task execution
history is absent, BasePlugin deletes stale generated outputs before `doFirst` or
`doLast`, even if Actions has just restored valid font bytes there. Restoring into
the output directory was therefore not a reliable cache. The dedicated cache
survives `clean`, deleted task history and a fresh checkout with no generated files.
`--offline` can restore from that verified cache, repair corrupt generated output,
or seed the cache from valid generated files. If neither copy is valid, it fails
with an actionable message. The APK remains entirely offline for fonts after
installation, independent of the build cache/network.

## Deterministic screenshot host

The debug-only `TvTestActivity` installs the same `CinemaNotoProvider` as production.
It exposes readiness only after every regional face is loaded with no reported
asset failures. TV regression setup and capture both wait for this signal (15s
bounded wait, with failures surfaced), so screenshots cannot silently record the
initial system-font fallback. This adds no brand animation. Production still shows
a usable system-font first frame. Phone tests use MainActivity, which already
installs the provider; native font evidence is separately captured by the device test.

## Verification and release gate

- `python scripts/fonts/verify-noto-gradle.py --gradle /path/to/gradle --report /tmp/noto-task-evidence`:
  executes the actual Groovy task in an SDK-free temporary Gradle project, with
  the real lock, BasePlugin and official downloads. Ten scenarios cover genuine
  cold download, offline reuse, clean, deleted execution history, cache-only fresh
  checkout, corrupt-output repair, invalid-cache rejection, online repair,
  missing-offline rejection and verified-output migration. The deleted-history
  case requires the stale-output deletion log and successful verified-cache reuse.
  Hashes and orphan `.part` files are checked throughout. Gradle 8.13 completed all
  ten cases locally on 2026-11-04; this is not an Android build/Lint pass.
  Uses an isolated writable Gradle home; honors a credential-free HTTPS proxy
  when supplied by the environment. Logs remain in the requested report directory.
- `python scripts/fonts/verify-noto-fonts.py`: standard-library offline verification
  of exact byte counts/hashes, names, versions, region indexes, shared CFF table and
  bundled OFL notices. It fails if assets are missing, not silently skips.
- `NotoRegionPolicyTest`: JVM mapping, override, fallback, script and index tests.
- `NotoFontsDeviceTest`: real packaged loading, cache identity, missing-asset fallback
  and exact mixed-text/surrogate preservation. Run on API26 and current TV/phone.
- Run `:app:testDebugUnitTest :app:assembleDebug`, inspect the APK for the three fonts
  and two OFL files, then run connected instrumentation tests.
- `NotoFontsDeviceTest.regionalFontsRenderWithOfflineEvidence` rasterizes seven
  labeled SC/TC/HK/JP/KR/Latin-regular/Latin-bold rows into a 1200×1640 native Canvas
  bitmap, with 40px regular and 64px synthetic-bold/real-bold samples. Each row
  checks `Paint.hasGlyph` for every non-space code point, verifies measured text
  fits, and counts non-background pixels separately in both sample regions.
  Outputs `ui-evidence/noto/noto-regional-native.png` plus matching JSON containing
  the exact packaged asset SHA256, TTC index, locale, weight, sample and ink counts.
  This nested directory keeps non-window samples separate from viewport goldens.
  No assertion assumes regions must rasterize differently. The sample is software
  Canvas evidence, not proof of Compose layout, hardware rendering or screen UI.
  Run this test class in the API33/phone matrix and separately on API26 as a small
  minimum-SDK smoke gate. Those executions are not asserted by this source change.
- Still require physical-device screenshot comparisons for SC/TC/HK/JP examples,
  synthetic bold legibility, Latin/CJK baseline alignment, cold/warm startup,
  memory on low-RAM TV, accessibility font scaling, clipping/ellipsis and focus/Back.
  A parsed font file is not proof of a working API26 GPU/device render.

## Official sources

- https://github.com/notofonts/noto-cjk/tree/f8d157532fbfaeda587e826d4cd5b21a49186f7c/Sans
- https://github.com/notofonts/noto-fonts/tree/ffebf8c1ee449e544955a7e813c54f9b73848eac/hinted/ttf/NotoSans
- https://notofonts.github.io/noto-docs/website/use/
- https://developer.android.com/reference/android/graphics/Typeface.Builder#setTtcIndex(int)
- https://developer.android.com/reference/android/content/res/AssetManager#openFd(java.lang.String)
- https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/AndroidResources
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/cmd/Link.cpp
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/Asset.cpp
- https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-text/src/androidMain/kotlin/androidx/compose/ui/text/font/AndroidFont.android.kt

Typeface.Builder and collection index selection are supported from Android API26,
matching this app's minSdk. Actual Compose/API26 integration remains subject to CI
and the device tests above; no physical-device pass is asserted by this document.
