# 2026-11-24 GPU rendering and compatibility study

**Status: E-gate only.** Emulator, debug build, 2 GB AVD. This is not a TV certification,
not a frame-rate claim and not a release acceptance. `TOTAL_DURATION` inverse is not FPS.

## Scope

The study isolates three emulator-level variables on the production TV entry (`MainActivity`):

1. Emulator 37.2.12 vs 37.3.10 (the latter contains the macOS App Nap fix and KosmicKrisp).
2. Guest Vulkan explicitly off vs explicitly on (`-feature -Vulkan` vs `-feature Vulkan`).
3. `hw.gltransport=pipe` vs `virtio-gpu-pipe`, cold boot, with `-gpu host` held throughout.

RAM, vCPU count, GPU mode, package identity, product code, glass quality and UI were not changed.

## Upgrade and rollback

| Item | Value |
|---|---|
| Installed emulator | `37.3.10.0 (build_id 16970787)` |
| Backup | `/Users/11169285/android-sdk-backups/emulator-37.2.12-20261124` |
| Backup version | `37.2.12.0 (build_id 16428233)` |
| Backup size | 1.2 GB |
| sdkmanager note | `yes \| sdkmanager` returned 141 (SIGPIPE from `yes`); installed list and `emulator -version` both confirm 37.3.10 |
| Vulkan default in 37.3.10 | `advancedFeatures.ini` still has `Vulkan = off`; both arms were passed explicitly |
| KosmicKrisp | `libkosmickrisp_icd.json` present, API 1.4.363; runtime selection is reported below, not assumed from file presence |

## Candidate identity

| Item | Value |
|---|---|
| Branch / HEAD | `dev-compose` / `8ecb49a0bb5fc5ac85638f7a76a783411dc59e88` |
| App APK | `app-debug.apk`, SHA-256 `3b88d733f0a32e85769059991f4dd2ceb9e3925e6520880136539d0b71434c6c` |
| Test APK | `app-debug-androidTest.apk`, SHA-256 `68a12b5c302f0b00c4bd1a7090ee2421ebed577fa3ef6cb2b1535ae3c50647c1` |
| Probe source | `app/src/androidTest/java/dev/cinematv/GpuFrameProbeTest.kt`, SHA-256 `9bf03adf1d49f71e91a0895b03e0293256a8563a33040ccd0a126f3a25efc3b8` |
| Build type | debug, versionCode 9; not the release artifact |
| Product code | unchanged at `8ecb49a`; probe and test-only code were removed after the study |

## Environment

| Item | Value |
|---|---|
| Host | macOS 26.4.1 / Apple M4 |
| AVD | `cinematv-tv`, Google TV API 34, arm64, 4 vCPU, 2048 MB, 1920×1080 @ 320 dpi |
| Display | `refreshFrameRate 60.000004`, `presenceDeadline 16666666` |
| Renderer | `GLES: Google (Apple), Android Emulator OpenGL ES Translator (Apple M4), OpenGL ES 3.0 (4.1 Metal - 90.5)` |
| HWUI pipeline | `debug.hwui.renderer=skiagl`; `ro.hwui.use_vulkan` empty |
| Guest Vulkan | `ro.hardware.vulkan=ranchu`; `cmd gpu vkjson` has no device with `-feature -Vulkan`, has a device with `-feature Vulkan` |
| Transport | `pipe` for B1/C1/C2/C1r/B2; `virtio-gpu-pipe` for C3/C4, verified by `ro.boot.hardware.gltransport` and `ro.boot.qemu.gltransport.name` |
| Glass policy environment | API 34, `lowRam=false`, `memoryClassMb=192`, hardware accelerated, window ready, power save off, thermal `NONE`, reduced motion off |

Host load was not idled, by explicit instruction. B1 ran during Xcode/Word/WindowServer load;
B2 and C1r are the drift controls. Snapshots are in
`/private/var/folders/ks/flqyrh0j5wvf2t7v4s7lcnz0b5tfzv/T/opencode/gpu-study/`.

## Protocol

- Production entry `MainActivity` + `UiFixtureServer(homeCatalog=true)`; 24 featured items;
  `updateReducedMotion(false)`; `Page.HOME`.
- Four unmeasured `DPAD_DOWN` presses to leave the hero/navigation focus; a 5 s pilot then a
  fixed six-step right/left bounce.
- One 20 s window per run, `Instrumentation.sendKeyDownUpSync` at absolute 60 ms deadlines.
  No `adb shell input`, no Compose `performKeyInput`, no 22-key `glass-performance` profile.
- `Window.OnFrameMetricsAvailableListener` on a HandlerThread; raw `TOTAL_DURATION`,
  `GPU_DURATION`, `DRAW`, `SYNC`, `COMMAND_ISSUE`, `SWAP`, `INTENDED_VSYNC`, `VSYNC`,
  `DEADLINE`, `FIRST_DRAW_FRAME`, callback drops.
- Actual glass mode read in the same run via `NativeGlassProbe.observation()` and
  `drawnTier()`; `tierOk` compares `actualMode` with AUTO→BLUR / ENHANCED→FULL.
- `jank%` is the share of produced frames with `VSYNC_TIMESTAMP > INTENDED_VSYNC_TIMESTAMP`
  (missed intended vsync), not `TOTAL_DURATION > 16.67 ms`. Cadence is samples per 20 s,
  never FPS. p99 is coarse: about 650 frames per run.
- Input receipt is counted at `Window.Callback.dispatchKeyEvent`; all full runs delivered
  334/334 keys except one B1 run at 329/329.
- **Focus telemetry limitation:** `pilotScore` was 0 and `NativePerfProbe` events were 0 in
  every measured run; the model focus key did not change. The keys reached the Activity
  window, but the Compose focus target remained the HOME hero/navigation action area, not
  a poster card. The frame/glass/tier measurements are valid; input-to-media-focus
  causality is not proven by this probe. The release `TvNativePerformanceAcceptanceTest`
  remains the input-causality lane.

## Results

### Condition summary (median; range across runs)

| Block | Emulator | Vulkan | Transport | Tier | p50 ms | p90 ms | p99 ms | GPU p50 ms | jank % | n | Actual |
|---|---|---|---|---|---|---|---|---|---|---|---|
| B1 | 37.2.12 | off | pipe | AUTO | 34.1 (33.8–34.3) | 44.9 (38.2–51.7) | 80.3 (67.8–92.9) | 12.6 (12.3–12.8) | 18.0 (5.0–30.9) | 640–662 | BLUR |
| B1 | 37.2.12 | off | pipe | ENHANCED | 34.5 (34.4–34.7) | 54.2 (52.7–55.6) | 114.3 (99.7–128.9) | 14.2 (13.8–14.6) | 18.6 (12.2–24.9) | 622–639 | FULL |
| C1 | 37.3.10 | off | pipe | AUTO | 17.1 | 19.0 (17.8–19.7) | 34.0 (33.7–51.3) | 14.1 (14.0–14.3) | 0.4 (0.3–0.8) | 664–667 | BLUR |
| C1 | 37.3.10 | off | pipe | ENHANCED | 17.1 | 18.1 (17.8–18.9) | 42.1 (34.0–63.4) | 14.1 (14.1–14.2) | 0.4 (0.3–0.5) | 664–667 | FULL |
| C2 | 37.3.10 | on | pipe | AUTO | 17.1 | 19.4 (17.7–26.5) | 36.0 (28.9–68.9) | 14.1 (14.1–14.2) | 0.6 (0.2–1.2) | 658–667 | BLUR |
| C2 | 37.3.10 | on | pipe | ENHANCED | 17.1 | 19.9 (18.8–20.0) | 46.1 (33.9–50.3) | 14.1 (14.1–14.3) | 0.5 (0.3–0.6) | 658–667 | FULL |
| C3 | 37.3.10 | off | virtio | AUTO | 17.1 (17.1–17.2) | 18.5 (18.3–18.8) | 19.9 (19.6–20.7) | 12.5 (12.5–12.6) | 0.0 (0.0–0.1) | 667–668 | BLUR |
| C3 | 37.3.10 | off | virtio | ENHANCED | 17.1 | 18.5 (18.2–18.6) | 26.7 (25.5–33.7) | 12.5 (12.5–12.6) | 0.0 | 667–668 | FULL |
| C4 | 37.3.10 | on | virtio | AUTO | 17.1 (17.1–17.2) | 19.2 (18.5–25.3) | 39.3 (19.8–84.4) | 12.6 (12.6–12.8) | 0.2 (0.0–0.9) | 655–668 | BLUR |
| C4 | 37.3.10 | on | virtio | ENHANCED | 17.1 (17.1–17.2) | 18.5 (18.5–18.7) | 33.8 (21.3–42.0) | 12.8 (12.7–12.8) | 0.2 (0.0–0.3) | 663–668 | FULL |
| C1r | 37.3.10 | off | pipe | AUTO | 17.2 | 20.1 (20.1–20.2) | 59.5 (50.3–68.7) | 13.9 | 1.3 (0.9–1.7) | 657–659 | BLUR |
| C1r | 37.3.10 | off | pipe | ENHANCED | 17.1 (17.1–17.2) | 19.3 (18.8–19.8) | 42.8 (33.8–51.7) | 14.0 | 0.7 (0.3–1.1) | 659–665 | FULL |
| B2 | 37.2.12 | off | pipe | AUTO | 17.1 / 33.0 | 19.3 / 36.0 | 51.5 / 106.7 | 13.9 / 11.8 | 0.3 / 3.8 | 663 / 656 | BLUR |
| B2 | 37.2.12 | off | pipe | ENHANCED | 17.1 / 34.0 | 18.5 / 44.8 | 20.9 / 117.0 | 14.2 / 12.5 | 0.3 / 9.0 | 667 / 642 | FULL |

B2 is deliberately shown as two runs rather than a median because it is bimodal: the first
run is at the 60 Hz budget, the second is the same ~34 ms regime as B1. The 37.2.12 emulator
is unstable under this host; 37.3.10 C1–C4 and C1r are stable at 17.1–17.2 ms.

### Per-run appendix

| Run | Tier | Mode | n | dispatched/handled | p50 ms | p90 ms | p99 ms | GPU p50 ms | jank % |
|---|---|---|---|---|---|---|---|---|---|
| B1 rep0 | AUTO | BLUR | 640 | 334/334 | 34.3 | 51.7 | 92.9 | 12.8 | 30.9 |
| B1 rep0 | ENHANCED | FULL | 622 | 329/329 | 34.7 | 55.6 | 99.7 | 14.6 | 24.9 |
| B1 rep1 | AUTO | BLUR | 662 | 334/334 | 33.8 | 38.2 | 67.8 | 12.3 | 5.0 |
| B1 rep1 | ENHANCED | FULL | 639 | 334/334 | 34.4 | 52.7 | 128.9 | 13.8 | 12.2 |
| C1 rep0 | AUTO | BLUR | 664 | 334/334 | 17.1 | 19.0 | 51.3 | 14.0 | 0.8 |
| C1 rep0 | ENHANCED | FULL | 665 | 334/334 | 17.1 | 17.8 | 34.0 | 14.1 | 0.3 |
| C1 rep1 | AUTO | BLUR | 664 | 334/334 | 17.1 | 18.2 | 33.7 | 14.1 | 0.3 |
| C1 rep1 | ENHANCED | FULL | 667 | 334/334 | 17.1 | 18.1 | 42.1 | 14.1 | 0.4 |
| C1 rep2 | AUTO | BLUR | 667 | 334/334 | 17.1 | 19.7 | 34.8 | 14.3 | 0.6 |
| C1 rep2 | ENHANCED | FULL | 665 | 334/334 | 17.1 | 18.9 | 63.4 | 14.2 | 0.5 |
| C2 rep0 | AUTO | BLUR | 666 | 334/334 | 17.1 | 19.4 | 36.0 | 14.1 | 0.6 |
| C2 rep0 | ENHANCED | FULL | 664 | 334/334 | 17.1 | 18.8 | 50.3 | 14.1 | 0.6 |
| C2 rep1 | AUTO | BLUR | 662 | 334/334 | 17.1 | 17.7 | 28.9 | 14.2 | 0.2 |
| C2 rep1 | ENHANCED | FULL | 667 | 334/334 | 17.1 | 19.9 | 33.9 | 14.3 | 0.3 |
| C2 rep2 | AUTO | BLUR | 658 | 334/334 | 17.2 | 26.5 | 68.9 | 14.1 | 1.2 |
| C2 rep2 | ENHANCED | FULL | 663 | 334/334 | 17.2 | 20.0 | 46.1 | 14.2 | 0.5 |
| C3 rep0 | AUTO | BLUR | 667 | 334/334 | 17.2 | 18.8 | 20.7 | 12.6 | 0.0 |
| C3 rep0 | ENHANCED | FULL | 668 | 334/334 | 17.1 | 18.5 | 33.7 | 12.5 | 0.0 |
| C3 rep1 | AUTO | BLUR | 667 | 334/334 | 17.2 | 18.5 | 19.9 | 12.5 | 0.0 |
| C3 rep1 | ENHANCED | FULL | 668 | 334/334 | 17.1 | 18.6 | 26.7 | 12.6 | 0.0 |
| C3 rep2 | AUTO | BLUR | 668 | 334/334 | 17.1 | 18.3 | 19.6 | 12.5 | 0.0 |
| C3 rep2 | ENHANCED | FULL | 667 | 334/334 | 17.1 | 18.2 | 25.5 | 12.5 | 0.1 |
| C4 rep0 | AUTO | BLUR | 668 | 334/334 | 17.1 | 18.5 | 19.8 | 12.6 | 0.0 |
| C4 rep0 | ENHANCED | FULL | 663 | 334/334 | 17.1 | 18.7 | 33.8 | 12.7 | 0.3 |
| C4 rep1 | AUTO | BLUR | 655 | 334/334 | 17.2 | 25.3 | 84.4 | 12.8 | 0.9 |
| C4 rep1 | ENHANCED | FULL | 668 | 334/334 | 17.1 | 18.5 | 21.3 | 12.8 | 0.0 |
| C4 rep2 | AUTO | BLUR | 666 | 334/334 | 17.1 | 19.2 | 39.3 | 12.8 | 0.2 |
| C4 rep2 | ENHANCED | FULL | 666 | 334/334 | 17.2 | 18.5 | 42.0 | 12.8 | 0.3 |
| C1r rep0 | AUTO | BLUR | 657 | 334/334 | 17.2 | 20.2 | 68.7 | 13.9 | 1.7 |
| C1r rep0 | ENHANCED | FULL | 665 | 334/334 | 17.1 | 18.8 | 33.8 | 14.0 | 0.3 |
| C1r rep1 | AUTO | BLUR | 659 | 334/334 | 17.2 | 20.1 | 50.3 | 13.9 | 0.9 |
| C1r rep1 | ENHANCED | FULL | 659 | 334/334 | 17.2 | 19.8 | 51.7 | 14.0 | 1.1 |
| B2 rep0 | AUTO | BLUR | 663 | 334/334 | 17.1 | 19.3 | 51.5 | 13.9 | 0.3 |
| B2 rep0 | ENHANCED | FULL | 667 | 334/334 | 17.1 | 18.5 | 20.9 | 14.2 | 0.3 |
| B2 rep1 | AUTO | BLUR | 656 | 334/334 | 33.0 | 36.0 | 106.7 | 11.8 | 3.8 |
| B2 rep1 | ENHANCED | FULL | 642 | 334/334 | 34.0 | 44.8 | 117.0 | 12.5 | 9.0 |

All runs used `Page.HOME`, the 20 s window and the same test APK. `AUTO` always resolved to
`BLUR`, `ENHANCED` always resolved to `FULL`; `tierOk=true`; no fallback occurred.

## Findings

1. **37.3.10 stabilises the frame budget; 37.2.12 is bimodal.** All 37.3.10 blocks
   (C1–C4, C1r) hold p50 17.1–17.2 ms with jank mostly <1%. B1 (37.2.12) is 33.8–34.7 ms
   with jank 5–31%; B2 (37.2.12) is 17.1 ms in rep0 and 33.0–34.0 ms in rep1. The host was
   not idle, so this is not a clean causal A/B; the stable 37.3.10 result across 28 runs and
   the known App Nap fix make 37.3.10 the stabilising change. Do not quote a 2× speedup as a
   general property: 37.2.12 reached 17.1 ms when the host window was favourable.
2. **Vulkan on/off does not change the app's rendering path.** C2 ≈ C1 and C4 ≈ C3
   (p50 17.1 ms; GPU p50 14.1 vs 12.5–12.8). `-feature Vulkan` does enable the guest Vulkan
   HAL (`vkjson` device present), but `debug.hwui.renderer=skiagl` and an empty
   `ro.hwui.use_vulkan` show the Compose/HWUI pipeline remains SkiaGL. This is not evidence
   for or against app-level Vulkan; a separate `debug.hwui.renderer=skiavk` arm would be a
   new variable.
3. **virtio-gpu-pipe reduces GPU time, not the 60 Hz total.** C3 (off) and C4 (on) have
   GPU p50 12.5–12.8 ms vs 14.0–14.3 ms for pipe; total p50 stays 17.1 ms because the frame
   is aligned to the 60 Hz budget. C3's p90/p99 are generally tighter; C4 has a few slow
   outliers. The transport was verified active on both virtio boots.
4. **The actual glass tier is proven, not inferred.** AUTO→BLUR and ENHANCED→FULL in every
   run; the environment is not low-RAM/thermal/power-save. ENHANCED FULL draws on this 2 GB
   API 34 AVD. BLUR and FULL have similar p50 in this HOME/hero workload; FULL's p99 is
   sometimes higher. There is no UX basis for lowering the tier.
5. **Input/focus limitation.** Each full run delivered 334/334 keys (329/329 in one B1 run)
   to the Activity window, but `pilotScore=0`, `perfEvents=0` and the model focus key stayed
   unchanged. The measured surface is the HOME hero/navigation focus area, not poster
   traversal. The frame cadence, GPU duration, actual tier and host state are valid; a
   per-key input→media-focus→frame pairing is not. That remains the release acceptance lane.
6. **Host noise is a first-class limitation.** B1 ran under Xcode/Word/WindowServer load;
   B2 and C1r bracket the drift. The B2 bimodality (17.1 vs 33–34 ms) is the clearest sign
   that the host, not the GPU, produced the slow 37.2.12 windows. A quiet-host A/B is still
   needed before a causal performance claim.

## What this does not prove

- No FPS and no TV certification. `TOTAL_DURATION` and GPU duration are window metrics;
  the 20 s sample cadence is not a frame rate.
- No app-level Vulkan rendering; `-feature Vulkan` only toggles the guest Vulkan HAL.
- No per-key input causality or poster traversal; the probe's focus target stayed on the
  HOME hero/navigation area.
- No release build, no physical TV, no thermal soak, no T-gate. The AVD is 2 GB and the host
  was not idle. p99 is coarse at ~650 frames per run.
- No claim that `virtio-gpu-pipe` improves the product experience; at this workload it
  reduced GPU duration but not the 60 Hz total.

## UX decision

- Keep emulator **37.3.10** for the Dev measurement environment; it removes the bimodal
  33–34 ms regime observed with 37.2.12 and the backup remains available for rollback.
- Do **not** switch HWUI to Vulkan, lower the ENHANCED/glass tier, or remove motion on the
  basis of this E-gate study. The actual FULL tier draws and the visual/UX gate is unchanged.
- `virtio-gpu-pipe` is an emulator-experiment option only; it is not a product change and did
  not reduce the 60 Hz total in this workload.
- The owner's physical-TV T-gate remains the product acceptance; no emulator result is
  relabelled as TV evidence.

## Follow-ups

1. Re-run the 20 s probe with a focus-requested tagged media card, or use the release
   `TvNativePerformanceAcceptanceTest` per condition, to restore per-key input causality.
2. If app Vulkan is a real question, run a separate `debug.hwui.renderer=skiavk` arm and
   verify `dumpsys gfxinfo` shows a Vulkan pipeline; keep it out of the main matrix.
3. Repeat the 37.2.12 vs 37.3.10 comparison on a quiet host for a causal claim.
4. Run the owner T-gate on a physical Android TV with the exact accepted artifact.

## Cleanup

- AVD `config.ini` and `hardware-qemu.ini` restored to `hw.gltransport=pipe`; temporary
  backups removed. RAM/vCPU/GPU mode unchanged.
- Emulator stopped; no `qemu-system-aarch64`; no `caffeinate`; no Gradle/Kotlin daemons;
  `adb devices` empty.
- Temporary probe deleted; git worktree has no probe or AVD-config change. This evidence file
  is the only new repository content and is left uncommitted.
- Emulator backup retained at `~/android-sdk-backups/emulator-37.2.12-20261124`.
- Raw JSON and host snapshots are under
  `/private/var/folders/ks/flqyrh0j5wvf2t7v4s7lcnz0b5tfzv/T/opencode/gpu-study/`.
