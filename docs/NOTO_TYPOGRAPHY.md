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
- Latin 400 and 700 are real font files. CJK ships only 400; bold/medium and italic
  use Android/Compose synthetic styling. No claim of true CJK 500/600/700 masters.
  This deliberately avoids another approximately 20 MB collection on TV devices.

Both original copyright/OFL 1.1 licenses ship in `assets/fonts/`. Fonts remain under
OFL, separate from the application's MIT source license. Do not strip the licenses.

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

First build requires access to raw.githubusercontent.com. Once generated, an
unchanged build reuses Gradle outputs; `--offline` works with verified local files.
`clean` removes them, so an offline clean build needs these exact files restored
into `app/build/generated/notoAssets/fonts` from a trusted build cache. A missing or
invalid file under `--offline` fails with an actionable message. The APK is entirely
offline for fonts after installation, independent of the build cache/network.

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
  the real lock and downloads. Verifies cold download, offline reuse, same-length
  corruption rejection, online repair and missing-offline rejection. It checks
  hashes, no orphan `.part` files and no offline mutation. Gradle 8.13 completed
  all five cases locally on 2026-11-04; this is not an Android build/Lint pass.
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
