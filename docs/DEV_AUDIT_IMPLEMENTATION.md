# Dev audit implementation and recovery record

Authorized scope: all fixes and feature packages from the 2026-11-06 review of
`dev` at `0d4d113e65ac9cf4498133a0b32f7f76a357362e`, followed by the normal Dev release.
Changes are committed incrementally on `feature/hybrid-tv-liquid-glass-complete`
and integrated into `dev`; `main` is outside this change.

| ID | Scope | State |
|---|---|---|
| B1 | Connection generation, request cancellation, origin-scoped authentication | In progress |
| B2 | LibreTV detail cache and local source identity | In progress |
| B3 | Empty filtered results retain remote pagination | In progress |
| B4 | Mobile live channel composite identity | In progress |
| B5 | BFF authentication redirects and concurrent session renewal | Implemented; focused regression pending |
| B6 | Completed-episode restart and explicit resume intent | In progress |
| B7 | Search authentication expiry and recovery | In progress |
| B8 | EPG ticking, foreground refresh and current-program window | In progress |
| B9 | Native destinations and quarantined library review | In progress |
| F1 | SourceList subscription preview, import, persistence and refresh | In progress |
| F2 | Android audio focus, noisy-device pause and MediaSession | In progress |
| F3 | Hybrid live groups, rating provenance filters and recommendation reasons | In progress |
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
