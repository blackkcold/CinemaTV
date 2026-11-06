# External signature handoff · no publication in this branch

This branch is intentionally not merged or released. Its workflows never read production keys or sign the public APK. The original external signer remains the only authorized source of the existing certificate.

## Inputs and verification

Use the exact checkout and `acceptance-UNSIGNED-dev-NOT-FOR-INSTALLATION` bundle from the accepted source. After the external signer returns a **separate** APK, run:

```sh
python3 scripts/acceptance/signed_handoff.py \
  --candidate-directory /absolute/path/to/unsigned-candidate \
  --signed-apk /absolute/path/to/externally-signed.apk \
  --previous-apk /absolute/path/to/public-code12.apk \
  --android-sdk "$ANDROID_HOME" \
  --output /absolute/path/to/fresh-signed-evidence
```

The verifier is read-only with respect to all APK inputs. It runs Android SDK 36 `apksigner verify --min-sdk-version 26 --verbose --print-certs`, checks the pinned original certificate, requires v2 verification, checks 16KiB shared-library alignment, decodes the real manifest, and compares every unsigned application entry with the signed APK. Only newly added JAR signature metadata is exempt; arbitrary `META-INF` resources are not ignored. It also verifies the packaged production Web inventory.

Package `dev.cinematv`, Dev channel, version reservation in `scripts/hybrid/release.json`, non-debuggable/non-testOnly state and minSdk26 remain mandatory. The optional previous APK must use the same original certificate and the exact previous public versionCode. No key rotation or substitute signing key is performed.

Success produces `signed-handoff.json` with **signed-integrity-verified-device-acceptance-pending**, never a release-approval flag. Missing original-key material is not addressed by generating a replacement key, signing with a debug key, or uploading secrets to Actions.

## Final-byte acceptance still required before a future release

The signed receipt and final APK hash must accompany a fresh sequential device run. On disposable GitHub guests, exercise clean installation, code12-to-code13 replacement without uninstalling, current hybrid/native routing, native playback and Web return. Preserve settings, sources/subscription ownership, favorites and per-episode progress through replacement. A final update-feed test must bind its download hash and signature to those same accepted bytes and exercise the actual installer path.

The existing update acceptance fixture pipeline remains separate: it cannot prove the production APK was installed, and its synthetic signing identity cannot replace the public certificate. UI instrumentation signed with a debug key also cannot be used to instrument a non-debuggable production APK as though it shared the production signature. Final-byte UI tests need external black-box control rather than a production backdoor or debug-only API.

These final signed installation and upgrade results are **not asserted by this branch's source/debug manifest**. No automatic merge, tag, release, update-feed promotion or user-device installation is part of this handoff.

## Official references

- https://developer.android.com/tools/apksigner
- https://developer.android.com/tools/apkanalyzer
- https://developer.android.com/tools/zipalign
