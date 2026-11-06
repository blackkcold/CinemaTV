# Visual evidence families

These families must never substitute for each other:

| Family | Entry point | Baseline |
| --- | --- | --- |
| hybrid-browser | Built `frontend/tv-web/dist`; test-process-only RPC fixture | `hybrid-browser/<viewport>/` |
| hybrid-android | Real Android TV MainActivity / packaged WebView | `hybrid-android/<API-provider-size-density>/` |
| native-components | Current native tools/player and retained legacy components | Existing `tv1080/`, `tv4k/` manifests, with original provenance |
| native-fallback | Capability policy on API26/unsupported provider | Separate fallback evidence; not modern-TV acceptance |
| phone-native | Independent phone UI | Separate phone device evidence |

Existing `tv1080` and `tv4k` directories are retained as native-component baselines rather than renamed without provenance. They are not the modern hybrid launcher baseline. Historical REVIEW.md and its exact masks/thresholds remain unchanged.

Production browser tests load `dist` with `vite preview`; the original development-server suite remains separate. Test fixture code is compiled by the Playwright process and injected before navigation, never imported by the application build. Font responses use the existing checksum-pinned Latin assets. Browser/device API, provider version, raster size, density/DPR, font lock, material and motion state must match the corresponding baseline environment.

Capture mode writes `candidate-only-not-approved` evidence. It never silently creates or accepts a baseline. Normal comparison requires a reviewed sidecar, verified image hash and matching environment before pixel comparison. Missing baseline, environment mismatch and image difference are separate failures. Capture output is not a passed visual gate.

4K captures stay at their original raster size. Browser comparison runs on those full-resolution pixels, not a scaled 1080p substitute. The existing native common-density comparison remains in addition to full-size evidence checks. Physical TV performance, HDR and decoder certification are outside emulator screenshot evidence.
