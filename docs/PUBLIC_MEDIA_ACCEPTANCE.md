# Isolated public-media acceptance

## Scope and source

The temporary loopback-only server is a clean-room **PeerTube-to-LibreTV-compatible test adapter**, not an actual LibreTV installation or an Apple CMS server. It does not alter LibreTV SSRF protections. No private server or user credentials were used.

The source is the [official Blender PeerTube video](https://video.blender.org/videos/watch/6402b77c-b61f-4a06-96ca-c8420a2becf4). Search and detail were fetched from its real public API. The adapter requires the exact video UUID, official account/channel, Attribution license and approved media URL; it never substitutes synthetic results on a source failure.

The actual Android stream was the **426×240 H.264/AAC MP4**, not 4K video. “4K” appears in the upstream film title but does not describe the rendition tested. Measured duration was 634,667 ms. The film's closing credits establish CC BY 3.0 for the Sunflower rendition; the test bundle retains the attributed primary-source credit frames.

Attribution: Big Buck Bunny © 2008 Blender Foundation / www.bigbuckbunny.org, CC BY 3.0. Sunflower stereoscopic / 4K / 60FPS / HDRI version: Janus Bager Kristensen and the credited Sunflower contributors. Retain complete ending credits. [License](https://creativecommons.org/licenses/by/3.0/), [original film information](https://peach.blender.org/about/).

## Verified results on 2026-11-04 UTC

Both tests used the unchanged production source at `449f358e1225de68ef51ab14ce8efc04035c6fae`. Their dedicated branch added only isolated test/harness files. They are **separate successful target results**, not a claim that one combined run passed.

- **TV layout, 1920×1080, API 33:** [run 41853182092](https://github.com/blackkcold/CinemaTV/actions/runs/41853182092), test commit `a06ebbce831ddf40c2a075152da5e330d4112c60`. Its TV target passed all seven checks. Rendered video buffers advanced from 62 to 153 while playback advanced from 2,000 to 5,031 ms. The saved episode-0 checkpoint was 12,027 ms and resumed playback reached 14,237 ms. The later phone target in this same run failed at upstream provenance retrieval, so the overall run is correctly marked failed.
- **Phone portrait layout, API 33:** [run 41859160272](https://github.com/blackkcold/CinemaTV/actions/runs/41859160272), test commit `a73e541a21aabe7d4e914d567be5657e373ba1d0`. The phone-only run passed one instrumentation test and all six checks. Rendered video buffers advanced from 60 to 148 while playback advanced from 2,084 to 5,134 ms. The saved episode-0 checkpoint was 16,325 ms and resumed playback reached 18,670 ms. All six upstream requests returned HTTP 200. Its manifest deliberately records `fullPairPassed=false` because TV was not repeated in that run.

Checks cover production UI search/detail with exact provider identity, actual Media3 decoded frames and advancing time, measured duration, user-pause preservation across lifecycle changes, active playback stop/resume, Back navigation with player release and Room checkpoint persistence, and subsequent episode resume. TV additionally verifies Back closes the track panel before leaving playback. Catalog-only search matches cannot satisfy the network-source assertion.

The phone run also passed 28 host harness tests, 12 offline adapter tests, eight real-source preflight checks, and Android application/instrumentation compilation. The preflight decoded five seconds / 150 frames with FFmpeg independently of Android. Synthetic contract fixtures are not counted as real playback evidence.

## Infrastructure findings and remaining limits

Earlier API 33 runs lost the entire emulator and returned empty JUnit failures, so they are failures, not passes. The successful phone run used the same pinned Emulator 36.4.10 (build 15004761), API, resolution and assertions, but changed the software renderer to supported Lavapipe and added persistent phase/host diagnostics. This does not prove the root cause or guarantee a full TV matrix will pass.

Software rendering dropped frames during the successful phone test. These results establish the exercised playback behavior, **not smoothness, sustained performance, hardware decode, or device certification**. Separate phone/1080p/4K UI acceptance remains required for the release candidate.

This single real film exercises episode index 0 only. It does not validate real series switching, Android HLS/DASH/DRM/HDR playback, live TV, physical remotes/devices, a deployed LibreTV version, or live Douban data. Existing synthetic/multi-episode contract tests are separate coverage. Connecting to a private deployment remains a separate explicitly authorized step.

The reproducible harness and provenance are on the [dedicated test branch](https://github.com/blackkcold/CinemaTV/tree/test/public-source-e2e/scripts/public-media). CI artifacts contain screenshots with attribution, JUnit XML, playback samples, target-selection manifests, source status, stage logs and APK hashes. Artifacts expire according to their configured retention; the commits and this results record remain in Git.
