# Native TV reading scrim — 2026-11-23

Decision: direction **B · 分层承托** from three directions captured on the same AVD.
HOME protects only the hero text column; routes that put body text over the artwork get a
stronger reading scrim. The BLUR glass surface moves to 9 dp with a brighter face and
highlight.

This file records measured facts only. Items marked `BLOCKED` were not run and must not be
quoted as results.

## Provenance

| Item | Value |
|---|---|
| Source commit | `a83a2ac27316aac44104ce66c6c98f2fb3741be9` |
| Working tree at build | clean |
| Installed APK SHA-256 | `43a0dea94ebfddf746dc1e2501d20cf618d2fbfab255f03d30bac83cadb0258b` |
| APK build | debug, `:app:assembleDebug` |
| Device | AVD `cinematv-tv`, Google TV `android-34`, arm64-v8a, 1920x1080 @ 320 dpi, 2048 MB |
| Renderer | Android Emulator OpenGL ES Translator (Apple M4), OpenGL ES 3.0 |

The capture harness used for the screenshots is an untracked androidTest class; it is not
part of the application APK.

## What changed

| Layer | Before | After |
|---|---|---|
| Horizontal scrim, HOME | `.94 → .42 @46%` | `.96 → .60 @28% → .30 @50%` |
| Vertical scrim, HOME | `.46 / 0 @30% / .32 @63%` | `.28 / 0 @30% / .30 @63%` |
| Horizontal scrim, text routes | same as HOME | `.97 → .56 @40%` |
| Vertical scrim, text routes | same as HOME | `.62 / 0 @32% / .48 @66%` |
| BLUR surface | 12 dp blur, white face 9%, highlight 27% | 9 dp blur, white face 13%, highlight 36% |
| Hero title | 42 sp | unchanged |

`TvRootApp` passes `denseText = model.page != Page.HOME`. `PLAYER` renders no backdrop.

## Verification

- `:app:testDebugUnitTest` — BUILD SUCCESSFUL.
- `:app:assembleDebug` from the clean commit reproduces the measured APK hash.
- Device recapture of the committed code is byte-identical to the reference set the
  direction was chosen from:

| Screen | PNG SHA-256 |
|---|---|
| `home` | `b9809b7959ea434a1fc2e73542db490861c081a42f68910fd157faf4203bc476` |
| `home-rail` | `aa4c0136bdd3460d884045713f7fd7c8bd7c0727a624ad0c8b5d256bdfdb1a9a` |
| `movies` | `e2a76c097b7c019b564456f461bb5bdb4e32f9241f2d2f97e5beb84510913aad` |
| `detail` | `9390674153a869e1a19b8d1db7ca1353701c28279bf14ec0f0030fd513a849c6` |

- Text-region mean luminance (mean sRGB luminance over a fixed text block; the text pixels
  are identical in both builds, so the difference is the scrim alone):

| Screen | Before | After |
|---|---|---|
| HOME | 0.254 | 0.271 |
| MOVIES | 0.327 | 0.293 |
| DETAIL | 0.231 | 0.191 |

HOME is 0.017 brighter because the sky and sun are no longer dimmed; the two routes that
put body text directly on artwork are darker.

## Not covered (BLOCKED)

- **Golden baselines.** `design/ui/goldens/tv1080` and `tv4k` still hold the pre-change
  visuals, so a device QA run will report golden differences until they are recaptured and
  reviewed. This is expected and must not be resolved by loosening the thresholds.
- `FULL` tier: the AVD draws `BLUR` only, so the lens path is unverified on hardware.
- Physical Android TV, 4K viewport, thermal and low-RAM behaviour.

## Golden refresh procedure (follow-up)

1. `gh workflow run tv-smoke.yml --ref <branch> -f target=tv1080` and again with `tv4k`.
2. Download the `ui-evidence-tv1080` / `ui-evidence-tv4k` artifacts; keep the run ids and
   artifact ids.
3. For each screen, normalize `qa/ui-evidence/<screen>.png` to 960x540 with the existing
   comparator (`normalized()` in `scripts/compare-ui-goldens.py`) and the screen's masks,
   then write it over `design/ui/goldens/<viewport>/<screen>.png`.
4. Record raw size, raw SHA-256, normalized SHA-256, source commit, workflow run, artifact
   id, reviewer and review reason in that viewport's `manifest.json`.
5. Update the pinned constants in `scripts/tests/test_golden_provenance.py` and add a
   `design/ui/goldens/REVIEW.md` entry.
6. Re-run both lanes and require the `goldens` stage to pass before the change is treated
   as visually certified.
