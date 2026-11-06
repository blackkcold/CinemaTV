# Dev audit implementation and recovery record

Authorized scope: all fixes and feature packages from the 2026-11-06 review of
`dev` at `0d4d113e65ac9cf4498133a0b32f7f76a357362e`, followed by the normal Dev release.
Changes are committed incrementally on `feature/hybrid-tv-liquid-glass-complete`
and integrated into `dev`; `main` is outside this change.

| ID | Scope | State |
|---|---|---|
| B1 | Connection generation, request cancellation, origin-scoped authentication | Implemented; Android race regressions added |
| B2 | LibreTV detail cache and local source identity | Implemented; JVM policy tests passed; Android fixtures added |
| B3 | Empty filtered results retain remote pagination | Implemented; focused browser regressions passed |
| B4 | Mobile live channel composite identity | In progress |
| B5 | BFF authentication redirects and concurrent session renewal | Passed 78 BFF tests, including 8 authentication regressions |
| B6 | Completed-episode restart and explicit resume intent | Policy/TV implemented; mobile integration in progress |
| B7 | Search authentication expiry and recovery | Implemented; expired-session Android regression added |
| B8 | EPG ticking, foreground refresh and current-program window | Implemented; clock/foreground browser regressions passed |
| B9 | Native destinations and quarantined library review | In progress |
| F1 | SourceList subscription preview, import, persistence and refresh | In progress |
| F2 | Android audio focus, noisy-device pause and MediaSession | Implemented; JVM policy tests passed; device regression added |
| F3 | Hybrid live groups, rating provenance filters and recommendation reasons | Implemented; focused browser regressions passed |
| QA | Exact candidate build/lint/tests, TV26/29/current/4K and phone acceptance | Pending |
| Release | Fixed-identity signed Dev APK and update feed | Pending |

## Release identity and evidence

The reserved candidate remains `0.9.0-dev.1`, global versionCode `13`, after public
Dev code `12`. Recheck public releases before publication. Keep the package
`dev.cinematv` and certificate recorded in `release/signing-identity.json`.
Signing takes place outside GitHub/Actions. A build artifact is not an installable
release until the original signing identity and final-byte acceptance are verified.

Record final commit, Actions runs, APK hash/certificate, install/upgrade evidence
and publication URL here as they become available. Do not mark physical hardware
or long-play checks passed without actual evidence.

## Candidate regression matrix

- Build one exact-source unsigned release plus debug/instrumentation APKs; all
  device lanes consume the same checksummed debug APKs without rebuilding.
- Actual Android TV system images: API26 fallback, API29 provider-policy boundary,
  API36 hybrid at 1080p and 4K. The API29 lane records the installed WebView's
  capability and verifies its appropriate hybrid/fallback route; it does not force
  an unsupported old provider into the hybrid interface.
- API35 phone: independent navigation/player plus real repository HTTP fixtures
  and native system-session instrumentation. A zero adb exit or a skipped test
  cannot pass the receipt checker.
- Official unmodified LibreTV 2.21.1 and 2.21.2, each with pinned release checksums:
  authentication, rejected-source guards, controlled search/NDJSON, cross-client
  detail cache, SourceList discovery and TVBOX skipped-item reporting.
- Final production-signed installation/upgrade and physical hardware remain
  separate evidence from these debug emulator and static fixture contracts.
