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
| B4 | Mobile live channel composite identity | Implemented; mobile collision regression added |
| B5 | BFF authentication redirects and concurrent session renewal | Passed 78 BFF tests, including 8 authentication regressions |
| B6 | Completed-episode restart and explicit resume intent | Implemented on TV and phone; recovery preserves user pause |
| B7 | Search authentication expiry and recovery | Implemented; expired-session Android regression added |
| B8 | EPG ticking, foreground refresh and current-program window | Implemented; clock/foreground browser regressions passed |
| B9 | Native destinations and quarantined library review | Implemented; original checkpoints preserved; native/WebView return regression added |
| F1 | SourceList subscription preview, import, persistence and refresh | Implemented; overlap ownership, concurrent edits and native UI regressions added |
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
  and native system-session instrumentation; duplicate live IDs, explicit replay
  and subscription preview/import/refresh/stop. Both portrait and landscape player
  captures are mandatory. A zero adb exit or a skipped test cannot pass the receipt
  checker. TV lanes additionally run the real media controller and remote-driven
  subscription preview; capable providers run empty-pagination and native-library
  return flows in the production MainActivity WebView.
- Official unmodified LibreTV 2.21.1 and 2.21.2, each with pinned release checksums:
  authentication, rejected-source guards, controlled search/NDJSON, cross-client
  detail cache, SourceList discovery and TVBOX skipped-item reporting.
- Final production-signed installation/upgrade and physical hardware remain
  separate evidence from these debug emulator and static fixture contracts.

## Integration verification before the final candidate

- Frontend matrix: 148 passed across CSS540/DPR2, 720p, 1080p and 4K; subsequent
  subscription/library return checks passed 8 cases and all seven native settings
  destinations passed 4 viewport checks. Production build excludes fixtures.
- Local integrated JVM tests and Android instrumentation compilation passed before
  the final subscription ownership follow-up; the exact final candidate still
  requires its own Actions result.
- BFF: 78 passed. Repository release/script regressions: 189 passed. Official
  pinned LibreTV 2.21.1/2.21.2 matrix passed on intermediate published commits.
- Independent review found and fixed unchanged-refresh reordering, hidden-source
  promotion on stop, subscription/manual-write races, destructive URL normalization,
  invalid ports and misleading manual-source removal affordances. Targeted tests
  cover these paths rather than relying on successful import alone.
- Final source SHA, device results and release publication are recorded only after
  they actually complete; the implementation rows above are not release receipts.
