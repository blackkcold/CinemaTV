# G0 governance baseline — 2026-11-09

Plan: `docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md`
Branch: `dev-compose`
Baseline tag: `baseline/native-tv-8787e1e`
Tested source: `6bc1c7f5ef983d66cc6d72bbdb2218b156823756`, tree `73e3764ce9ec8266d6946577c91082ef930ef652`
Merged as: `8787e1e848df49b3cb9ed84e53884d85126b9c55`, identical tree (rebase-merge rewrote only the commit objects)

This file records measured facts only. Items marked `BLOCKED` were not run and must not
be quoted as results.

## G0.1 Governance changes accepted

| File | Change |
|---|---|
| `README.md` | TV described as native Compose; the Hybrid section replaced by the native architecture section; build notes say the Web build wiring still exists until G4 |
| `docs/ARCHITECTURE.md` | Native boundaries, layer table with `TvRootApp` as the only route owner, glass evidence model, one Back ladder |
| `AGENTS.md` | `dev-compose` is the only mainline and the Dev-channel source; `dev` is a transitional mirror; `main` is Stable; rule 17 makes UX a gate |
| `docs/HYBRID_TV.md` | FROZEN banner; the old "approved architecture" wording corrected |
| `docs/NATIVE_TV_MIGRATION.md` | Historical-input banner; the conflicting "do not merge into dev" rule removed; jank definition corrected |
| `docs/DEV_AUDIT_IMPLEMENTATION.md` | Historical-record banner |
| `docs/releases/v0.9.0-dev.1.md` | Historical-candidate pointer |
| `docs/RELEASE_CHECKLIST.md` | Golden reference is the current native TV; Macrobenchmark replaced by native-tv-perf with drawn tier and `sourceReady` |
| `docs/UPDATE_CHANNELS.md` | `tv1080`/`tv4k` explicitly are not television lanes |
| `docs/IMPLEMENTATION_GAPS.md` | Matrix described as phone/API26 component lanes plus `tvNative` |
| `docs/LIBRETV_PROTOCOL.md` | Subscription settings described natively; no WebView bridge DTO wording |
| `docs/DOUBAN_PUBLIC_ACCESS.md` | "hybrid TV filter row" and "hybrid gateway" corrected to native |
| `docs/LOCAL_BUILD_DEFAULTS.md` | Hybrid candidate scripts marked frozen and scheduled to move to `scripts/updates/` |
| `scripts/tests/test_device_qa_contracts.py` | Two stale anchors updated to the native `search-field-shell` focus and the restored channel key; the verified sequence is unchanged |

The test change fixes a failure that already existed at `5a68260e`: the contract test
expected the inner text field to hold D-pad focus and the first live channel to be
restored, while `TvField` keeps focus on the shell until OK and the LIVE lane restores
the previously focused channel key.

## G0.2 Exact-source CI evidence

| Workflow | Run | Result | Jobs |
|---|---|---|---|
| Android TV APK | [42581567666](https://github.com/blackkcold/CinemaTV/actions/runs/42581567666) | success | `apk` 9m38s |
| Update channel safety | [42581567424](https://github.com/blackkcold/CinemaTV/actions/runs/42581567424) | success | `scope` 5s, `safety (dev)` 6m57s, `safety (stable)` 56s, `settings-ui` phone 2m5s and tv 2m0s |

The `apk` job completed every step: device-QA harness validation (191 Python contract
tests), build/lint/test, exact Noto font bytes, release acceptance test APK compilation,
an assertion that Android JUnit tests actually executed, APK archive and signature
structure, immutable build provenance, and artifact upload.

Skipped by path/marker filters, not failed: `preview`, `promote-preview`, `ui-qa`,
`installer-fixtures`, `installer-upgrades`, `repository-acceptance`,
`unsigned-dev-candidate`, `unsigned-stable-candidate`.

## G0.3 Host toolchain (measured 2026-11-09)

| Item | Finding |
|---|---|
| Host | macOS, arm64 |
| JDK | Temurin 21.0.12.1 |
| Android SDK | `/Users/11169285/android-sdk` via `local.properties` |
| Platforms / build-tools | android-34, 35, 36, 37.0 / 35.0.0, 36.0.0, 37.0.0 |
| Emulator / adb | present under the SDK |
| AVDs | `cinematv-tv` (Google TV API34, arm64, 1920x1080 @320, 2048 MB), `livis34`, `livisdiy` |
| Gradle | 8.13 unpacked at `$TMPDIR/opencode/gradle2/gradle-8.13`; not on `PATH`; the repository has no `gradlew` |
| GNU `timeout` | absent; harness steps that require it run in CI only |

## Not covered (BLOCKED)

- `tv-smoke.yml` device lanes: not triggered, because this change touched no UI.
- E-gate frame sampling and T-gate product acceptance: not run.
- Production signing, fresh install, channel upgrade and data preservation: not run.
- Physical Android TV: owner-run and not scheduled.

This evidence supports **Build Verified** only. It does not support Device QA Verified
or Release Certified.

## Follow-ups

- Branch protection and required checks are not configured on `dev-compose` yet.
- PR #40 is still open; its head is already an ancestor of the mainline.
- The performance protocol still cannot prove `input -> semantic state -> drawn frame`.
- Six of the ten TV routes have no page-level `testTag`; five have no loading or error state.
