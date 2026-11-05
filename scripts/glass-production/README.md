# Production glass native QA

This is a focused production-integration check, separate from the glass lab and
from media/release acceptance. It renders the actual `TvRootApp` in landscape or
`MobileRootApp` in portrait, with `CinemaViewModel`, bundled Noto, and the existing
offline `UiFixtureServer(homeCatalog=true)`. The non-exported debug activity only
supplies immersive window chrome and passive observers. It is not in release.

## Evidence and coverage

- API35: one emulator, TV 1920×1080 at 320dpi, then phone 1080×2400 at 420dpi.
  Each viewport captures Home, Detail, and navigation for requested Full, Blur,
  and Basic. TV checks focus separately from selected/activated tab and restores
  hero focus after Back. Phone uses native touch, selected tabs, and Back.
- API31: TV narrow Home captures for all preferences; requested Full genuinely
  resolves to Blur. Basic additionally checks Detail and navigation.
- API26: the same narrow safe path; all preferences genuinely resolve to Basic.
- Phone Home alone owns its local glass scene. The debug host supplies a passive
  page scope captured during composition. Phone Detail requires new Basic draws,
  zero Full/Blur draws, and zero live-source draws in the DETAIL scope. Legitimate
  outgoing Home draws remain in global diagnostics, never mislabelled as Detail.
  Phone catalog navigation is captured for state/input, not claimed glass coverage.
- `MobileGlassButtonTest` preserves native target/role/disabled behavior.
  The TV pass on each SDK additionally runs the small `GlassMaterialSmokeTest`,
  nine `FocusMediaCardMotionTest` cases, and eight `InterruptedFocusRestorationTest`
  cases. Exact execution is required: 21 tests per TV SDK and three on phone.
  Interrupted restoration tests pause entry/lazy frame work and inject native
  D-pad events; newer navigation/content wins, cancellation never falls back,
  and ordinary saved-target/fallback behavior remains covered.
- Reduced motion freezes geometry; it never overrides static material or device
  capability. Production startup still waits for a committed lightweight frame,
  window focus, and the following frame. No test forces those gates open.

Every capture records source SHA, SDK, native viewport/density, requested tier,
production policy decision/reason/environment, observed renderer draw counts,
decoded local artwork, foreground checks, and PNG SHA-256. The shared
`TvWindowCapture` fences a real hardware window frame and reads the native display
through UiAutomation. No crop, scaling, PixelCopy, Compose forced raster, video
surface, cross-window dialog, or fabricated screenshot is used.

Native hardware Canvas on an emulator software GPU is functional evidence, not
physical-device GPU performance certification. Runtime artifacts include actual
SurfaceFlinger renderer information and emulator launch configuration. A genuine
conservative policy fallback is retained in evidence but fails the intended
Full/Blur coverage gate. The suite never silently bypasses it or labels it Full.

## Bounded CI

`.github/workflows/production-glass.yml` triggers only for the dedicated branch
`feature/production-glass-v2` or manual dispatch, not pull requests or main pushes.
The build job verifies pinned Backdrop source, existing host contracts, these
harness tests, both modules' unit tests, APK build, lint, fonts, archive and debug
signature. It uploads both APKs once with SHA-256 receipts tied to the exact
commit. Emulator jobs download those bytes and never run Gradle. API35 uses one
emulator for TV then phone; API31 and API26 each use one narrow TV pass. Each
viewport is hard-bounded to ten minutes, including install/setup/export. Stop on
failure rather than recycling an ANR emulator into a misleading next pass.

```sh
python3 -m unittest discover -s scripts/glass-production -p 'test_*.py' -v
# After the build job has produced dist/ and with the matching SDK emulator:
bash scripts/glass-production/run-matrix.sh 35 "$(git rev-parse HEAD)"
```

Log checking does not require `logcat -c`, which failed before test startup on
API26. It continuously reads the explicit main/system/crash buffers from a device
clock timestamp, then requires unique START and END messages to appear under the
expected log tag. Only that verified span is checked for crashes/ANRs. Initial
replay remains in `live-test.log` for diagnosis but cannot fail this test span.
Missing/duplicate markers, a reader exit, or an unsupported start timestamp fail
closed. `log-boundary.json` records the strategy, marker verification state and
command output on failure. Incomplete logs are clearly named `device-partial.log`
and cannot pass the crash gate. No failed clear is simply ignored and no full
boot log is substituted for test evidence.

JUnit execution is checked from raw instrumentation status, not `adb` exit code.
Skipped, unexecuted, failed, crashed, duplicated, or missing tests fail validation.
The runner leaves native/partial capture metadata, input trace, logs and runtime
state under `qa/production-glass/`. `result.json` is the viewport's terminal result.
No APK is published or released, no PR is opened, and no broad media suite runs.
