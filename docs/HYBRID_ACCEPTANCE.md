# Hybrid acceptance hardening · branch-only implementation

Base: `dev@7d3ec0055cf8cf48edb18b8ea91763114633123a`.
Working development line: **`dev-hybird`** (the user's exact spelling).
Recovered checkpoint: `c176b0303616c4f46786315d99c5d74dbb1583b9` from `fix/hybrid-acceptance-serial-qa`; that branch remains untouched.
Save incremental commits on `dev-hybird`; do not merge, sign or publish. The new development line descends from the unchanged Dev base through the recovered acceptance commits.

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

## Development-line validation

Every push runs host safety and provenance checks. A green host-only workflow is **not** a complete acceptance result. Commit markers select deliberate resource-intensive runs:

- `[visual-capture]`: production-browser candidate captures; no automatic approval.
- `[android-visual-capture]`: build exact APK configurations, then actual-TV 1080p and 4K captures sequentially; not the full device matrix.
- `[acceptance-capture]`: full ordered matrix with visual review pending.
- `[acceptance-run]`: full strict matrix against reviewed, environment-matched baselines.

The aggregate `acceptance-manifest.json`, not the workflow's green badge, determines the scope accepted. All emulator leaf jobs share `cinematv-emulator-exclusive` with `queue: max` and no cancellation. The final full chain ends with native 4K followed by actual-TV hybrid 4K. No production-private signing material is required or requested for this branch.
