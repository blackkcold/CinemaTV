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
  This is not a measured APK delta; check the built artifact before release.
- Latin 400 and 700 are real font files. CJK ships only 400; bold/medium and italic
  use Android/Compose synthetic styling. No claim of true CJK 500/600/700 masters.
  This deliberately avoids another approximately 20 MB collection on TV devices.

Both original copyright/OFL 1.1 licenses ship in `assets/fonts/`. Fonts remain under
OFL, separate from the application's MIT source license. Do not strip the licenses.

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

`app/noto-fonts.gradle.kts` creates `prepareNotoFonts`; `preBuild` depends on it.
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

## Verification and release gate

- `python scripts/fonts/verify-noto-fonts.py`: standard-library offline verification
  of exact byte counts/hashes, names, versions, region indexes, shared CFF table and
  bundled OFL notices. It fails if assets are missing, not silently skips.
- `NotoRegionPolicyTest`: JVM mapping, override, fallback, script and index tests.
- `NotoFontsDeviceTest`: real packaged loading, cache identity, missing-asset fallback
  and exact mixed-text/surrogate preservation. Run on API26 and current TV/phone.
- Run `:app:testDebugUnitTest :app:assembleDebug`, inspect the APK for the three fonts
  and two OFL files, then run connected instrumentation tests.
- Still require physical-device screenshot comparisons for SC/TC/HK/JP examples,
  synthetic bold legibility, Latin/CJK baseline alignment, cold/warm startup,
  memory on low-RAM TV, accessibility font scaling, clipping/ellipsis and focus/Back.
  A parsed font file is not proof of a working API26 GPU/device render.

## Official sources

- https://github.com/notofonts/noto-cjk/tree/f8d157532fbfaeda587e826d4cd5b21a49186f7c/Sans
- https://github.com/notofonts/noto-fonts/tree/ffebf8c1ee449e544955a7e813c54f9b73848eac/hinted/ttf/NotoSans
- https://notofonts.github.io/noto-docs/website/use/
- https://developer.android.com/reference/android/graphics/Typeface.Builder#setTtcIndex(int)
- https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-text/src/androidMain/kotlin/androidx/compose/ui/text/font/AndroidFont.android.kt

Typeface.Builder and collection index selection are supported from Android API26,
matching this app's minSdk. Actual Compose/API26 integration remains subject to CI
and the device tests above; no physical-device pass is asserted by this document.
