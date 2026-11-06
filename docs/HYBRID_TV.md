# Hybrid TV implementation

CinemaTV's approved TV architecture is **local React/CSS presentation + Kotlin
application services + native Media3 playback**. The project keeps API26 installation
support, the existing `dev.cinematv` package, Room data, sources and signing identity.
The phone UI and native compatibility screens remain available.

The implementation branch is `feature/hybrid-tv-liquid-glass-complete`, continuing
the preserved commits after the original PR #36 and branch were closed/deleted.
It is based on
`dev@553d37076ba0f9989ad5d24942f82638cf722478`. It preserves that branch's default-off
personal-account experiment. Its source must not be merged into the stable branch
until that separate experiment satisfies the existing source-isolation policy.

## Responsibilities

| Layer | Responsibility |
|---|---|
| `frontend/tv-web` | Cinematic full-screen artwork, floating glass navigation, home/discovery rails, movie/series catalogues, search, details, episodes, library, live guide and presentation settings |
| `app/.../hybrid` | Local-origin WebView, bounded message protocol, presentation DTOs, native actions, artwork transport, lifecycle and recovery |
| Existing Kotlin services | LibreTV/BFF transport, source identity, recommendations, Room library and episode progress |
| Native panels | TV text input, service/account configuration, update/installer permissions and advanced existing tools |
| Existing `TvPlayerPage` / Media3 | Decode, native video surface, playback controls, tracks, subtitles, source changes and progress |

The APK contains static Web resources under `assets/tv-web/`; it does not run a Node
server or download a remote application UI. Browser fixtures are development-only,
are synthetic, and must be absent from the production bundle. No public streams or
credentials are included.

## Compatibility and interaction

The native admission policy checks OS, actual WebView provider and required bridge
support. Installation on API26 is retained; hybrid admission requires API29+ and
Chromium111+ plus the supported native message feature. Unsupported or failed
WebViews recover to the existing native TV UI. Android version alone cannot certify
the installed WebView. The provider and presentation actually selected are part of
device evidence.

The Web UI uses fixed focus geometry with animated inner artwork, bounded visible
pages, stable content keys, logical navigation and per-route focus/scroll bookmarks.
Native navigation remains authoritative. Playback enters the existing Media3 page
and returning restores browsing state. Reduced-motion and lower-cost glass options
preserve a visible focus indicator. The glass design applies to the UI background;
video surfaces are not sampled by webpage effects.

Requests contain opaque item/episode identifiers. Raw playback URLs, provider
credentials, account sessions and the complete native ViewModel are not Web DTOs.
The bridge enforces exact origin/main-frame admission, protocol and action
allowlists, payload/concurrency limits and explicit failures. Timed-out writes are
not automatically retried. Artwork is resolved by native code, not arbitrary
JavaScript fetches.

### Browse, guide and native tools

Movie, series, discovery and search pages keep the remote page action available
when local filters hide every currently loaded item. Loading, retry, exhausted and
snapshot-restart states are explicit. The remote action retains its focus key while
its state changes, and the server cursor is not discarded after a retryable error.
Search rating filters preserve their TMDB or Douban provenance; Top 250 remains a
separate licensed Douban restriction. Live channel groups and recommendation reasons
use bounded presentation fields rather than provider responses.

The live guide recalculates time every 30 seconds while the screen is visible and
immediately on foreground return. Native code filters and sorts the complete cached
guide before sending at most 48 current/upcoming programs; each visible tick also
checks the native 15-minute provider refresh window. Background screens do not poll.
Program keys survive clock updates, and the Web UI recovers the next guide item or
channel when the focused program ends.

Native settings destinations are named explicitly: connection, sources, display,
updates, developer options, recommendations, retained-progress review and source-list
subscriptions. The legacy `advanced` bridge destination maps to developer options.
Source-list URLs, preview/import calls and credentials stay in the native screen.
Subscriptions show preview/diff information before import or refresh; unsubscribing
retains already imported sources. Returning from these panels restores the Web route
and its focus bookmark.

The library's management action opens a native review of unresolved episode progress.
It shows the preserved source, original playlist position, measured time and reason
the record cannot be attached safely. Opening the current playlist does not transfer
or delete the old checkpoint. Details distinguish resume from restart, including an
explicit restart action for incomplete episodes; completed episodes restart from zero.

Official API references:
[local WebView content](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content),
[native bridge risks](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges),
[WebView lifecycle](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview),
[TV input requirements](https://developer.android.com/docs/quality-guidelines/tv-app-quality),
[Media3 video surfaces](https://developer.android.com/media/media3/ui/surface).

## Local frontend development

CI uses Node **22.23.4** and the committed npm lockfile. Run from the repository root:

```sh
npm --prefix frontend/tv-web ci --ignore-scripts
npm --prefix frontend/tv-web run dev
```

The development adapter permits browser work without an Android build or real
account. Its rendered content is visibly marked as synthetic development content.
Production uses only the native transport:

```sh
npm --prefix frontend/tv-web run build
npm --prefix frontend/tv-web run verify
npm --prefix frontend/tv-web exec -- playwright install chromium
npm --prefix frontend/tv-web test
```

Vite explicitly targets Chromium111. Production verification rejects development
fixtures, source maps and external scripts and enforces the initial bundle budget
(1MiB JavaScript / 2MiB total assets). These are build budgets, not measured device
memory or frame-rate claims. In the native host, the Web UI loads the existing Latin
Noto regular/bold TTF assets without packaging another copy. Web CJK text uses the
device's locale-aware system fallback; the existing native Compose screens continue
to use the packaged regional Noto CJK collection. Naming a CSS font family alone
does not expose a Compose font to WebView, and browser screenshots do not certify
OEM CJK glyph selection.

## Exact-source CI evidence

Two workflows provide distinct evidence without recompiling beside an emulator:

| Workflow | Evidence |
|---|---|
| `Hybrid TV Web` | Locked install, type/build validation, production bundle validation, keyboard/remote-style browser interactions at the frontend's configured viewports, screenshots/traces and source/asset receipt |
| `Hybrid TV Android` build | Exact PR-head checkout; release tests/lint; unsigned Dev candidate; debug + instrumentation APKs; byte-for-byte equality between packaged Web assets and the built frontend |
| Android TV API36 | Actual `android-tv` x86_64 image and `tv_1080p` profile; `MainActivity` hybrid admission, real WebView, D-pad/Back and native-player handoff/rebuild |
| Android TV API26 | Actual `android-tv` x86 image and `tv_1080p` profile; expected native compatibility fallback |

The TV packages are published through Google's
[official SDK image repository](https://dl.google.com/android/repository/sys-img/android-tv/sys-img2-1.xml).
The workflow records the runtime system fingerprint and provider; it does not silently
substitute a phone image or make unsupported WebView admission a passing skip.

`scripts/hybrid/run-device.sh` installs the prebuilt APKs, checks their hashes against
the exact checkout and runs all five cases in `dev.cinematv.HybridHostUiTest`. The
harness rejects missing, failed, assumed or skipped test cases even when `adb` itself
exits zero. Each case must export its screenshot and matching production-host,
presentation-mode and API metadata; their hashes are retained in the device receipt.
Test output, provider information and a final memory snapshot are also exported. The final
memory snapshot is diagnostic only; it is not an A/B resource benchmark and does not
measure the entire browser renderer lifetime.

The existing Android/backend/Compose regression workflows remain applicable. Passing
the new browser suite does not replace Android WebView execution, and a synthetic
emulator result does not establish real TV input latency, GPU cost, codec/HDR coverage
or four-hour playback stability.

Run the artifact-gate tests without an Android SDK:

```sh
python3 -m unittest discover -s scripts/hybrid -p 'test_*.py' -v
bash -n scripts/hybrid/build-dev-candidate.sh scripts/hybrid/run-device.sh scripts/hybrid/wait-device.sh
```

## Dev candidate and publication

`scripts/hybrid/release.json` reserves **0.9.0-dev.1 / versionCode13** after the existing
public Dev12. A reservation is not a published release. Only the fixed existing
production signing identity may finish this candidate; neither workflow handles a
private signing key or uploads signing material.

On a clean checkout with Java17, Gradle8.13, Android platform/build-tools36, Node22.23.4
and installed locked frontend dependencies:

```sh
bash scripts/hybrid/build-dev-candidate.sh /tmp/cinematv-hybrid-dev13-candidate
```

The output directory must not already exist. The script refuses signing credentials,
dirty or ignored source inputs, a checkout differing from the expected CI head,
invalid packaged identity or assets differing from the built frontend. It produces:

- `CinemaTV-0.9.0-dev.1-code13-dev-UNSIGNED.apk` — **not installable**.
- `unsigned-receipt.json` — source/tree, frontend/lockfile, Web asset hashes, APK hash,
  package/version/channel and required public certificate fingerprint.
- Decoded `AndroidManifest.xml`, `SHA256SUMS.txt` and `NOT-FOR-INSTALLATION.txt`.

The relevant CI artifact is `UNSIGNED-hybrid-dev-candidate-NOT-FOR-INSTALLATION`.
Before external signing, require successful exact-head browser, Android, applicable
existing regression and both new TV device gates; retain their receipts together.

Final steps follow [the existing update-channel procedure](UPDATE_CHANNELS.md):
verify the unsigned receipt, sign outside GitHub with the existing private key,
verify the final certificate/package/version, run applicable installation/forward
upgrade acceptance on those final bytes, then publish the immutable Dev prerelease.
Prepare and sign a Dev update manifest only after its separate end-to-end acceptance.
Do not change the key, reuse a versionCode, publish the unsigned/debug artifact as a
normal upgrade, enable an unsigned fallback or revive GitHub-based private signing.

The checked-in workflows are not evidence of a successful run. Publication status,
exact run links and final signed-APK acceptance must be recorded in the release notes
after observing them. Physical-device performance remains an explicit separate gate.
