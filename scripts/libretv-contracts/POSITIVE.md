# Official upstream controlled-static-fixture checks

This runner extends the negative/authentication contracts without modifying
`run.py`. It imports that runner's archive check, minimal child environment,
loopback process lifecycle and request helper. It additionally verifies the exact
Node 22.23.4 archive digest and compares the executable bytes with the binary in
that archive. It accepts only immutable full-commit raw URLs for the one reviewed
fixture path in `blackkcold/CinemaTV`.

Run after obtaining the separately verified official artifacts:

    python scripts/libretv-contracts/positive.py \
      --archive /tmp/cinematv-official-libretv/libretv-2.21.1-standalone.tar.gz \
      --sums /tmp/cinematv-official-libretv/SHA256SUMS \
      --node /tmp/cinematv-official-libretv/node-v22.23.4-linux-x64/bin/node \
      --node-archive /tmp/cinematv-official-libretv/node-v22.23.4-linux-x64.tar.xz \
      --fixture-url https://raw.githubusercontent.com/blackkcold/CinemaTV/74d104aa2b4e212163376bcbb1da04bef6670918/scripts/libretv-contracts/fixtures/blender-bbb-static-cms.json \
      --output /tmp/libretv-positive-evidence.json

Offline checks:

    python scripts/libretv-contracts/test_positive.py
    python scripts/libretv-contracts/fixtures/test_fixture.py

The runner first checks the plain/search/detail raw-file response digests against
the reviewed manifest, rejecting redirects. It verifies a bounded fresh MP4 range.
It then authenticates a disposable, unmodified official upstream process, checking
positive search mapping, uncached/cached NDJSON, detail media and attribution,
cached detail equality and logout. Passwords/cookies are never printed or saved.
Failed runs overwrite their specified evidence output with a failed result;
only a fresh zero exit status and `result: passed` establish success.

## Observed outcome, 2026-11-04

`evidence/positive-static-fixture-2026-11-04.json` is **failed evidence**, not
acceptance. Ten preflight/auth/status assertions succeeded, including all three
published fixture digests and the fresh MP4 range. Search returned HTTP 200 but
zero results and a source failure `fetch failed`. A separate one-shot fetch using
the verified Node binary with an empty environment except PATH returned
`TypeError`, cause code `EAI_AGAIN`. A DNS lookup also returned `EAI_AGAIN`.
The parent environment's normal HTTP proxy could retrieve the files, but the
isolated upstream's clean network environment could not resolve the host.

The diagnostic run stopped at the first positive-search failure and terminated the
upstream process. NDJSON/detail positive contracts consequently remain **not run**.
There was no access-denial workaround or successful positive run. No DNS override,
proxy injection, upstream source edit, fetch interception or private-address
substitution was used. Rerun in an authorized environment that legitimately
supports ordinary DNS and HTTPS for this immutable URL; keep upstream guards and
clean child environment unchanged. This module does not provision that environment.

## Claim boundary

See `fixtures/README.md` and the manifest. The file always returns the same record;
no keyword filtering, paging or requested-ID matching is implemented. The official
parser picks its first record for detail. A passing run would establish only
controlled static protocol parsing with a permitted real media URL, not a working
dynamic CMS source or Android/home-server/production acceptance. The runner never
calls `/api/douban` or connects the normal Android UI, which otherwise implicitly
loads that endpoint. Source status lists must be empty. No arbitrary content
sources, production service or personal credentials are involved.

## Narrow GitHub runner verification

`.github/workflows/official-libretv-positive.yml` allows manual dispatch and narrowly filtered pushes only to
`test/official-libretv-positive`, with read-only repository access, non-overlapping
concurrency and a 12-minute job deadline. It downloads only the fixed official
LibreTV/Node archives and published SHA manifests over HTTPS; both manifest entries
and archive bytes must match local SHA pins before extraction/execution. No Android
build/emulator, proxy/resolver changes or secret input is used. The harness remains
unchanged and the public fixture URL stays pinned to commit `74d104aa...`.

After review and checking no equivalent run is active, the parent can trigger
one run by pushing a reviewed change to the dedicated test branch. Only changes
to this workflow or its positive runner, artifact verifier, or their two host test
files trigger it. Main, PRs, schedules and unrelated changes never trigger it.
Manual dispatch remains available when supported:

    gh workflow run official-libretv-positive.yml --ref test/official-libretv-positive --repo blackkcold/CinemaTV

The narrow push trigger is the reviewed alternative when manual dispatch is
unavailable. It does not require credentials beyond the existing authorized
repository workflow. No credentials are acquired or stored by this harness.
The job also checks its branch and event independently of trigger filtering.

Outcome JSON is uploaded even after failure, under a run/attempt-specific artifact
name. Early download/verification failures leave a `not_run` outcome; harness
failures produce `failed`. Neither counts as acceptance. The historical cloud DNS
failure evidence is preserved separately and never overwritten by this workflow.

Additional offline scope/integrity checks:

    python scripts/libretv-contracts/test_workflow.py
