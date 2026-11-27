# CinemaTV TV test plan

This is the single entry point for how the native TV surfaces are tested, what each layer
proves, and what must never be claimed from it. It complements
`docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md` (the governing plan)
and `docs/GLASS_PRODUCTION.md` (the glass contract).

## Layers

| Layer | Command | Proves | Does not prove |
|---|---|---|---|
| JVM unit tests | `:app:testDebugUnitTest` | ViewModel/state machines, identity, persistence helpers | Rendering, focus, glass |
| Script contracts | `python3 -m unittest discover -s scripts/tests` (plus `perf_common`, `native-tv-perf`, `glass-production`) | Protocol rules, fixtures, CI wiring, golden ledger | Anything on a device |
| Instrumentation compile | `:app:assembleDebugAndroidTest` | Test code compiles for the exact candidate | That any test ran |
| Device lanes | `tv-smoke.yml` matrix (`phone`, `tv1080`, `tv4k`, `tvNative`, `tv29`, `tv31`, `tv36`, `compat26`) | D-pad/OK/Back, focus restoration, IME, playback intent, golden screenshots on those viewports | Physical TV behaviour |
| Native perf E-gate | `scripts/native-tv-perf/` + `FrameTimingRecorder` + `NativePerfProbe` | Input → state → drawn-frame causality, paired latency, drawn glass tier, protocol validity | Frame rate on a TV SoC |
| T-gate (optional, owner) | Manual run on a physical Android TV | Physical-TV confidence for whoever runs it | Release acceptance; it is not a release gate |

Emulator evidence is E-gate only: protocol/relative, never a 60 fps or TV certification claim.

## Local TV AVD

`cinematv-tv` is a Google TV API34 arm64 image at 1920x1080/320 dpi with 2 GB RAM.

```sh
$ANDROID_HOME/emulator/emulator -avd cinematv-tv \
  -no-snapshot-save -no-boot-anim -gpu host -no-audio
```

`-gpu host` is required for hardware acceleration. The AVD config already sets
`hw.gpu.mode=host`, but an explicit `-gpu swiftshader_indirect` on the command line overrides
it and silently falls back to software rendering. Verify the renderer after boot:

```sh
adb shell dumpsys SurfaceFlinger | grep -i GLES
# host:     Android Emulator OpenGL ES Translator (Apple M4), OpenGL ES 3.0 (4.1 Metal)
# software: ... (SwiftShader Device (LLVM ...)), OpenGL ES 3.1
```

Software rendering is acceptable for layout/focus checks and never for timing.

When the run ends, stop the AVD and any test container it used (`adb emu kill`, then
confirm no qemu process; `docker stop` or `rm` for the container), and leave no
Gradle/Kotlin daemon. These are shared local resources, not background services.

## Acceptance lane on the emulator

```sh
GRADLE=$TMPDIR/opencode/toolchain/gradle-8.13/bin/gradle   # no wrapper in this repo
$GRADLE --no-daemon -PcinematvNativeTvAcceptance=true \
  :app:assembleRelease :app:assembleReleaseAndroidTest \
```

Release output is unsigned; sign both APKs with the local debug keystore for installation
only. Then run the production entry with the measured artifact identity:

```sh
adb shell am instrument -w \
  -e class dev.cinematv.TvNativePerformanceAcceptanceTest \
  -e sourceSha    "$(git rev-parse HEAD)" \
  -e apkSha256    "$(shasum -a 256 app-release-debug.apk | cut -d' ' -f1)" \
  -e renderer     "emulator-gles3-metal-m4" \
  -e glassQuality ENHANCED \
  -e tierDirect   FULL \
  dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner
```

Arguments:

| Argument | Meaning |
|---|---|
| `sourceSha` | 40-hex commit the APK was built from; the run is rejected otherwise |
| `apkSha256` | 64-hex installed APK hash; the run is rejected otherwise |
| `renderer` | Actual renderer description; never inferred |
| `glassQuality` | Sets the persisted preference before measuring: `AUTO`, `BASIC`, `BACKDROP`, `ENHANCED` |
| `tierDirect` | Fails unless that tier was actually drawn (`FULL`, `BLUR`, `BASIC`) |

The run document is written to `native-tv-perf/<uuid>.json` under the app's external files.
Validate it on the host, never by eye:

```sh
adb pull /sdcard/Android/data/dev.cinematv/files/native-tv-perf/<uuid>.json run.json
python3 scripts/native-tv-perf/analyze.py run.json     # exit 0 only when valid
```

A run is valid only when the identity, scene, purpose, input receipt, applied state and the
responding frame are all present; `window-overlap`, `response-shared-frame`,
`not-enough-paired-inputs`, `response-before-state` and `response-outside-window` are hard
failures. The protocol is `scripts/perf_common/protocol.py` v4.

## Glass tiers

`glassQuality` is a preference; the policy may lower it. Expected mapping on API 31/33+:

| Preference | Platform mode | Notes |
|---|---|---|
| `BASIC` | Basic | no backdrop work |
| `AUTO` | Blur | conservative default |
| `BACKDROP` | Blur | explicit blur |
| `ENHANCED` | Full | requires API 33+, hardware acceleration, window ready, non-low-memory, no power save, no severe thermal |

A `tierDirect` assertion must follow a `glassQuality` argument; asserting `FULL` under `AUTO`
tests nothing and is the mistake that produced the earlier "the emulator never draws FULL"
conclusion. Full is still unproven on TV hardware until a physical run draws it.

## Matrix

| Surface | Where | Gate |
|---|---|---|
| Three-state pages (loading / error / empty), page tags | `TvPageStateTest` on `tv1080`/`tv4k` | CI |
| D-pad, OK, Back, IME, focus restoration | `TvUiRegressionTest`, `TvRouteBackTest`, `TvImeBackTest`, `TvPlayerBackLadderTest`, `TvStatusDialogTest`, `TvLaunchSmokeTest` | CI |
| Catalog, library, work association, dashboard | `TvLibraryCatalogControlsTest`, `TvWorkNavigationTest`, `TvRecommendationDashboardTest` | CI |
| Glass material invariants | `GlassMaterialSmokeTest`, `GlassActionLayoutTest` | CI |
| Golden screenshots (6 screens × 1080p/4K) | `TvUiRegressionTest` + `compare-ui-goldens.py` | CI, human review on change |
| Production entry + perf causality | `TvNativePerformanceAcceptanceTest` on `tvNative` and the local TV AVD | E-gate |
| Physical TV acceptance | Optional owner run with the receipt template below | Not required; T-gate |

Only `tvNative` (Android TV image, `isTv=true`, production `MainActivity`) is television
evidence for the production entry. `tv1080`/`tv4k` use phone images at wide viewports and
prove geometry/rendering only. `cmd uimode` has no `television` subcommand (verified on
API 34), so a phone cannot be switched into TV mode through adb; a phone can run the TV
composition only through a debug-only entry, and that is never TV certification.

## Change checklist

1. JVM tests and script contracts pass.
2. The exact candidate compiles `assembleDebugAndroidTest` (and the acceptance variant when
   perf code changes).
3. Device lane for the changed viewport passes, including goldens when visuals change.
4. For perf/glass changes: a valid `analyze.py` run with the real drawn tier, plus the
   before/after numbers.
5. Evidence file records source SHA, APK SHA-256, device/AVD, renderer, tier and limitations.
6. A T-gate receipt is optional. When no physical run happened, the release notes say so;
   no emulator result is ever relabeled as physical.

## Known open items (2026-11-23)

- `tier-direct` sampling: one FULL run paired 4/6 while two `input-causality` runs on the same
  build paired 6/6 and 5/6. Treat `tier-direct` as a tier check and `input-causality` as the
  latency sample; investigate the 450 ms window under FULL before tightening anything.
- `TvFrameTrace` is not wired; `FrameTimingRecorder` is the only frame source.
- Physical TV T-gate (optional, owner-run), 4K/HDR and thermal coverage remain unmeasured.

## T-gate receipt (optional, owner-run)

The T-gate is an optional manual run on a physical Android TV; it is not a release
blocker. Collect the surrounding facts first;
the script installs nothing and changes no device setting:

```sh
scripts/t-gate-receipt.sh app-release-debug.apk > /tmp/t-gate-receipt.md
```

It records the model, vendor, Android release, build fingerprint, `ro.build.characteristics`,
the current configuration, the foreground window, the source commit, the APK SHA-256 and the
signer SHA-256. A receipt without a television identity (`characteristics` contains `tv`)
cannot serve as T-gate evidence.

Fill the checklist by hand and keep the result in `docs/evidence/<date>-t-gate.md`:

| Step | Expected |
|---|---|
| Cold start | `MainActivity` reaches HOME |
| D-pad | Every rail is reachable; no focus trap, no lost focus |
| DETAIL | Back returns to the section that opened it |
| Dialog | Cancel is focused by default; one Back closes it and returns focus to its trigger |
| SEARCH | First Back hides the keyboard and keeps the page; the next follows the page rule |
| Playback | Panel → controls → previous route, in that order |
| Browsing routes | Back returns HOME; HOME Back leaves the app |
| Stability | No crash, ANR or visible stall during the run |

A signature mismatch stops the run: never uninstall or clear data to work around it, and never
relabel a phone or emulator result as TV evidence.

## Two contracts that look like product bugs

Both have already cost a release lane a week of red runs. They are platform behaviour, not defects.

**HOME Back has two legitimate implementations.** With no local state to pop, the system either
finishes the activity or moves its task to the background, and which one you get depends on the
image. The API33 Google TV image takes the second — its device log shows
`ActivityTaskManager: moveTaskToBack` and lifecycle goes `PAUSED` → `STOPPED` with
`isFinishing` never becoming true, while the local API34 Google TV AVD takes the first. Assert
the contract — the app leaves the foreground and no second press is needed — not
`isFinishing`. Poll that state with `runOnMainSync` on the instrumentation thread: once the app
is backgrounded the Compose clock is no longer idle, so `rule.waitUntil` throws
`ComposeNotIdleException` instead of answering.

**Television identity is declared in one of two ways.** `ro.build.characteristics` is one
source, not the only one: the API33 Google TV system image reports `emulator` there and declares
itself through `android.hardware.type.television` and `android.software.leanback_only`. A gate
that requires the property alone fails closed on a truthful television build, which is how the
only production-TV lane stayed red while the product behaved correctly. Accept both sources;
pair `android.software.leanback` with a check that the device is not a phone, since Leanback is
also present on some phones.

## Failure triage

- Re-run once with the same artifact before suspecting the code; record both runs.
- Never fix a red gate by widening a threshold, skipping an assertion, or editing a baseline
  without a review note.
- Infrastructure failures (Maven 429, emulator attach, runner disk) are diagnosed from the
  job log and re-run; they are not product failures and are not counted as passes.
- **A local-only failure is an environment difference until proven otherwise.** Before calling
  it a repo defect, check whether CI runs the same suite and what it reports:
  ```sh
  grep -rn "unittest discover\|scripts/tests" .github/workflows/*.yml
  gh api repos/<owner>/<repo>/actions/jobs/<apk-job-id>/logs | grep -E "Ran [0-9]+ tests|OK$"
  ```
  A suite that is green on CI and red on one workstation is describing the workstation. Report
  the local failure as a host gap with its cause, and do not rewrite the fixture to make the
  host pass: changing a harness that CI depends on, to satisfy an environment it never runs in,
  edits the thing under test instead of the setup. This has already happened once here — a
  macOS-only `TypeError` was "fixed" by replacing the fixture's real `timeout` propagation with
  a local imitation, which changed what the assertions measure. It was reverted.
- A flaky test is fixed by removing the timing assumption; if it cannot be made
  deterministic, the assertion is narrowed and the gap is written down.

### Known flaky: tv4k `TvRecommendationDashboardTest`

`RecommendationDashboardUiTest.kt:48` waits 15 s for `model.focused != null`. That value is only
assigned inside `loadHome()` after it joins the asynchronous recommendation build, so the wait is
inherently waiting for the ranker rather than for a route transition.

Measured A/B on the same commit and lane, from the JUnit XML of each run:

| Run | Result | Test wall time |
|---|---|---|
| `45960680901` (full CI) | failed at the 15 s wait | 31.982 s |
| `45966092461` (re-run) | passed, continued past it | 58.989 s |

The passing run is the slower one, so "4K software rendering is too slow" is not established:
the failing run only looks shorter because it aborts at the timeout. The XML records one wall
time per test case and nothing per statement, so there is no measurement of how long this
particular wait actually needs. **Do not raise the 15 s budget on this evidence** — a wider
budget would hide the race instead of removing it. The durable fix is to wait on a deterministic
signal (the recommendation build completing) rather than on a producer that runs after it.
