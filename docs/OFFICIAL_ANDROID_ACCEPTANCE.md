# Isolated official LibreTV Android acceptance

This gate is prepared, **not yet executed on Android**. The separate official positive-protocol run 41905547692 passed 27 assertions, but it does not substitute for this gate.

`tv-smoke.yml` includes `OfficialLibreTvPlaybackAcceptanceTest` on phone/API 30 and TV 1080p/API 33 only. The 4K/API 33 and compatibility/API 26 jobs remain offline with respect to this extra integration. The matrix otherwise retains existing navigation, focus and synthetic playback coverage.

## Boundaries

- Official LibreTV v2.21.1 standalone archive and official Node v22.23.4 are verified against committed SHA-256 pins and published manifests before execution. Upstream code remains separate, extracted temporarily, and unmodified.
- Artifacts are prepared before emulator allocation. `android_acceptance.py` owns the official server only during the emulator's QA command. It uses the existing scrubbed environment and `127.0.0.1` server lifecycle. The emulator reaches that owned host loopback server through `10.0.2.2` on a dynamically allocated port. No home backend, private source, Douban endpoint, proxy, DNS override, or source discovery is used.
- One immutable CinemaTV fixture at commit `74d104aa2b4e212163376bcbb1da04bef6670918` is the only source. It is an AppleCMS-shaped **static record, not a dynamic search, pagination or ID-filtering service**. Its exact episode is the Blender-hosted 240p Big Buck Bunny MP4. The fixture preserves attribution and CC BY 3.0 license information.
- The random synthetic password is in a mode-0600 host file inside a mode-0700 disposable directory. `run_android_test.py` transfers it via adb stdin to the debug app's private files directory after the ordinary UTP suite has completed and both exact compiled APKs have been reinstalled and their package/runner presence verified; it is never an instrumentation/Gradle argument. The test deletes the file immediately after reading it. The harness also removes it on exit. No credential files, cookies, upstream logs, or environment dumps are uploaded.
- The wrapper terminates the QA process group on timeout/interruption, then closes the server and temporary files. Existing workflow and emulator timeouts remain enforced. `SIGKILL`/runner loss cannot execute application cleanup; GitHub's disposable runner teardown is the final boundary.

## Android assertions

The actual `LibreTvRepository` checks required authentication and official version, logs in, searches through ordinary JSON and NDJSON flows, validates the exact source/ID, and resolves the exact licensed episode and attribution. It uses separate test preferences and an in-memory Room database, not saved user settings.

A neutral debug `NotoTestActivity` supplies a real `SurfaceView` without starting the application ViewModel or recommendations. The production `PlaybackFactory` (uncached mode) must initialize a decoder, render at least eight video buffers, advance its playback clock, and continue rendering additional buffers. Pause must stop clock advancement. A checkpoint saved through the repository must reload through a fresh library instance using the same Room database; playback then resumes from that checkpoint and renders new frames. The player is released and activity closed even on assertion failure.

This verifies adapter-to-player integration and database checkpoint reload, not app player-page remote interactions, process-death disk durability, whole-film decode, all codecs, a live dynamic provider, or home-service acceptance. Existing UI tests cover their own separate scopes.

## Evidence

- `qa/official-artifact-verification.json`: artifact verification only.
- `qa/official-lifecycle.json`: bounded process/QA exit outcome only, never sufficient by itself.
- `qa/official-android-instrumentation.txt` and `qa/official-android-result.json`: separate post-UTP invocation of the official class on the already-installed APKs. The parser requires exactly one matching test start/completion, successful JUnit summary and final instrumentation code, and rejects crashes/abort/failure. Require this gate on both selected targets. Ordinary suite XML/HTML does not contain this separately run class.
- Final instrumentation result metrics carry numeric rendered-frame, checkpoint, and resumed-position evidence after all assertions pass. The bounded `qa/live-test.log` may also include the success line; neither contains passwords or session cookies.

Offline safety tests: `python3 -m unittest discover -s scripts/tests -p test_official_android_lifecycle.py -v`. These use mocks and do not claim network or emulator acceptance.
