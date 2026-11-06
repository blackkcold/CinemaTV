# Hybrid acceptance hardening · branch-only implementation

Base: `dev@7d3ec0055cf8cf48edb18b8ea91763114633123a`.
Working branch: `fix/hybrid-acceptance-serial-qa`.
The user's 2026-11-06 instruction supersedes the historical branch name in AGENTS.md: save incremental commits on this new branch; do not merge or publish.

## Scope

Preserve completed B1–B9/F1–F3 application repairs. Harden exact-source evidence, current hybrid/native test contracts, production browser visual comparisons, serial GitHub device scheduling, 4K diagnostics and an external-signed artifact handoff. API26 installation, native Media3 and the independent phone presentation remain unchanged.

Source/debug acceptance is not release approval. Private signing keys stay outside GitHub and Actions. No artifact or workflow in this change may publish a release, move dev/main or silently promote visual baselines.

## Incremental checkpoints

1. `0bb9fe6`: read-only source provenance workflow and source archive, without credentials/build outputs.
2. Exact-source build/lane manifests: distinct preview-debug and current dev-debug identities; fail-closed missing/duplicate/stale/hash/configuration checks; required device order and interval non-overlap.

## Evidence policy

Each acceptance run records source commit/tree, APK/test APK hashes, packaged Web assets, environment, exact case identifiers, per-lane scope and raw evidence digests. Duplicate or overlapping case counts are not presented as unique coverage. Later screenshots or status changes cannot retroactively turn a failed historical run into a passed run.

Legacy native TV component captures, actual TV hybrid captures, native fallback, phone UI and browser production captures have separate meanings. API29 follows the real WebView capability policy, not API number alone.

## References

- GitHub concurrency: https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency
- Playwright visual comparisons: https://playwright.dev/docs/test-snapshots
- Android emulator acceleration: https://developer.android.com/studio/run/emulator-acceleration

Implementation and actual verification results are recorded separately. A checked-in test or reserved release number is not a passed test or published APK.
