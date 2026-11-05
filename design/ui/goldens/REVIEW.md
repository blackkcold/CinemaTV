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
