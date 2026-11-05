"""Release-tool and fail-closed update contracts. All signing identities here are fake."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("prepare_update_manifest", ROOT / "scripts/updates/prepare-manifest.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
A = "{http://schemas.android.com/apk/res/android}"
FINGERPRINT = "a" * 64
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.cinematv"
 android:versionCode="100" android:versionName="0.7.0-dev.1">
 <uses-sdk android:minSdkVersion="26"/><application android:debuggable="false">
 <meta-data android:name="dev.cinematv.UPDATE_CHANNEL" android:value="dev"/>
 </application></manifest>'''


class UpdateChannelsTest(unittest.TestCase):
    def prepare(self, manifest=MANIFEST, fingerprint=FINGERPRINT):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            apk.write_bytes(b"synthetic non-installable APK fixture")
            results = [SimpleNamespace(stdout=f"Signer #1 certificate SHA-256 digest: {FINGERPRINT}\n"),
                       SimpleNamespace(stdout=manifest)]
            with patch.object(MODULE.subprocess, "run", side_effect=results):
                return MODULE.prepare(apk, "dev", "https://github.com/blackkcold/CinemaTV/releases/download/v0.7.0-dev.1/app.apk",
                                      fingerprint, "Test-only fixture", 1800000000, "apksigner", "apkanalyzer")

    def test_payload_uses_verified_apk_metadata(self):
        result = self.prepare()
        self.assertEqual(result["versionCode"], 100)
        self.assertEqual(result["channel"], "dev")
        self.assertEqual(len(result["sha256"]), 64)
        self.assertEqual(result["expiresAt"] - result["publishedAt"], 7 * 86400)

    def test_wrong_signer_debug_apk_channel_and_package_rejected(self):
        with self.assertRaises(ValueError):
            self.prepare(fingerprint="b" * 64)
        for original, modified in [('debuggable="false"', 'debuggable="true"'),
                                   ('android:value="dev"', 'android:value="stable"'),
                                   ('package="dev.cinematv"', 'package="wrong.package"'),
                                   ('versionCode="100"', 'versionCode="0"')]:
            with self.subTest(modified=modified), self.assertRaises(ValueError):
                self.prepare(MANIFEST.replace(original, modified))

    def test_private_file_provider_has_no_broad_roots(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        provider = manifest.find("application/provider")
        self.assertEqual(provider.get(A + "exported"), "false")
        self.assertEqual(provider.get(A + "authorities"), "${applicationId}.updates")
        paths = ET.parse(ROOT / "app/src/main/res/xml/update_file_paths.xml").getroot()
        self.assertEqual([(item.tag, item.get("path")) for item in paths], [("cache-path", "verified-updates/")])

    def test_dev_icon_is_different_but_retains_approved_foreground(self):
        icon = ET.parse(ROOT / "app/src/main/res/mipmap-anydpi-v26/ic_launcher_dev.xml").getroot()
        self.assertEqual(icon.find("foreground").get(A + "drawable"), "@drawable/ic_launcher_foreground")
        self.assertEqual(icon.find("background").get(A + "drawable"), "@color/cinema_dev_purple")

    def test_live_updates_are_explicit_opt_in_and_block_debug(self):
        build = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('gradleProperty("cinematvUpdatesEnabled").getOrElse("false")', build)
        repo = (ROOT / "app/src/main/java/dev/cinematv/data/update/UpdateRepository.kt").read_text()
        self.assertIn("BuildConfig.UPDATES_ENABLED && !BuildConfig.DEBUG", repo)
        self.assertIn("ApplicationInfo.FLAG_DEBUGGABLE == 0", repo)
        self.assertNotIn("DownloadManager", repo)

    def test_signing_tool_cannot_run_on_github_or_use_repo_key(self):
        source = (ROOT / "scripts/updates/SignUpdateManifest.java").read_text()
        self.assertIn('System.getenv("GITHUB_ACTIONS")', source)
        self.assertIn('directory.resolve(".git")', source)
        self.assertIn("StandardOpenOption.CREATE_NEW", source)
        self.assertNotIn("generateKey", source)


if __name__ == "__main__":
    unittest.main()
