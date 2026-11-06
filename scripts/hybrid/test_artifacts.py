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
import zlib

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

    def write_device_png(self, path, size):
        """A valid, flat grayscale PNG fixture; no imaging dependency or real media."""
        width, height = map(int, size.split("x"))

        def chunk(kind, data):
            return (struct.pack(">I", len(data)) + kind + data +
                    struct.pack(">I", zlib.crc32(kind + data)))

        pixels = (b"\x00" + b"\x80" * width) * height
        path.write_bytes(b"\x89PNG\r\n\x1a\n" +
                         chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)) +
                         chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))

    def device_evidence(self, size="1920x1080", density=320):
        methods = ["firstCase", "secondCase"]
        self.write("app/src/androidTest/java/dev/cinematv/HybridHostUiTest.kt",
                   "\n".join(f"@Test fun {name}() {{}}" for name in methods))
        self.commit()
        bundle = Path(self.temp.name) / "device"
        artifacts.device_bundle(self.root, self.apk, self.apk, bundle)
        evidence = Path(self.temp.name) / "evidence"
        captures = evidence / "hybrid-evidence"
        captures.mkdir(parents=True)
        (evidence / "device.txt").write_text("Synthetic host fixture, not actual device evidence\n"
                                           f"Physical size: {size}\nPhysical density: {density}\n")
        log = "".join("INSTRUMENTATION_STATUS: class=dev.cinematv.HybridHostUiTest\n"
                      f"INSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 0\n"
                      for name in methods) + "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
        (evidence / "instrumentation.txt").write_text(log)
        for name in methods:
            self.write_device_png(captures / f"{name}.png", size)
            (captures / f"{name}.json").write_text(json.dumps(dict(method=name, expectedMode="hybrid",
                api=36, activity="dev.cinematv.MainActivity", syntheticContent=True)))
        return methods, bundle, evidence, captures

    def test_each_device_case_requires_matching_host_lane_and_exported_evidence(self):
        methods, bundle, evidence, captures = self.device_evidence()
        result = artifacts.device_result(self.root, bundle, evidence, "hybrid", "1920x1080", 320)
        self.assertEqual(set(result["testEvidence"]), set(methods))
        (evidence / "device-receipt.json").unlink()
        for method in methods:
            (captures / f"{method}.json").write_text(json.dumps(dict(method=method,
                requestedMode="provider-policy", expectedMode="native-fallback", api=29,
                provider="83.0.0.0", messageListener=False,
                activity="dev.cinematv.MainActivity", syntheticContent=True)))
        artifacts.device_result(self.root, bundle, evidence, "provider-policy", "1920x1080", 320)
        with self.assertRaises(ValueError):
            artifacts.device_result(self.root, bundle, evidence, "hybrid", "1920x1080", 320)
        for method in methods:
            (captures / f"{method}.json").write_text(json.dumps(dict(method=method, expectedMode="hybrid",
                api=36, activity="dev.cinematv.MainActivity", syntheticContent=True)))
        case_path = captures / "secondCase.json"
        original = json.loads(case_path.read_text())
        for changed in (dict(expectedMode="native-fallback"), dict(api=26),
                        dict(activity="dev.cinematv.TvTestActivity"), dict(method="otherCase")):
            case_path.write_text(json.dumps({**original, **changed}))
            with self.assertRaises(ValueError):
                artifacts.device_result(self.root, bundle, evidence, "hybrid", "1920x1080", 320)
        case_path.write_text(json.dumps(original))
        (captures / "secondCase.png").unlink()
        with self.assertRaisesRegex(ValueError, "Missing per-case"):
            artifacts.device_result(self.root, bundle, evidence, "hybrid", "1920x1080", 320)

    def test_device_result_accepts_matching_1080_and_4k_evidence(self):
        methods, bundle, evidence, captures = self.device_evidence()
        for size, density in (("1920x1080", 320), ("3840x2160", 640)):
            with self.subTest(size=size, density=density):
                (evidence / "device.txt").write_text(f"Physical size: {size}\nPhysical density: {density}\n")
                for method in methods:
                    self.write_device_png(captures / f"{method}.png", size)
                result = artifacts.device_result(self.root, bundle, evidence, "hybrid", size, density)
                self.assertEqual(result["expectedDisplay"], {"size": size, "density": density})
                self.assertEqual(result["display"], result["expectedDisplay"])
                self.assertEqual(set(result["testEvidence"]), set(methods))
                (evidence / "device-receipt.json").unlink()

    def test_device_result_uses_wm_override_instead_of_physical_display(self):
        _, bundle, evidence, _ = self.device_evidence(size="3840x2160", density=640)
        (evidence / "device.txt").write_text("Physical size: 1920x1080\r\nOverride size: 3840x2160\r\n"
                                           "Physical density: 320\r\nOverride density: 640\r\n")
        result = artifacts.device_result(self.root, bundle, evidence, "hybrid", "3840x2160", 640)
        self.assertEqual(result["display"], {"size": "3840x2160", "density": 640})

    def test_4k_lane_rejects_1080_wm_even_when_captures_are_4k(self):
        _, bundle, evidence, _ = self.device_evidence(size="3840x2160", density=640)
        for sizes in ("Physical size: 1920x1080\n",
                      "Physical size: 3840x2160\nOverride size: 1920x1080\n"):
            with self.subTest(sizes=sizes):
                (evidence / "device.txt").write_text(sizes + "Physical density: 640\n")
                with self.assertRaisesRegex(ValueError, "wm size differs"):
                    artifacts.device_result(self.root, bundle, evidence, "hybrid", "3840x2160", 640)
                self.assertFalse((evidence / "device-receipt.json").exists())

    def test_4k_lane_rejects_each_1080_capture_even_when_wm_is_4k(self):
        methods, bundle, evidence, captures = self.device_evidence(size="3840x2160", density=640)
        for method in methods:
            with self.subTest(method=method):
                png = captures / f"{method}.png"
                self.write_device_png(png, "1920x1080")
                with self.assertRaisesRegex(ValueError, f"PNG dimensions differ.*{method}"):
                    artifacts.device_result(self.root, bundle, evidence, "hybrid", "3840x2160", 640)
                self.assertFalse((evidence / "device-receipt.json").exists())
                self.write_device_png(png, "3840x2160")

    def test_device_result_rejects_wrong_effective_density(self):
        _, bundle, evidence, _ = self.device_evidence(size="3840x2160", density=640)
        for densities in ("Physical density: 320\n",
                          "Physical density: 640\nOverride density: 320\n"):
            with self.subTest(densities=densities):
                (evidence / "device.txt").write_text("Physical size: 3840x2160\n" + densities)
                with self.assertRaisesRegex(ValueError, "wm density differs"):
                    artifacts.device_result(self.root, bundle, evidence, "hybrid", "3840x2160", 640)
                self.assertFalse((evidence / "device-receipt.json").exists())

    def test_missing_or_ambiguous_wm_metadata_cannot_pass(self):
        valid = "Physical size: 1920x1080\nPhysical density: 320\n"
        for metadata in ("Synthetic host fixture only", valid.replace("Physical density: 320\n", ""),
                         valid + "Physical size: 1920x1080\n",
                         valid + "Override density: 320\nOverride density: 640\n"):
            with self.subTest(metadata=metadata), self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
                artifacts.device_display(metadata, "1920x1080", 320)

    def test_signature_without_png_ihdr_cannot_pass(self):
        _, bundle, evidence, captures = self.device_evidence()
        (captures / "secondCase.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"synthetic" * 160)
        with self.assertRaisesRegex(ValueError, "Invalid device PNG"):
            artifacts.device_result(self.root, bundle, evidence, "hybrid", "1920x1080", 320)

    def test_invalid_wm_override_never_falls_back_to_physical(self):
        valid = "Physical size: 1920x1080\nPhysical density: 320\n"
        for override in ("Override size: \n", "Override density: invalid\n"):
            with self.subTest(override=override), self.assertRaisesRegex(ValueError, "Invalid device wm"):
                artifacts.device_display(valid + override, "1920x1080", 320)

    def test_device_result_cli_requires_expected_size_and_density(self):
        script = Path(__file__).with_name("artifacts.py")
        base = ["python3", str(script), "device-result", "--bundle", "unused", "--evidence", "unused",
                "--mode", "hybrid"]
        for arguments in ([], ["--expected-size", "3840x2160"], ["--expected-density", "640"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run(base + arguments, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
                self.assertIn("required", result.stderr)

    def test_candidate_script_rejects_any_signing_variable_before_build(self):
        script = Path(__file__).with_name("build-dev-candidate.sh")
        for name in ("CINEMATV_KEYSTORE_PATH", "CINEMATV_KEYSTORE_PASSWORD", "CINEMATV_KEY_ALIAS", "CINEMATV_KEY_PASSWORD"):
            result = subprocess.run(["bash", str(script), str(Path(self.temp.name) / "rejected")],
                cwd=self.root, env={**os.environ, "ANDROID_HOME": self.temp.name, name: "SYNTHETIC"},
                capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("refuses external signing credentials", result.stderr)
            self.assertNotIn("SYNTHETIC", result.stdout + result.stderr)

    def test_device_startup_recovers_transport_but_rejects_persistent_offline(self):
        folder = Path(self.temp.name)
        fake = folder / "fake-adb"
        fake.write_text("""#!/usr/bin/env bash
set -eu
if [ "$1" = version ]; then echo 'Synthetic ADB for readiness contract test'; exit 0; fi
[ "$1" = -s ] && [ "$2" = emulator-5554 ]
shift 2
if [ "$1" = get-state ]; then
  attempts=0
  if [ -f "$FAKE_ADB_COUNTER" ]; then attempts=$(cat "$FAKE_ADB_COUNTER"); fi
  attempts=$((attempts + 1))
  printf '%s' "$attempts" > "$FAKE_ADB_COUNTER"
  if [ "$FAKE_ADB_MODE" = offline ] || [ "$attempts" = 1 ] || [ "$attempts" = 3 ]; then
    echo 'adb: device offline' >&2
    exit 1
  fi
  echo device
elif [ "$*" = 'shell getprop sys.boot_completed' ]; then
  printf '1\\r\\n'
else
  exit 2
fi
""")
        fake.chmod(0o755)
        script = Path(__file__).with_name("wait-device.sh")
        counter = folder / "adb-count"
        evidence = folder / "startup.txt"
        env = {**os.environ, "FAKE_ADB_COUNTER": str(counter), "FAKE_ADB_MODE": "reconnect"}
        result = subprocess.run(["bash", str(script), str(fake), "emulator-5554", str(evidence), "10"],
                                env=env, capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(counter.read_text(), "5")
        self.assertIn("adb: device offline", evidence.read_text())
        counter.unlink()
        result = subprocess.run(["bash", str(script), str(fake), "emulator-5554", str(evidence), "2"],
            env={**env, "FAKE_ADB_MODE": "offline"}, capture_output=True, text=True, timeout=7)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("before the deadline", result.stderr)


if __name__ == "__main__":
    unittest.main()
