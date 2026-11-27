# Reviewed visual baseline refreshes

## Detail refresh · 2026-11-04

Only `tv1080/03-detail.png` and `tv4k/03-detail.png` are refreshed in this change. All other golden bytes, every mask, the mean-error limit (0.020), and the changed-pixel limit (0.08) are unchanged. In particular, the previously reviewed settings baselines below retain their original provenance.

Source: commit `3b2e9842d9efa305f56f1cef0148b0fcd3fe0a45`, [run 41911066064](https://github.com/blackkcold/CinemaTV/actions/runs/41911066064), artifacts `13363940974` (1920×1080) and `13364155858` (3840×2160). Exact raw and normalized PNG SHA-256 values are recorded per screen in the manifests. Both targets completed 14 of 15 instrumentation tests successfully; the main route test failed immediately after the detail capture on its stale expectation that the status dialog initially focuses “想看” rather than the implemented safe default “取消”. That run remains failed. Screens and interactions after that assertion were not certified by this run.

The actual raw screenshots, 960×540 normalizations, and overlays against the old baselines were visually reviewed at both resolutions alongside the source changes. The new local-work-association action occupies its own full-width row. The synthetic fixture's `Synthetic 9.9` provenance and “片源已就绪” status are now explicit. The approved V4 glass, Back/Play/Bookmark icons, and corrected Noto font rendering account for the remaining control and typography differences. Title, metadata, description, primary actions, and the association label remain legible, separated, and within their bounds; the initial Play focus remains distinguishable. Both resolutions have matching normalized geometry. Old-baseline mean/changed errors were 0.049295/0.093636 (1080p) and 0.048176/0.094271 (4K), so accepting the old images would require weakening both gates, which this change does not do.

The source-ready line and association row intentionally move the episode heading down by approximately 97 normalized pixels. Only the top of the first episode row is now visible at the initial viewport edge. This is content continuing below the fold of the existing full-height `LazyColumn`, not text clipped inside a fixed-height control. It is a visible layout trade-off, not evidence that episode navigation passed. A fresh exact-commit device run must verify real D-pad traversal from Play through the association action into a fully visible episode row and restoration upward, in addition to all unchanged acceptance gates. No baseline for a scrolled episode state is inferred from this screenshot.

Raw CI captures remain untouched. Normalization uses the existing comparator's 960×540 RGB/LANCZOS path with no masks baked into the images. The focused provenance regression checks the new detail source/artifacts, both reviewed image hashes and sizes, and unchanged non-detail images and masks. This review does not certify the full UI suite, physical-device performance, a real LibreTV deployment, or preview-release readiness.

## Settings refresh · 2026-11-04

Only `tv1080/11-settings.png` and `tv4k/11-settings.png` are refreshed. Every other golden, mask, mean-error limit (0.020) and changed-pixel limit (0.08) is unchanged.

Source: commit `5576a108ee44f15d86adc2f215567cfeb79a31fb`, [run 41887458013](https://github.com/blackkcold/CinemaTV/actions/runs/41887458013). Both TV targets passed all eight instrumentation tests, including keyboard ownership and actual Compose CJK weight. Their settings images exceeded the old visual baseline; that source run remains failed and is not retroactively relabeled as passed. Per-screen manifests record exact artifact IDs, raw dimensions, raw-image SHA-256 and normalized-image SHA-256.

Two separate visual reviews inspected both actual resolutions and the difference overlays. The approved V4 pills replace old white active/primary controls with dark layered depth, the approved Back icon replaces a text glyph, and corrected Noto fonts change glyph shapes, weight and natural text measurements. CJK/Latin hierarchy, clear focus, labels, panel bounds and sidebar spacing are intact. New 1080p and 4K geometry agrees; an approximately six-pixel normalized old 4K vertical offset reflects the changed header font measurement, with unchanged layout code. The partially visible bottom field already existed at the scroll-panel edge. No new clipping, overlap, keyboard displacement or rendering regression was found. A1 startup motion is not on this page.

Existing masks cover the ephemeral loopback port and transient bottom notification; they are not expanded. Raw CI screenshots stay untouched. Golden images use the existing comparator's 960×540 RGB/LANCZOS normalization, without masks baked into the image. The comparison report includes each screen's baseline commit/run, using manifest defaults for the five original screens and explicit provenance for settings.

A new exact-commit device run must pass these reviewed baselines and every unchanged acceptance gate before preview publication. This review does not certify physical-device performance or a real LibreTV deployment.

## Production glass and motion refresh · 2026-11-05

The six explicitly reviewed screens `01-home.png`, `03-detail.png`, `05-movies.png`, `06-series.png`, `07-discovery.png` and `11-settings.png` are refreshed independently for `tv1080` and `tv4k`. No other baseline is added or inferred. Every mask, the mean-error limit (0.020), and the changed-pixel limit (0.08) are unchanged. The manifest-level defaults remain commit `07d85420df3fd13a83e0d9b41b740927d5011bc0` and run `41819223687`; all six rows now explicitly override those defaults, rather than rewriting history.

Source: commit `80a91e3ae7605a96717c7a238b37cefd81264104`, [run 41993059313](https://github.com/blackkcold/CinemaTV/actions/runs/41993059313). The exact per-image raw capture SHA-256, raw size and normalized baseline SHA-256 are recorded in each manifest row, together with its artifact ID and concrete visual-review reason.

- `tv1080`: artifact `13398969985`, raw captures 1920×1080. Downloaded ZIP SHA-256 `1408c53d8e9e15e88956d70039be9eba768c4d76ab5b3b81320e7fd574861e3e` (10,826,653 bytes).
- `tv4k`: artifact `13400124111`, raw captures 3840×2160. Downloaded ZIP SHA-256 `104fd1d4e938a5b451adefb4941394b24648ae114675a0d2297b8a8da51dfc41` (31,302,278 bytes).

Both viewports passed the canonical `TvUiRegressionTest` (1 test), the six native button tests and the ten native motion tests. Each viewport passed 19 of 21 instrumentation tests overall; two new, independent visibility tests failed. The large-font failure occurs after remounting at 12× font scale, before any Down input, when the initial Hero has zero clipped height. The cancellation failure measures a single pre-cancellation cached Foundation frame increment (4 pixels at 1080p, 8 pixels at 4K), not a new scroll request. These failures remain separate functional-verification work. **The source run remains failed; this baseline review does not retroactively mark it passed.** The canonical captures completed successfully before those independent failures.

The actual seven raw screenshots `01`, `02`, `03`, `05`, `06`, `07` and `11` were inspected individually at both resolutions, alongside earlier captures and the old baselines. The six canonical screens are accepted on their visible merits, not because a numerical comparison failed:

- Home: the approved CinemaTV identity, Noto typography and production glass navigation retain readable hierarchy and spacing. The foreground two-tone View Details focus ring is complete and does not cover its label.
- Detail: the new foreground two-tone Play ring makes focus explicit with Reduced Motion enabled, without relying on geometric scale or backdrop brightness. The Play icon/label, sibling buttons and association action remain separated and unclipped. The existing episode content still continues below the initial scroll fold, as documented in the historical review above.
- Movies and series: the short selected underline remains independently visible beneath All, including when the same button has a focus ring. Neither cue overlaps label glyphs. Portrait captions now prioritize valid numeric rating plus the actual `Synthetic` source, with intentional trailing metadata ellipsis; wide series captions retain rating, source, year and category. Hidden portrait rating digits were an existing v0.7 issue, not a new motion regression. The improvement does not invent ratings or change licensing/provenance policy.
- Discovery: the focused poster outline, centered title, score/source line and Catalog and Loading action remain legible and separate. Navigation and coverflow geometry remain intact.
- Settings: sidebar focus and persistent selection are clearly distinguished. Panel bounds, labels and input spacing remain intact; the partially visible bottom field is the existing scroll-panel boundary.
- Supplemental `02-focused-rail.png`: the focused image, title and rating line remain complete, with visible bottom-edge clearance at both resolutions. This image was reviewed but is not added to the six-screen baseline set.

No resolution-specific clipping, focus-ring thickness defect, label collision or unintended layout change was found. These static images do not establish runtime animation smoothness, physical-device FPS, real-service behavior or release readiness.

Raw CI captures remain untouched. Each baseline is produced only by the existing comparator's `normalized(raw, [])` path: RGB conversion followed by LANCZOS resizing to 960×540, with no masks baked into the PNG. All 30 source PNGs fully decode at their expected viewport dimensions; all 12 baseline PNGs fully decode as 960×540 RGB. Recomputing from the unchanged raw bytes reproduces the baseline PNG bytes and manifest SHA-256 values. Host checks protect the exact six-screen capture sources and hashes, original manifest defaults, every existing mask, comparison limits, and historical review text. Self-comparison against the source artifact validates normalization/provenance only, not a fresh device pass.

For byte reproduction, this refresh used Pillow 13.0.0 and `normalized(raw, []).save(output, format="PNG")` with default PNG encoder options, no added metadata and no optimization pass. The stored PNG SHA-256 identifies the exact reviewed encoding; the comparator still compares decoded, normalized pixels.

The final new tree must still pass the complete exact-commit acceptance matrix, including the independent visibility tests, these unchanged visual-comparison limits and all other required gates, before preview publication. Later production changes affecting pixels require fresh capture and review; no future passing result is inferred here.

### Superseded per-screen provenance

The historical reviews above are preserved verbatim. Their former explicit manifest capture records are retained below; these hashes identify the superseded PNGs, not the current baseline files.

- `tv1080/03-detail.png`: commit `3b2e9842d9efa305f56f1cef0148b0fcd3fe0a45`, run `41911066064`, artifact `13363940974`, raw size 1920×1080, reviewed `2026-11-04`. Raw SHA-256 `e7f9ff92d5bc57b5e44c8af6d2dcd34d10487fc63e337cb56cc12d7468e7b1c1`; baseline SHA-256 `54fcdb4ab13acaeb4896879dc9895e27149f4194d0307be9c26b0438a5ab6d84`.
- `tv1080/11-settings.png`: commit `5576a108ee44f15d86adc2f215567cfeb79a31fb`, run `41887458013`, artifact `13353160716`, raw size 1920×1080, reviewed `2026-11-04`. Raw SHA-256 `f7d5cff8d2acaa509c22aaa4f112f96c3385286391f2c031044f38a9060068a6`; baseline SHA-256 `f4113041908110f04eb9c28cfe331076b829a17b7ce744310c2a76d7fafce58c`.
- `tv4k/03-detail.png`: commit `3b2e9842d9efa305f56f1cef0148b0fcd3fe0a45`, run `41911066064`, artifact `13364155858`, raw size 3840×2160, reviewed `2026-11-04`. Raw SHA-256 `4449d164e4341c1924a73227944b1b5352fcc4a314d4a08a6ce87a90b0578d7a`; baseline SHA-256 `749e2176e215464042463eed5c635e71cec1b4e495104d0f4df8db67c8e15f53`.
- `tv4k/11-settings.png`: commit `5576a108ee44f15d86adc2f215567cfeb79a31fb`, run `41887458013`, artifact `13353325309`, raw size 3840×2160, reviewed `2026-11-04`. Raw SHA-256 `51d44392904580a3b43c7a25b82351a8fb71c95547709d3627490883e4d4cffa`; baseline SHA-256 `9c4ce90776a49f733dd5d44d3da85630d59e6937ec0b9994cfeae1ef54e58083`.

## Settings destinations refresh · 2026-11-06

Only `tv1080/11-settings.png` and `tv4k/11-settings.png` are refreshed. The other five PNGs and their complete per-screen provenance remain unchanged in each viewport. The original manifest defaults, mean-error limit (0.020), changed-pixel limit (0.08), and 960×540 normalization are retained.

Source candidate: commit `ce0ee6c11a936c0e1b0297f132957838502b6f5f`, [run 42183795256](https://github.com/blackkcold/CinemaTV/actions/runs/42183795256). The pull-request workflow checked out synthetic merge commit `6f3d64d375dc82d5ca467fb38db59ab5643354c0`. `tv1080` passed 62 of 62 instrumentation tests and its separate official LibreTV fixture acceptance; `tv4k` passed 18 of 18 instrumentation tests. Both exported all 14 canonical screenshots and then failed only the settings golden comparison. The phone job had two independent instrumentation failures. **The source run remains failed; accepting these reviewed images does not retroactively mark it passed.**

| Viewport | Artifact | Raw size | Raw PNG SHA-256 | Normalized PNG SHA-256 |
| --- | --- | --- | --- | --- |
| tv1080 | `13487249284` | 1920×1080 | `587954dc787a0705049f906ad588c8f80b338cc347946ac0452f5bf70abd4f8d` | `4236126c5b52dbd53d23511da0079ff666a74adc1628751c3d8539a965440151` |
| tv4k | `13488310708` | 3840×2160 | `24cb21b122f06cb5454353d1ea906fdcee1d03b9fbe139fc0a62664f16648f49` | `c1f013ed41c77001199aebb5e5e7f76dcfec4f67746e0f87fbe385bba0d9641c` |

Downloaded ZIP identities: `tv1080`, 25,039,919 bytes, SHA-256 `19741025b80fb71c6127b1fc0980e1bb9671da4e9c6f928013be59f5fcd3bd4d`; `tv4k`, 67,715,500 bytes, SHA-256 `1805ac955ad7a4336a63e4675f2e0f6e5659a52af785d49595c92e51d0cc2c91`.

Two reviewers inspected the current settings captures, their 960×540 normalizations and the prior baselines; the implementer also inspected the difference images. The new Developer Options, Local Recommendations and Library destinations use the existing sidebar controls. The Dev shortcut above the connection pane accounts for the vertical displacement of the existing server and metadata fields. Sidebar focus and persistent selection remain distinct, text stays separated, and panel bounds remain intact at both resolutions. The partly visible bottom field continues inside the existing scroll panel. This review accepts the intended destinations and Dev entry; it does not introduce a visual redesign.

The Dev shortcut moves the server field down **63 normalized pixels** at both resolutions. The existing ephemeral localhost-port mask therefore moves from `[455,199,515,235]` to `[455,262,515,298]`, with no area expansion. Its horizontal bounds and dimensions are unchanged. The existing bottom notification mask `[410,482,555,530]` and all masks on other screens remain unchanged. Control borders and all other pixels continue to be compared.

Against the old settings baselines, mean/changed errors were 0.033476/0.070604 at 1080p and 0.032889/0.066644 at 4K. All five other 1080p comparisons were zero. The 4K movies comparison was 0.000007/0; its other four non-settings comparisons were zero. No non-settings baseline is refreshed to absorb those results.

Raw CI captures remain untouched. Both new PNGs use the existing comparator's `normalized(raw, []).save(output, format="PNG")`, Pillow 13.0.0, default PNG encoder options, RGB/LANCZOS at 960×540, and no masks baked into the PNG. Focused host checks verify the exact reviewed sources, artifacts, PNG dimensions and hashes, previous history, unchanged non-settings captures, and the port mask's translation without growth. Source-artifact self-comparison verifies normalization and provenance only. The final candidate still requires fresh exact-commit acceptance with the unchanged visual gates before publication.

### Superseded 2026-11-05 settings captures

- `tv1080/11-settings.png`: commit `80a91e3ae7605a96717c7a238b37cefd81264104`, run `41993059313`, artifact `13398969985`, raw size 1920×1080, reviewed `2026-11-05`. Raw SHA-256 `2574fa628d8fb3f3baceba7aaed604d5c439fa72dc4f6948cb564bf894dfa8cd`; baseline SHA-256 `e63a35a868615d95b9c0f0cd924e37882ad4047a3aa0146c00a952e5e49ad2cd`.
- `tv4k/11-settings.png`: commit `80a91e3ae7605a96717c7a238b37cefd81264104`, run `41993059313`, artifact `13400124111`, raw size 3840×2160, reviewed `2026-11-05`. Raw SHA-256 `be69fd0bc6fad07f2d9fb19d44a3fbf1a8ba06d830f9eee46f10d457dfe3eb2c`; baseline SHA-256 `d86173e19bafc82bb9a6230ae6cd2f605df71df0bf3923082e88ba738f4bec36`.

## Reading scrim refresh · 2026-11-23

All six screens in both viewports are refreshed after the reading-scrim change: HOME now protects only the hero text column, text-dense routes use a stronger reading scrim, and the BLUR glass surface moved to 9 dp with a brighter face and highlight. Source commit `ee2b76d068447f85c273dc2e115e271b28f33d16`, which also lands the confirm-dialog default focus — without that fix the TV lanes abort at instrumentation and never reach the golden stage.

| viewport | run | artifact | size | instrumentation |
|---|---|---|---|---|
| tv1080 | 45134791580 | 14800510394 | 1920×1080 | 62 tests, 0 failed |
| tv4k | 45137482702 | 14802210389 | 3840×2160 | 18 tests, 0 failed |

Old baseline versus these captures, with both limits unchanged (mean 0.020, changed 0.08):

| screen | tv1080 mean / changed | tv4k mean / changed |
|---|---|---|
| `01-home.png` | 0.15032 / 0.32530 | 0.15004 / 0.32674 |
| `03-detail.png` | 0.06175 / 0.11822 | 0.06060 / 0.11304 |
| `05-movies.png` | 0.02081 / 0.00689 | 0.02070 / 0.00643 |
| `06-series.png` | 0.02091 / 0.00689 | 0.02079 / 0.00643 |
| `07-discovery.png` | 0.03079 / 0.02349 | 0.03077 / 0.02330 |
| `11-settings.png` | 0.10431 / 0.27353 | 0.10293 / 0.26830 |

Every screen was already outside the baseline before this change on the same branch: the untouched base measured 0.130 on `01-home` and 0.095 on `11-settings` at tv1080. This refresh accepts the current rendered state; it does not move a limit. Masks are unchanged, no mask is baked into a PNG, and normalization stays the existing 960×540 RGB/LANCZOS path.

Reviewed at both resolutions: the hero title, metadata and description keep their reading bed; the movie and series filter rows and the detail meta rows sit on a darker bed than before; the settings sidebar and the discovery cover flow keep their focus rings and are not clipped. Per-screen raw and normalized hashes are recorded in each `manifest.json`.

## 2026-11-24 — navigation face and paired poster focus ring

Source `8532f98a9d44fbac9b1e0f6eee97dfb4d5d1aa09`. The floating navigation now draws its own
face (white .045, veil .34) so 14sp labels clear 4.5:1 over a bright hero; the poster focus
ring uses the Ink 4dp + Ivory 2dp pair. `03-detail.png` is byte-identical because the detail
screen does not show the floating navigation.

| viewport | run | artifact | size | result |
|---|---|---|---|---|
| tv1080 | 45314161245 | 14885387790 | 1920×1080 | lane passed |
| tv4k | 45324367460 | 14889319771 | 3840×2160 | lane passed (21 suites, 40m budget) |

Old baseline versus these captures, with both limits unchanged (mean 0.020, changed 0.08):

| screen | tv1080 mean / changed | tv4k mean / changed |
|---|---|---|
| `01-home.png` | 0.00430 / 0.02534 | 0.00433 / 0.02548 |
| `03-detail.png` | 0.00000 / 0.00000 | 0.00000 / 0.00000 |
| `05-movies.png` | 0.00356 / 0.02040 | 0.00361 / 0.02067 |
| `06-series.png` | 0.00355 / 0.01960 | 0.00357 / 0.01976 |
| `07-discovery.png` | 0.00405 / 0.02071 | 0.00409 / 0.02157 |
| `11-settings.png` | 0.00029 / 0.00053 | 0.00028 / 0.00053 |

The tv1080 baseline came from `8532f98`; the tv4k baseline came from `5034b64` because the
intermediate 4K runs either hit a Maven Central 429 during dependency resolution or the
old 24m instrumentation budget after the lane grew to 21 suites. `03-detail.png` is
byte-identical at both viewports, so the change is exactly the navigation pill and the
poster focus ring.

Reviewed at tv1080: the pill keeps the artwork's warmth and shows the sky through it, every
tab label is legible, the selected lens stays distinct, and the filter row keeps its focus
outline. Masks and normalization are unchanged.

## Font variable-collection refresh · 2026-11-26

**No baseline is refreshed in this change.** This section records a measured font change whose
goldens are **not yet accepted**: the CI run that must produce the new captures has not been
requested yet. Every existing golden byte, mask, the mean-error limit (0.020) and the
changed-pixel limit (0.08) remain exactly as reviewed before.

### What changed

- CJK payload: `NotoSansCJK-Regular.ttc` (single 400 face per region; any request at 600 or above
  was synthesised by Skia's fake-bold stroke) replaced by `NotoSansCJK-VF.ttf.ttc`, a variable
  collection instantiating real 400/500/600. `FontSynthesis.None` is now set on the design
  type scale so a missing weight renders as the nearest real face instead of a synthetic stroke.
- `TvType.display`, `TvType.hero` and `TvType.h2` moved from `FontWeight.Bold` to `SemiBold`. 700
  is not in the approved §5.4 weight table, and with a 400/500/600 family Compose would have
  matched it down to 600 anyway — silently, while claiming a weight the face does not have.
- Latin gains a real Medium (`NotoSans-Medium.ttf`) so 500 is no longer a fallback to 400.

### Evidence: local A/B, not CI

Same AVD (Google TV API34, arm64, 1920×1080 @320, `-gpu host`,
`GLES: ... OpenGL ES 3.0 (4.1 Metal - 90.5)`), same synthetic fixture, `pm clear` before each run.
A = clean `bbb3061` build (static TTC). B = this change. Comparison by
`scripts/compare-ui-goldens.py` with its default limits.

| screen | mean | changed | gate |
|---|---|---|---|
| `01-home.png` | 0.009651 | 0.019041 | pass |
| `03-detail.png` | 0.023273 | 0.063773 | **fail** (mean > 0.020) |
| `05-movies.png` | 0.032165 | 0.103939 | **fail** |
| `06-series.png` | 0.048516 | 0.120822 | **fail** |
| `07-discovery.png` | 0.012613 | 0.026300 | pass |
| `11-settings.png` | — | — | **not captured** |

Three of six exceed the gate. They are **not** claimed as passing, and the limits are **not**
relaxed to make them pass.

### Nature of the difference (measured, not inferred)

Ink bounding boxes of fixed strings on the failing screens: `Δwidth = 0`, `Δheight = −1` for both
hero and subtitle on `05-movies` and `06-series`. Zero width change across four measurements rules
out reflow, wrapping, tracking change or a size change: glyph advance is identical. What moved is
stroke weight and the vertical position of ink inside the line box (10–18 px up), from the changed
face metrics. A 2× side-by-side of hero and subtitle shows open counters and clean strokes in B
versus the synthesised, half-filled counters in A. The direction is the improvement §5.4 specifies.

### Why `11-settings` is not captured

`TvUiRegressionTest` aborts at `TvUiRegressionTest.kt:297` on an assertion that predates both this
change and S1 (`git diff 9bd1a4b HEAD -- TvUiRegressionTest.kt` is empty for that file). `11-settings`
is captured after that point, so neither A nor B reaches it. It is recorded as **unverified**, never
as passing.

### `tv4k` is not measured by this refresh

`design/ui/goldens/tv4k/` carries the same six tracked screens and is equally affected: the face
change is resolution-independent, so the same three screens are expected to move there too. The A/B
above was captured only at 1920x1080 (`tv1080`), so **no tv4k claim is made here** — it is
unmeasured, not passing and not failing. The `tv-smoke` run that produces the new tv1080 captures
must also run the `tv4k` lane, and both viewports are refreshed from that single run rather than
accepting one and leaving the other stale.

### Accepted only through CI

A local AVD capture has no `workflowRun`, so writing it here would either fail
`scripts/tests/test_golden_provenance.py` or require inventing a run id. Following the precedent
already recorded above ("The source run remains failed"), the refreshed baselines must come from a
CI `tv-smoke` run whose golden stage fails on these three screens, with the captures reviewed and
the failing run recorded as failed in the manifest and here.

### Follow-up, not fixed here

The three failing screens use inline `fontSize = X.sp` with no `lineHeight`, so line height comes
from font metrics and a face change shifts text vertically. `TvType` styles carry explicit line
heights and are unaffected. Converging those inline sizes onto `TvType` (§5.5 of the theme plan) is
what removes this sensitivity; it is out of scope for the font change.

## Two-track theme refresh · 2026-11-27

Both viewports are refreshed from **one** run: `tv-smoke` run `45942087491`, source
`6fe7aa13953afa72bae8d52d974f0d63ba716073`, artifacts `15174690731` (`tv1080`) and
`15174735855` (`tv4k`). Refreshing both from a single run is what the 2026-11-26 section
required and what keeps the two viewports comparable.

**The source run remains failed.** Its golden stage failed on five of six screens in each
viewport, and this review accepts the captures only after measuring why. The run is recorded
as failed in the manifest via its `workflowRun`, not retroactively marked green.

### Why five of six screens move

Two accepted product changes, not capture noise:

- The CJK variable-collection change of 2026-11-26: real 400/500/600 SemiBold replaces a
  synthesised bold stroke. Ink bounding boxes keep `Δwidth = 0` and `Δheight = −1` on the
  tracked strings, so no reflow, wrap, tracking or size change is involved; only stroke
  rendering and the ink position inside the line box move.
- S1's stable region keys and single version list. `03-detail` lifts its action row because
  the ad-hoc source list became one version list; the row height, labels and focus ring are
  unchanged, and the entries that disappeared were the same source listed twice.

| screen | tv1080 mean/changed | tv4k mean/changed | gate |
|---|---|---|---|
| `01-home.png` | 0.009651 / 0.019043 | 0.010335 / 0.020610 | pass before this refresh |
| `03-detail.png` | 0.041035 / 0.101510 | 0.040131 / 0.095046 | **fail** (mean and changed) |
| `05-movies.png` | 0.032156 / 0.103752 | 0.032629 / 0.104475 | **fail** |
| `06-series.png` | 0.048506 / 0.120633 | 0.049258 / 0.122982 | **fail** |
| `07-discovery.png` | 0.012371 / 0.025694 | 0.013447 / 0.027741 | pass before this refresh |
| `11-settings.png` | 0.113564 / 0.408916 | 0.112414 / 0.404749 | **fail** (structural, see below) |

The limits are **not** relaxed: every comparison here and in CI still uses mean ≤ 0.020 and
changed ≤ 0.08.

### `11-settings` never matched this branch, and its port mask had drifted

The superseded settings baseline came from source `8532f98`, which is **not** an ancestor of
`dev-compose`: `95376a5` ("0.9.0 candidate without the personal-account experiment") removed the
Douban developer entry from this line before the baseline was taken elsewhere. The old capture
therefore still shows a `开发者选项 · Dev` badge that this product no longer renders, and the whole
content column sat lower. That structural difference, not the theme, is the bulk of 0.40 changed.

The ephemeral-port mask had the same problem. `UiFixtureServer` binds `ServerSocket(0)`, so the
port in the URL row is random on every run, and the mask exists to cover it. With the content
lifted, the old box `[455, 262, 515, 298]` landed on empty background while the real port — measured
at normalised `y 225-239`, `x 464-494` by differencing two independent captures — stayed visible.
The mask is now `[455, 218, 515, 254]`: **same 60×36 size, same x band, moved only in y**, so it
still follows its input without expanding. Without this move the next run would fail again on a
different random port.

### Reproducibility: same platform, two independent runs

Accepting a capture is only meaningful if the capture is a stable function of the product. Two
independent `tv-smoke` runs on the same platform (`tv1080` API33 x86_64 1920×1080 @320;
`tv4k` API33 x86_64 3840×2160 @640) were differenced with the reviewed masks:

- `tv1080` runs `45925465321` vs `45942087491`: `01-home`, `03-detail`, `05-movies`, `06-series`,
  `07-discovery` are **pixel-identical** (`mean = 0.000000`).
- `tv4k` runs `45925465321` vs `45942087491`: same five screens, `mean ≤ 0.000014`, max channel
  delta 4/255.

**Not verified:** `tv4k/11-settings.png` had no second same-platform capture, because the earlier
run lacked that screen (the regression aborting `TvUiRegressionTest` was still present). Its new
baseline is asserted by measurement and geometry, **not** by replay, and this section does not
claim otherwise. The next `[ui-qa]` run is the first opportunity to confirm it.

### The local AVD is not a golden judge

A local AVD (API34, arm64) and the CI emulator (API33, x86_64) rasterise text and background
gradients differently: differencing the same product code across the two gave `03-detail` 0.060953
and `07-discovery` 0.037919 while three other screens stayed at ~0.0001. Those are platform
differences, not defects, and a local capture cannot become a baseline — it has no `workflowRun`.
Every number above therefore comes from CI captures, and local AVD runs were used only to locate
which assertion was failing, never as evidence that a golden passes.
