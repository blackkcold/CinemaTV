# Promote already-tested preview artifacts

This optimization does not reuse a result for changed source. Normal APK compilation,
JVM tests, lint and backend tests still run on every main push. Only the redundant
four-emulator acceptance run is avoided when the entire tested Git tree is identical.

## Sequence

1. Include the promotion helper, workflow, per-job receipt steps, release notes and
   intended prerelease version in the integration branch **before** running the full
   `[ui-qa]` PR matrix. A diagnostic `[ui-tv4k]` run is not eligible.
2. Require successful `Android TV APK` run, `apk`, and every `ui-qa / UI /` job:
   phone, tv1080, tv4k and compat26. The source must be in blackkcold/CinemaTV, not a
   fork, and its first attempt must complete successfully within the last 14 days.
3. Merge without source changes. In the merge commit message, use exactly one marker:
   `[promote-preview run=123456789 version=0.6.0-preview]` (replace the run ID).
   Alternatively, an authorized empty main commit with this marker preserves the
   tested tree. Do not combine it with `[preview-release]` or `[ui-qa]`.
4. The main push first runs the normal `apk` job. Promotion then verifies source run
   and job metadata, all receipts, artifacts, executed JUnit classes/methods, and
   phone/TV official LibreTV lifecycle, artifact verification and raw instrumentation
   success. The original APK and full device evidence are published as a prerelease.

The workflow does not authorize pushing/merging or publishing outside the user's
requested workflow. No dispatch endpoint, local credentials or new persistent access
is required; the promotion job uses its normal bounded repository GITHUB_TOKEN.

## Fail-closed provenance

Each receipt records actual `git rev-parse HEAD`, its tree, the event's source head,
repository, run and attempt, version, selected test classes and APK digests. GitHub's
run head must match the source head, while GitHub's commit API must independently
confirm the checkout tree and its relationship to the source: exact head for push/dispatch, or a two-parent PR merge with that source head as second parent. For PRs the tested checkout may be a synthetic merge SHA,
not the branch head. Main's complete tree must match that tested checkout, including
these workflow/helper files; merge metadata can differ. The main ref is checked
before verification and immediately before publication. A subsequent main push
cancels an in-flight workflow through existing concurrency policy.

Artifact ZIP bytes must match GitHub's SHA-256 digest and exact byte count. Archives
are bounded and reject traversal, symlinks and duplicate paths. Expired, missing,
partial, failed, skipped, rerun-attempt, foreign-repository, stale and mismatched
source evidence is rejected. Existing tags (including prefix collisions) and releases
are rejected; the helper never overwrites them. Immediately before publication the helper atomically creates the tag at the verified main SHA, confirms its target, then uses `gh release create --verify-tag`. A failure after tag creation leaves the tag in place for human review; retries never delete or repoint it. API errors are failures, not evidence
that a tag/release is absent.

The promoted APK's exact SHA-256 must match the build receipt. All four device
receipts must also show identical **unsigned ZIP payloads** to that APK. Separate
runners' debug keys can differ, so JAR signature entries and APK signing blocks are
excluded only from the payload comparison. The exact original signed APK is retained
and its own signature was verified by the successful build job. If toolchain output
is otherwise non-reproducible, promotion stops; do not remove this check to force a
release. Evidence does not claim that every runner had the same debug signature.

## Deliberate limitations

- Old runs without receipts cannot be promoted. New helper/workflow changes require
  a newly tested full tree; this is not a retroactive shortcut around acceptance.
- Reruns are initially unsupported to prevent mixing attempts. Investigate and review
  any future rerun design instead of relabeling old receipts.
- More than 100 jobs or artifacts is rejected rather than accepting partial API pages.
- The helper validates the published workflow's evidence; it is not a defense against
  repository administrators deliberately modifying both tests and verifier. Review
  the tested workflow changes with the same care as release code.
- There is no atomic GitHub operation combining a main-ref check and release creation.
  The release is pinned to the verified main SHA; a later main movement cannot alter
  its contents. Concurrent tag/release writers can still make publication fail.
- This is a debug-signed preview, not stable production/private-key signing, physical
  TV acceptance, home-server acceptance, or full-film playback acceptance.

Offline tests: `python3 -m unittest discover -s scripts/tests -p test_preview_release.py -v`.
No live release or workflow run is performed by these tests.
