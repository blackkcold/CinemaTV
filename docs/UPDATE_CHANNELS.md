# Stable / dev updates

This is an **opt-in implementation, not an active production update service**.
The current public signing identity is recorded in `release/signing-identity.json`.
Private signing material remains outside GitHub.
No update feed or release is published by the PR. Existing preview pipelines remain
unchanged. The obsolete production workflow is now a fail-closed safety notice;
it has no signing-secret references or publication permissions. Network updates are disabled by default and always disabled in debug APKs.

## User behavior

- Settings → About on TV, and Settings on phones, show the installed version/channel
  and a persisted stable/dev subscription. Checking and downloading are explicit.
- Dev has a purple adaptive launcher icon, purple TV launcher banner and `CinemaTV Dev` launcher label; stable
  retains the existing reviewed icon. Selecting a subscription does not pretend that
  a different APK is installed: the icon changes only after the actual update.
- Both channels use `dev.cinematv` and the **same fixed production signing key**.
  They cannot be installed side by side. Updates retain Room data and preferences.
- Only a strictly higher versionCode can install. Switching from a newer dev build
  to stable waits for a later stable release. No uninstall, downgrade flag, database
  rollback, data deletion or silent install is attempted.
- Android 8.0 / API 26 minimum. User must allow this app to install unknown apps and
  confirm the OS package installer. Returning from permission settings does not
  install automatically. TV devices without a permission page/installer report it.
- Existing preview debug APKs may have different keys. They **cannot be promised
  an in-place upgrade to the fixed key**. Do not recommend uninstalling as a seamless
  migration: it loses the current local library, and export/import is not implemented.

## Trust and transport

1. Fixed HTTPS endpoints:
   `https://github.com/blackkcold/CinemaTV/releases/download/channel-dev/update.json`
   and `.../channel-stable/update.json`. They are future publication contracts, not
   claims that feeds currently exist.
2. Envelope: `{ "schema": 1, "payload": "<base64 UTF-8 JSON>", "signature": "<base64>" }`.
   Exact payload bytes are signed using SHA256withRSA (or SHA256withECDSA for EC).
   The trust anchor is the **current installed APK signing certificate**, not a key
   supplied by the server. Multiple signers and key rotation fail closed in v1.
3. Payload schema: `schema`, `channel`, `versionCode`, `versionName`, `applicationId`,
   `minSdk`, `apkUrl`, `apkBytes`, `sha256`, `publishedAt`, `expiresAt`, `notes`.
   Timestamps are Unix seconds. Maximum validity is 31 days, generated manifests
   expire after 7 days. A channel-specific observed version watermark rejects older
   signed manifests. A wrong device clock can block updates, never bypass validation.
4. Only immutable APK asset URLs under this repository's releases are accepted.
   Up to five HTTPS redirects may reach GitHub's known asset hosts. An isolated
   OkHttp client sends no media cookies, provider tokens or custom source URLs.
5. Download to app-private cache; enforce 256 MiB cap, advertised byte count, free
   space and SHA-256. Recheck before install. Validate package, version, minSdk,
   channel metadata, non-debuggable state and exact signing certificate equality.
   PackageManager's archive parsing is **identity inspection, not a substitute for
   APK cryptographic verification**. Signed SHA-256 authenticates every byte;
   Android's installer performs the final APK signature and platform checks.
6. A non-exported FileProvider exposes only the update subdirectory with temporary
   read permission. There is no storage permission, DownloadManager, trust-all TLS,
   unsigned fallback, untrusted URL setting, background polling or silent install.

Expired feeds need a newly signed manifest, even if the latest APK is unchanged.
First-ever install still needs an independently verified download and certificate.
A compromised signing key is outside this v1 mechanism's recovery capability.

## Branch/version policy

- `main`: tested integration; `dev` releases are prereleases from a reviewed commit.
- Stable release tags: `vMAJOR.MINOR.PATCH`; dev tags:
  `vMAJOR.MINOR.PATCH-dev.N`. These are separate from the movable feed pointer tags.
- Maintain **one increasing versionCode ledger across both channels**. Reserve a
  new code for each public APK; never derive it separately from branch commit counts
  or reuse a code. Example: dev code 100, dev 101, stable 102, next dev 103.
- Only publish stable after the repository's full exact-commit test/hardware gates.
  A successful unit test or a debug build does not certify a signed update.
- Channel build selection uses `-PcinematvChannel=dev|stable`; it does not rename
  existing build types, tasks or APK output paths. Enable live updates only with
  `-PcinematvUpdatesEnabled=true -PcinematvVersionCode=<reserved-code>
  -PcinematvVersionName=<version>`, and all external signing environment variables.
  This configuration alone is not authorization to sign or publish.

## Signing and private storage

Keep **all private signing material outside GitHub**, including Actions
Secrets, logs, artifacts, caches and repository history. Public APKs, detached
signatures and certificate fingerprints are intended to be public.

Maintainers must retain the same signing identity across both channels and future
updates. Losing the key without a recoverable backup prevents future same-signature
updates. Keep encrypted recovery copies and restore instructions separate from
the repository, build artifacts and signing machine, and verify recovery through
a controlled process. Key creation and successful signing do not prove recovery.

Recovery status is tracked separately from APK acceptance. Validate
dev→stable→dev upgrades on API 26 and current Android/TV with test keys, then
perform the applicable final signed-artifact gates before publication.

The old `.github/workflows/release.yml` signing path has been retired. It now fails
with a safety notice, has read-only permissions, accepts no signing credentials
and cannot publish. Do not restore a GitHub-keystore signing path.

`scripts/updates/prepare-manifest.py` inspects a signed APK with official Android
`apksigner` and `apkanalyzer`, requiring an expected public signing fingerprint.
It only writes an unsigned public payload. Review it, then run
`java scripts/updates/SignUpdateManifest.java payload.json update.json` on the
approved external signing machine. The tool reads the existing external keystore
environment variables, refuses GitHub Actions and keys located in a Git checkout,
self-verifies its signature, never exports private material and refuses to
overwrite an existing output. It is not called by any workflow in this PR.

## Verification still required before enabling

- Full app build/lint/JVM and Python tests for the exact PR head.
- TV D-pad and phone channel selection/persistence, cancel/retry, reopening settings.
- Test-only signed release fixtures: valid signed manifest/APK; corrupted payload,
  wrong key, digest, size, package, version, channel, minSdk and expired manifest.
- Real Android installer: grant/deny/revoke unknown-app permission, return/back,
  cancel installation, successful same-key upgrade preserving data, wrong-key
  rejection, low storage, interrupted download, process death and missing installer.
- API 26 / current Android / actual low-end TV. No production signing, 1Password
  integration or complete device install loop is claimed by source changes alone.

Official references:
[Android signing](https://developer.android.com/studio/publish/app-signing),
[versioning and downgrade protection](https://developer.android.com/studio/publish/versioning),
[unknown-source authorization](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()),
[SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo),
[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider),
[download security](https://developer.android.com/privacy-and-security/risks/unsafe-download-manager).

## Stable isolation and release acceptance

Until the personal-account integration is accepted, `main` and stable candidates
must exclude `feature/douban-personal-account`. Its account data/UI directories
(`data/douban` and `ui/douban`) cause any `cinematvChannel=stable` build to fail.
This is a source-presence guard, not a hidden runtime toggle. Existing public
catalogue ratings/Top250 are unrelated and remain supported. Dev may integrate
that branch independently; do not merge its unaccepted code into `main`.

A stable label is not certification. Production publication additionally needs:

1. A reserved globally increasing versionCode, clean approved source commit/tree,
   stable channel, and no unaccepted personal-account sources.
2. The fixed externally held production key matching the published certificate
   fingerprint. Disposable debug certificates and retired keys are not that identity.
3. Build/lint/unit/backend checks plus complete phone/API26/TV1080/TV4K UI evidence
   for the candidate source. Do not reuse a debug APK hash for a release APK.
4. Acceptance tied to the **final signed APK bytes and certificate**: explicit
   installer permissions/cancel/return, same-key channel upgrades, real library
   data preservation, API26/current Android and physical TV. Run those checks on
   the signed artifact, then publish those unchanged bytes. Do not re-sign or
   rebuild after acceptance and call it the tested binary.
5. Public APK hash, certificate fingerprint, source commit/tree and acceptance
   evidence recorded together. Feed payload preparation is not a substitute for
   this review, and no current workflow automatically publishes stable.

`update-channels.yml` builds disposable, non-debug release fixtures with a clearly
marked test-only key outside the checkout. The key is deleted and never uploaded.
An isolated, separately packaged instrumentation probe checks private preferences
and a private SQLite sentinel across dev(100) → stable(101) → dev(102), verifies
wrong-certificate and downgrade rejection on API26/35, and retains public hashes
and test logs. The fixture APK artifact is explicitly TEST ONLY and expires after
one day. This checks Android PackageManager via adb, **not** the user-facing
installer permission screens, a real production key, all library migrations, or
physical TV. These remaining production gates must not be reported as passed.

If an existing preview has a different signing certificate, stop: there is no
same-package in-place migration to the new key. Do not uninstall, clear data or
suggest that a channel toggle bypasses Android signing. A separate, tested
export/import migration needs to exist before offering a data-preserving move.

## Public unsigned candidate transfer

`build-unsigned-candidate.sh` supports the two reserved entries in one global
versionCode sequence:

| Invocation | Channel | Version | Code | Output |
|---|---|---|---|---|
| no argument (unchanged default), or `stable` | stable | `0.8.0` | `10` | `candidate/` |
| `dev` | dev | `0.8.0-dev.1` | `11` | `candidate-dev/` |

Both use the same accepted main app source with no Douban personal-account code.
Code 11 cannot downgrade to stable code 10; a subsequent stable release needs a
new globally higher code. These reservations do not change the ordinary Gradle
defaults or the provenance of earlier public previews.

The dedicated stable and dev CI jobs check out the exact workflow SHA (the tested
merge commit on a PR), not a moving branch. Each builds release bytes with the
updater enabled and explicit `cinematvUnsignedCandidate=true`. This mode rejects
every signing credential and outputs an unsigned, non-installable artifact labeled
**NOT FOR INSTALLATION**. Its receipt records source commit/tree, originating PR
head, app/backdrop source trees, app build configuration, workflow run/attempt,
packaged identity and unsigned APK SHA-256. Existing output is not overwritten.
Gradle also rejects production signing variables in GitHub Actions. No private
key is needed to compile public code, and neither candidate job signs or publishes.

The external signer must compare this receipt and APK hash before signing, verify
the signed APK's package/channel/version/minSdk and approved public certificate,
and record its new hash. Only the signed bytes can undergo final installer/device
acceptance. The unsigned candidate, test-key fixtures and earlier debug evidence
are never a production release. The candidate job does not publish or certify it.

## Fixed public release identity

The active production identity's **public** certificate SHA-256 is
`07:98:f1:68:6a:28:ae:4a:38:6a:37:6e:fc:ab:ba:3a:4c:4b:b5:47:de:76:02:10:d9:44:a5:84:55:09:2e:8c`.
Machine-readable public metadata is in `release/signing-identity.json`. The private
key remains outside GitHub; this file contains no private key or password.
The previous unpublished identity is retired. **Backup/recovery verification is
pending for this identity**; no verified backup is claimed. Key creation alone
does not certify an APK. No previously distributed debug build is claimed to share
this signing identity. An APK signed with another key cannot be silently upgraded
to this key; v1 has no key-rotation or data-preserving migration bypass.


## Real updater instrumentation (TEST ONLY)

The `repository-acceptance` API26/API35 jobs execute the real `UpdateRepository`
against disposable, non-debug release fixtures. This is separate from adb installer
upgrade probes and does not publish any feed or production-signed APK.

- `-PcinematvUpdateAcceptance=true` selects `testBuildType=release` and the isolated
  `app/src/updateAcceptance` instrumentation source set. It requires explicit
  unsigned-candidate mode, updates enabled, dev channel and version code 100.
  Ordinary debug UI tests and their hosts are not part of this suite. APK dex
  inspection also checks that test classes do not ship in the release target.
- `build-installer-fixtures.sh` builds the existing dev100/stable101/dev102 fixtures
  with updates enabled, signs target/test APKs outside Gradle with disposable
  TEST ONLY certificates, and creates public signed envelopes through the separate
  `TestOnlySignUpdateManifest` helper. The production signing helper remains
  forbidden on GitHub. Keys stay outside checkout/artifact directories and are
  deleted by an EXIT trap; artifacts contain only public APKs, receipts and signatures.
- The instrumentation runner streams public fixtures from a host loopback HTTP
  server through adb reverse into private test cache. There is no external-storage
  permission, exported provider, run-as requirement, bundled duplicate APK asset,
  production endpoint override or trust-anchor bypass. An internal client seam
  rewrites validated official HTTPS requests to MockWebServer only in test code.
- The suite covers valid check/download/recheck for both channels, tampered,
  wrong-key and expired envelopes without watermark advancement, invalid signed
  package/channel/version/minSdk/URLs, stale manifests, SHA-256 and length failures,
  real archive package/channel/version/minSdk/signer mismatch, cancellation cleanup,
  private-path confinement, post-download tampering and untrusted redirects.
  The archive minSdk negative is a tiny non-debug fixture declaring 25 with a signed
  envelope claiming 26, so both API26 and API35 exercise identity rejection without
  an OS-incompatibility parse failure or an assumption skip. All 18 cases run on both.

Run on a clean checkout with Java17, Gradle8.13 and Android platform/build-tools36:

```sh
# Generates only disposable TEST ONLY keys; RUNNER_TEMP must be outside the checkout.
export RUNNER_TEMP="$(mktemp -d)"
bash scripts/updates/build-installer-fixtures.sh
# Fresh isolated API26 or API35 emulator, with adb connected and no existing target/test package:
bash scripts/updates/test-update-repository.sh "$RUNNER_TEMP/update-fixtures" repository-evidence
```

The fixture builder uses this base build invocation before external test-only signing:

```sh
gradle --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  -PcinematvChannel=dev -PcinematvVersionCode=100 \
  -PcinematvVersionName=0.0.0-base.TEST-ONLY \
  -PcinematvUnsignedCandidate=true -PcinematvUpdatesEnabled=true \
  -PcinematvUpdateAcceptance=true :app:assembleRelease :app:assembleReleaseAndroidTest
```

Passing this test establishes only the test-certificate repository pipeline. It does
not exercise the Android installer confirmation UI, real application Room migrations,
physical TV behavior, or final production-certificate signed-feed/APK acceptance.
Those remain required before active feed publication. Local source checks alone do
not establish that these instrumentation jobs compiled or passed.
