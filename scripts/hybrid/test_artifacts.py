"""Exercise missing tests, source drift, identity mismatch and altered artifact rejection."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import artifacts


class HybridArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cinematv-hybrid-artifacts-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.release = dict(schema=1, channel="dev", versionName="0.9.0-dev.1", versionCode=13,
                            previousPublicVersionCode=12, applicationId="dev.cinematv", minSdk=26)
        self.write(".gitignore", "**/build/\nfrontend/tv-web/dist/\n*.apk\n.env\n")
        self.write("scripts/hybrid/release.json", json.dumps(self.release))
        self.write("release/signing-identity.json", json.dumps({"applicationId": "dev.cinematv", "certificateSha256": "a" * 64}))
        self.write("frontend/tv-web/package-lock.json", '{"lockfileVersion":3}\n')
        self.write("frontend/tv-web/src/main.ts", "export const synthetic = true;\n")
        self.write("app/src/main/fixture.kt", "// synthetic source fixture\n")
        self.write("app/build.gradle.kts", "// synthetic build fixture\n")
        self.git("init", "-q")
        self.commit()
        self.write("frontend/tv-web/dist/index.html", '<html><script src="./assets/index.js"></script></html>')
        self.write("frontend/tv-web/dist/assets/index.js", "document.title='Synthetic artifact test';")
        self.apk = Path(self.temp.name) / "synthetic.apk"
        self.make_apk()

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True).strip()

    def commit(self):
        self.git("add", ".")
        self.git("-c", "user.name=Synthetic Test", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "Synthetic fixture")

    def make_apk(self, extra=None, alter=None):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for path in (self.root / "frontend/tv-web/dist").rglob("*"):
                if path.is_file():
                    name = path.relative_to(self.root / "frontend/tv-web/dist").as_posix()
                    archive.writestr("assets/tv-web/" + name, alter if alter and name.endswith(".js") else path.read_bytes())
            if extra:
                archive.writestr("assets/tv-web/" + extra, "unexpected")
            archive.writestr("TEST-ONLY.txt", "Host fixture, not an installable Android APK")

    def manifest(self, **overrides):
        values = dict(package="dev.cinematv", versionName="0.9.0-dev.1", versionCode="13",
                      minSdk="26", channel="dev", label="CinemaTV Dev", debuggable="false")
        values.update(overrides)
        path = Path(self.temp.name) / "manifest.xml"
        path.write_text(('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="{package}" android:versionName="{versionName}" android:versionCode="{versionCode}">'
            '<uses-sdk android:minSdkVersion="{minSdk}"/><application android:debuggable="{debuggable}" '
            'android:label="{label}"><meta-data android:name="dev.cinematv.UPDATE_CHANNEL" '
            'android:value="{channel}"/></application></manifest>').format(**values))
        return path

    def test_unsigned_receipt_binds_bytes_source_and_preserves_acceptance_limits(self):
        output = Path(self.temp.name) / "candidate"
        result = artifacts.candidate_receipt(self.root, self.apk, self.manifest(), output)
        self.assertEqual(result["sourceCommit"], self.git("rev-parse", "HEAD"))
        self.assertEqual(result["unsignedApkSha256"], artifacts.sha256(output / result["apk"]))
        self.assertEqual(result["expectedCertificateSha256"], "a" * 64)
        self.assertEqual((result["channel"], result["versionCode"]), ("dev", 13))
        self.assertFalse(result["signedArtifactAcceptance"])
        self.assertFalse(result["physicalTvAcceptance"])
        with self.assertRaisesRegex(ValueError, "already exists"):
            artifacts.candidate_receipt(self.root, self.apk, self.manifest(), output)

    def test_missing_changed_or_extra_web_assets_are_rejected(self):
        inventory = artifacts.web_inventory(self.root)
        self.make_apk(alter=b"altered production script")
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            artifacts.verify_apk_assets(self.apk, inventory)
        self.make_apk(extra="dev-fixture.js")
        with self.assertRaisesRegex(ValueError, "inventory differs"):
            artifacts.verify_apk_assets(self.apk, inventory)
        self.make_apk()
        inventory["missing.css"] = dict(bytes=0, sha256=hashlib.sha256(b"").hexdigest())
        with self.assertRaisesRegex(ValueError, "inventory differs"):
            artifacts.verify_apk_assets(self.apk, inventory)

    def test_web_symlinks_are_not_followed(self):
        (self.root / "frontend/tv-web/dist/escape.js").symlink_to(self.root / ".git/config")
        with self.assertRaisesRegex(ValueError, "symlinks"):
            artifacts.web_inventory(self.root)

    def test_invalid_signature_material_does_not_become_an_unsigned_candidate(self):
        with zipfile.ZipFile(self.apk, "a") as archive:
            archive.writestr("META-INF/CERT.RSA", b"deliberately invalid test signature")
        with self.assertRaisesRegex(ValueError, "v1 signature material"):
            artifacts.require_unsigned_archive(self.apk)
        self.make_apk()
        with zipfile.ZipFile(self.apk) as archive:
            offset = archive.start_dir
        content = self.apk.read_bytes()
        # A synthetic invalid signing footer is enough to ensure it cannot pass as unsigned.
        block = struct.pack("<Q", 16) + b"APK Sig Block 42"
        content = bytearray(content[:offset] + block + content[offset:])
        end = content.rfind(b"PK\x05\x06")
        struct.pack_into("<I", content, end + 16, offset + len(block))
        self.apk.write_bytes(content)
        with self.assertRaisesRegex(ValueError, "APK signing block"):
            artifacts.require_unsigned_archive(self.apk)

    def test_wrong_packaged_identity_never_produces_candidate(self):
        for overrides in (dict(package="other.app"), dict(versionCode="12"), dict(channel="stable"),
                          dict(minSdk="29"), dict(debuggable="true"), dict(label="CinemaTV")):
            with self.subTest(overrides=overrides):
                output = Path(self.temp.name) / "rejected"
                with self.assertRaises(ValueError):
                    artifacts.candidate_receipt(self.root, self.apk, self.manifest(**overrides), output)
                self.assertFalse(output.exists())

    def test_device_bundle_rejects_tampered_apk(self):
        output = Path(self.temp.name) / "device"
        artifacts.device_bundle(self.root, self.apk, self.apk, output)
        artifacts.verify_device_bundle(self.root, output)
        with (output / "app-debug.apk").open("ab") as stream:
            stream.write(b"modified")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            artifacts.verify_device_bundle(self.root, output)

    def test_device_bundle_cannot_be_reused_for_another_source_commit(self):
        output = Path(self.temp.name) / "device"
        artifacts.device_bundle(self.root, self.apk, self.apk, output)
        self.write("frontend/tv-web/src/main.ts", "export const changed = true;\n")
        self.commit()
        with self.assertRaisesRegex(ValueError, "differs from built source"):
            artifacts.verify_device_bundle(self.root, output)

    def test_dirty_ignored_or_wrong_checkout_rejected(self):
        with patch.dict(os.environ, {"EXPECTED_SOURCE_SHA": "b" * 40}):
            with self.assertRaisesRegex(ValueError, "expected exact source"):
                artifacts.clean_source(self.root)
        with patch.dict(os.environ, {"EXPECTED_SOURCE_SHA": self.git("rev-parse", "HEAD")}):
            artifacts.clean_source(self.root)
            ignored = self.write("app/src/main/untracked.apk", "synthetic ignored compiler input")
            with self.assertRaisesRegex(ValueError, "ignored files"):
                artifacts.clean_source(self.root)
            ignored.unlink()
            env_file = self.write("frontend/tv-web/.env", "VITE_UNTRACKED=synthetic\n")
            with self.assertRaisesRegex(ValueError, "ignored files"):
                artifacts.clean_source(self.root)
            env_file.unlink()
            self.write("app/src/main/fixture.kt", "changed")
            with self.assertRaisesRegex(ValueError, "clean source tree"):
                artifacts.clean_source(self.root)

    def test_version_reservation_cannot_reuse_or_downgrade_code(self):
        for code in (12, 11, 0):
            self.write("scripts/hybrid/release.json", json.dumps({**self.release, "versionCode": code}))
            with self.assertRaisesRegex(ValueError, "global public versionCode"):
                artifacts.config(self.root)

    def test_adb_zero_exit_or_success_summary_without_cases_is_insufficient(self):
        with self.assertRaises(ValueError):
            artifacts.instrumentation_results("OK (1 test)\nINSTRUMENTATION_CODE: -1\n", ["requiredCase"])
        log = ("INSTRUMENTATION_STATUS: class=dev.cinematv.HybridHostUiTest\n"
               "INSTRUMENTATION_STATUS: test=requiredCase\nINSTRUMENTATION_STATUS_CODE: 0\n"
               "OK (1 test)\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual(artifacts.instrumentation_results(log, ["requiredCase"]), ["requiredCase"])
        for bad in (log.replace("CODE: 0", "CODE: -3"), log.replace("CODE: 0", "CODE: -4"),
                    log.replace("requiredCase", "otherCase"), log + "Process crashed\n"):
            with self.assertRaises(ValueError):
                artifacts.instrumentation_results(bad, ["requiredCase"])

    def test_candidate_script_rejects_any_signing_variable_before_build(self):
        script = Path(__file__).with_name("build-dev-candidate.sh")
        for name in ("CINEMATV_KEYSTORE_PATH", "CINEMATV_KEYSTORE_PASSWORD", "CINEMATV_KEY_ALIAS", "CINEMATV_KEY_PASSWORD"):
            result = subprocess.run(["bash", str(script), str(Path(self.temp.name) / "rejected")],
                cwd=self.root, env={**os.environ, "ANDROID_HOME": self.temp.name, name: "SYNTHETIC"},
                capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("refuses external signing credentials", result.stderr)
            self.assertNotIn("SYNTHETIC", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
