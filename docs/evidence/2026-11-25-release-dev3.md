# Dev release 0.9.0-dev.3 — automated handoff (2026-11-25)

**Tag/source:** `v0.9.0-dev.3` → `ed3a18afb3342fba42c45320108bd18fd8faac10`
(merge of PR [#58](https://github.com/blackkcold/CinemaTV/pull/58)).
Version `0.9.0-dev.3`, versionCode `15`, package `dev.cinematv`, minSdk 26.
The tag is pushed. This document records automated work only; it is not a signed-artifact
acceptance or a T-gate.

## Automated gates

| Gate | Result |
|---|---|
| Required PR checks | `apk` (13m19s), `no-node` (2m55s), dashboards phone/tv1080/compat26, settings-ui phone/TV, synthetic phone33/compat26, scope, safety dev/stable, unsigned-dev-account — passed. |
| Host build/test/lint | `:app:testDebugUnitTest`, `:backdrop:testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `compileGlassBenchmarkKotlin`, `lintDebug` — passed. |
| Backend/Python | `tv-bff` 78/78; perf 12, native analyzer 3, glass 39, QA/device/unsigned 39. |
| Glass E-gate | 9/9 valid `glass-controls`; FULL/BLUR/BASIC all collected; see `2026-11-25-g6-glass-baseline.md`. |
| Focus/playback | Focus acceptance FULL, 5/6 paired, analyzer valid; `NativePlaybackSessionTest` with 3 s progression passed; 10-minute real-VOD E-gate +600 s / 14,401 frames / 0 dropped. |
| G6.4 emulator substitute | 30-min soak rendered 21,082 frames with stable memory; 20/20 route/playback cycles; positional loop assertion partial; see `2026-11-25-g6.4-emulator.md`. |

## Representative device lanes

- `tv36` (API36): passed (6m48s).
- `compat26` (API26, 1920x1080): passed (11m7s).
- `tvNative` (API33 Google TV): **failed** — `MainActivityPathSmokeTest.firstRunSettingsBackReturnsToHomeThenHomeBackFinishes` timed out at the 8 s final finish check, and the results audit reported `not-tv` for the image identity. This lane is not accepted as production-TV evidence.
- `tv31`, `tv1080`, `tv4k` full device lanes and the full matrix were not run.
- Physical G3.4 is owner-waived; physical G6.4 T-gate remains owner-run.

## Unsigned candidate and signing

The local unsigned Dev candidate was built from the release branch head `b8bc75c`
(app code identical to the merge):
`CinemaTV-0.9.0-dev.3-code15-dev-UNSIGNED.apk`, 44,197,113 bytes, SHA-256
`ee4284aefd1fdbb17e2a33a6b1736107fb90fc610f4d79cc07a130e07856bb4f`.
The merge/tag tree differs only in workflow/audit files; the external signer should
rebuild from `ed3a18a` and use that source receipt.

**No signed APK or GitHub Release was created by this session.** Production signing is
performed only on the approved non-GitHub machine; GitHub Actions must not receive the
key. The external signer must:

1. Build the unsigned candidate from `ed3a18a` and verify the exact source/tree/hash.
2. Sign with the fixed identity whose certificate SHA-256 is
   `0798f1686a28ae4a386a376efcabba3a4c4bb547de760210d944a58455092e8c`.
3. Verify v2/v3 signatures, 16 KiB ZIP alignment, package/version/channel/non-debuggable
   identity and all original ZIP entries.
4. Create the GitHub prerelease `v0.9.0-dev.3` with the signed APK, certificate,
   provenance, acceptance and SHA256SUMS. Do not publish the unsigned candidate or a
   debug-signed APK as a Dev upgrade.
5. Update the release acceptance with the signed APK SHA-256 and the known limits
   (tvNative CI failure, missing tv31/tv1080/tv4k, no physical T-gate, no full golden
   review).

## Known limits

- No physical TV acceptance, thermal run, 4-hour playback, HDR/codec breadth or
  installer/update-feed acceptance.
- `tvNative` CI lane failed its production-entry smoke and TV identity audit.
- G6.3 is a representative subset, not the full device matrix; G6.5 goldens and real
  compressed artwork remain.
