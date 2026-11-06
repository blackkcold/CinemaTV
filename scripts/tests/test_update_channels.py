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
    def prepare(self, manifest=MANIFEST, fingerprint=FINGERPRINT, channel="dev"):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            apk.write_bytes(b"synthetic non-installable APK fixture")
            results = [SimpleNamespace(stdout=f"Signer #1 certificate SHA-256 digest: {FINGERPRINT}\n"),
                       SimpleNamespace(stdout=manifest)]
            with patch.object(MODULE.subprocess, "run", side_effect=results):
                return MODULE.prepare(apk, channel, "https://github.com/blackkcold/CinemaTV/releases/download/v0.7.0-dev.1/app.apk",
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

    def test_stable_feed_rejects_preview_version_names(self):
        manifest = MANIFEST.replace('android:value="dev"', 'android:value="stable"')
        with self.assertRaises(ValueError):
            self.prepare(manifest=manifest, channel="stable")
        approved = self.prepare(manifest=manifest.replace('0.7.0-dev.1', '0.7.0'), channel="stable")
        self.assertEqual(approved['channel'], 'stable')

    def test_private_file_provider_has_no_broad_roots(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        providers = manifest.findall("application/provider")
        self.assertEqual(len(providers), 2)
        provider = next(item for item in providers
                        if item.get(A + "authorities") == "${applicationId}.updates")
        self.assertEqual(provider.get(A + "exported"), "false")
        self.assertEqual(provider.get(A + "grantUriPermissions"), "true")
        paths = ET.parse(ROOT / "app/src/main/res/xml/update_file_paths.xml").getroot()
        self.assertEqual([(item.tag, item.get("path")) for item in paths], [("cache-path", "verified-updates/")])

        diagnostics = next(item for item in providers
                           if item.get(A + "authorities") == "${applicationId}.diagnostics")
        self.assertEqual(diagnostics.get(A + "exported"), "false")
        self.assertEqual(diagnostics.get(A + "grantUriPermissions"), "true")
        diagnostic_paths = ET.parse(ROOT / "app/src/main/res/xml/diagnostic_file_paths.xml").getroot()
        self.assertEqual([(item.tag, item.get("path")) for item in diagnostic_paths],
                         [("external-files-path", "diagnostics/"), ("files-path", "diagnostics/")])

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

    def test_existing_preview_provenance_still_reads_literal_default(self):
        import re
        build = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIsNotNone(re.search(r'versionName = "([^\"]+)"', build))
        self.assertIn('providers.gradleProperty("cinematvVersionName").orNull?.let', build)

    def test_stable_refuses_unaccepted_personal_account_sources(self):
        build = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('if (updateChannel == "stable")', build)
        self.assertIn('src/main/java/dev/cinematv/data/douban', build)
        self.assertIn('src/main/java/dev/cinematv/ui/douban', build)
        self.assertIn('it.isFile && it.extension == "kt"', build)
        # The account-free public list package lives under data/douban and is excluded with it.
        self.assertTrue(list((ROOT / "app/src/main/java/dev/cinematv/data/douban/public").glob("*.kt")))
        self.assertNotIn('contains("douban")', build)

    def test_obsolete_workflow_cannot_receive_keys_or_publish(self):
        source = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn('contents: read', source)
        self.assertIn('exit 1', source)
        self.assertNotIn('${{ secrets.', source)
        self.assertNotIn('gh release create', source)
        self.assertNotIn('assembleRelease', source)

    def test_benchmark_cannot_inherit_enabled_updater(self):
        build = (ROOT / "app/build.gradle.kts").read_text()
        benchmark = build.split('create("glassBenchmark")')[1].split('compileOptions')[0]
        self.assertIn('buildConfigField("boolean", "UPDATES_ENABLED", "false")', benchmark)

    def test_fixture_keys_are_disposable_and_probe_is_outside_app(self):
        source = (ROOT / "scripts/updates/build-installer-fixtures.sh").read_text()
        self.assertIn('TEST ONLY disposable CI fixture', source)
        self.assertIn("trap 'rm -rf \"$KEYDIR\"' EXIT", source)
        self.assertIn('Refusing fixture build with external signing credentials', source)
        workflow = (ROOT / ".github/workflows/update-channels.yml").read_text()
        self.assertNotIn('update-test-keys/', workflow)
        self.assertIn('api: [26, 35]', workflow)
        self.assertFalse((ROOT / 'app/src/main/java/dev/cinematv/updateprobe').exists())

    def test_unsigned_candidate_requires_explicit_no_credential_mode(self):
        build = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('gradleProperty("cinematvUnsignedCandidate").getOrElse("false")', build)
        self.assertIn('require(externalSigningNames.none', build)
        self.assertIn('System.getenv("GITHUB_ACTIONS") != "true" || externalSigningNames.none', build)
        source = (ROOT / "scripts/updates/build-unsigned-candidate.sh").read_text()
        self.assertIn('-PcinematvUnsignedCandidate=true', source)
        self.assertIn('-PcinematvUpdatesEnabled=true', source)
        self.assertNotIn('apksigner sign', source)
        self.assertIn('Unsigned candidate unexpectedly has a valid signature', source)

    def test_candidate_and_safety_jobs_share_pinned_checkout(self):
        source_workflow = (ROOT / '.github/workflows/update-channels.yml').read_text()
        self.assertEqual(source_workflow.count('uses: actions/checkout@v4'),
                         source_workflow.count('ref: ${{ github.sha }}'))
        self.assertNotIn('ref: ${{ github.event.pull_request.head.sha', source_workflow)
        source = (ROOT / 'scripts/updates/build-unsigned-candidate.sh').read_text()
        self.assertIn('git status --porcelain --untracked-files=all', source)
        self.assertIn('git ls-files --others --ignored --exclude-standard app/src backdrop/src', source)
        self.assertIn('git diff --cached --exit-code', source)

    def test_release_identity_contains_only_public_fingerprint(self):
        import json
        identity = json.loads((ROOT / 'release/signing-identity.json').read_text())
        self.assertEqual(set(identity), {'schema', 'applicationId', 'certificateSha256'})
        self.assertEqual(identity['applicationId'], 'dev.cinematv')
        self.assertRegex(identity['certificateSha256'], r'^[0-9a-f]{64}$')

    def test_replacement_identity_and_pending_backup_are_documented(self):
        import json
        identity = json.loads((ROOT / 'release/signing-identity.json').read_text())
        docs = (ROOT / 'docs/UPDATE_CHANNELS.md').read_text()
        active = identity['certificateSha256']
        self.assertEqual(active, '0798f1686a28ae4a386a376efcabba3a4c4bb547de760210d944a58455092e8c')
        self.assertIn(':'.join(active[i:i + 2] for i in range(0, 64, 2)), docs)
        self.assertIn('Backup/recovery verification is\npending for this identity', docs)
        self.assertIn('is retired', docs)
        self.assertNotIn('The current key and encrypted backup were created', docs)
        self.assertNotIn('its encrypted backup verified on', docs)
        for private_context in ('the user requested', 'The user explicitly', 'specific approval',
                                'per-action approval', 'temporary protected retention',
                                'post-release storage decision'):
            self.assertNotIn(private_context, docs)

    def test_candidate_and_fixture_environment_guards_do_not_pipe_secrets(self):
        import os
        import subprocess
        for filename in ('build-installer-fixtures.sh', 'build-unsigned-candidate.sh'):
            path = ROOT / 'scripts/updates' / filename
            source = path.read_text()
            self.assertNotIn('env | grep', source)
            # Synthetic marker only. The guard must run before any key/build/filesystem action.
            env = dict(os.environ, ANDROID_HOME='/nonexistent-test-sdk', RUNNER_TEMP='/nonexistent-test-temp',
                       CINEMATV_KEY_ALIAS='SYNTHETIC-NOT-A-CREDENTIAL', LARGE_SYNTHETIC_VAR='x'*100000)
            result = subprocess.run(['bash', str(path)], cwd=ROOT, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn('signing credentials', result.stdout)
            self.assertNotIn('SYNTHETIC-NOT-A-CREDENTIAL', result.stdout + result.stderr)

    def test_signing_tool_cannot_run_on_github_or_use_repo_key(self):
        source = (ROOT / "scripts/updates/SignUpdateManifest.java").read_text()
        self.assertIn('System.getenv("GITHUB_ACTIONS")', source)
        self.assertIn('directory.resolve(".git")', source)
        self.assertIn("StandardOpenOption.CREATE_NEW", source)
        self.assertNotIn("generateKey", source)


if __name__ == "__main__":
    unittest.main()
