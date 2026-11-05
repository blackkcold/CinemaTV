"""Acceptance harness contracts, with synthetic public bytes and disposable TEST ONLY keys."""
import base64
import importlib.util
import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('test_envelopes', ROOT / 'scripts/updates/prepare-test-envelopes.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class UpdatePipelineHarnessTest(unittest.TestCase):
    def test_payloads_bind_actual_bytes_and_cover_rejection_layers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixtures = {}
            for name, code, channel in [('stable', 101, 'stable'), ('dev', 102, 'dev'), ('wrong-key', 102, 'dev')]:
                data = ('synthetic non-installable ' + name).encode()
                (root / (name + '.apk')).write_bytes(data)
                fixtures[name] = dict(versionCode=code, channel=channel, versionName=f'0.0.0-{name}.TEST-ONLY',
                                      packageName='dev.cinematv', sha256=hashlib.sha256(data).hexdigest())
            (root / 'wrong-package.apk').write_bytes(b'synthetic wrong-package fixture')
            (root / 'wrong-min-sdk.apk').write_bytes(b'synthetic actual-minSdk25 fixture')
            (root / 'fixture-receipt.json').write_text(json.dumps(dict(testOnly=True, fixtures=fixtures)))
            MODULE.payloads(root, 1800000000)
            cases = {p.name.removesuffix('.payload.json'): json.loads(p.read_text())
                     for p in (root / 'manifests').glob('*.payload.json')}
            self.assertEqual(len(cases), 21)
            self.assertEqual(cases['stable']['sha256'], fixtures['stable']['sha256'])
            self.assertEqual(cases['stable']['apkBytes'], (root / 'stable.apk').stat().st_size)
            self.assertEqual(cases['wrong-apk-key']['sha256'], fixtures['wrong-key']['sha256'])
            self.assertEqual(cases['archive-channel']['sha256'], cases['stable']['sha256'])
            self.assertEqual(cases['archive-channel']['channel'], 'dev')
            self.assertEqual(cases['archive-package']['applicationId'], 'dev.cinematv')
            self.assertEqual(cases['archive-package']['sha256'], hashlib.sha256((root / 'wrong-package.apk').read_bytes()).hexdigest())
            self.assertEqual(cases['archive-sdk']['minSdk'], 26)
            self.assertEqual(cases['archive-sdk']['sha256'], hashlib.sha256((root / 'wrong-min-sdk.apk').read_bytes()).hexdigest())
            self.assertEqual(cases['archive-sdk']['apkBytes'], (root / 'wrong-min-sdk.apk').stat().st_size)
            self.assertTrue(cases['archive-sdk']['apkUrl'].endswith('/wrong-min-sdk.apk'))
            self.assertLess(cases['expired']['expiresAt'], 1800000000)
            self.assertEqual(cases['wrong-envelope-key']['versionCode'], 199)
            self.assertTrue(all(case['apkUrl'].startswith('https://github.com/blackkcold/CinemaTV/releases/download/')
                                for name, case in cases.items() if name not in {'http-url', 'foreign-url'}))

    @unittest.skipUnless(shutil.which('java') and shutil.which('keytool'), 'Java and keytool required')
    def test_dedicated_signer_accepts_only_disposable_test_identity(self):
        with tempfile.TemporaryDirectory(prefix='cinematv-test-key-') as directory:
            root = Path(directory)
            keydir, artifacts = root / 'keys', root / 'artifacts'
            keydir.mkdir(mode=0o700)
            artifacts.mkdir()
            env = {k: v for k, v in os.environ.items() if not k.startswith('CINEMATV_KEYSTORE_')
                   and k not in {'CINEMATV_KEY_PASSWORD', 'CINEMATV_KEY_ALIAS'}}
            env['GITHUB_ACTIONS'] = 'true'  # this TEST ONLY helper works; production helper stays forbidden
            for name, subject in [('test', 'CN=TEST ONLY disposable CI fixture,OU=Not for distribution,O=CinemaTV Tests,C=ZZ'),
                                  ('other', 'CN=Unapproved identity')]:
                subprocess.run(['keytool', '-genkeypair', '-keystore', str(keydir / (name + '.p12')),
                                '-storetype', 'PKCS12', '-storepass', 'test-only-password', '-alias', 'test-only',
                                '-keypass', 'test-only-password', '-keyalg', 'RSA', '-keysize', '2048',
                                '-validity', '2', '-dname', subject], check=True, capture_output=True, env=env)
            source = ROOT / 'scripts/updates/TestOnlySignUpdateManifest.java'
            classes = root / 'classes'
            subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', str(classes), str(source)],
                           check=True, capture_output=True, env=env)
            payload = artifacts / 'test.payload.json'
            payload.write_bytes(b'{"notes":"TEST ONLY"}')
            def sign(key, environment=env):
                return subprocess.run(['java', '-cp', str(classes), 'TestOnlySignUpdateManifest',
                                       str(keydir / (key + '.p12')), str(payload)], capture_output=True, env=environment)
            self.assertNotEqual(sign('other').returncode, 0)
            self.assertFalse((artifacts / 'test.json').exists())
            self.assertNotEqual(sign('test', {**env, 'CINEMATV_KEYSTORE_PATH': ''}).returncode, 0)
            result = sign('test')
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            envelope = json.loads((artifacts / 'test.json').read_text())
            self.assertEqual(base64.b64decode(envelope['payload']), payload.read_bytes())
            self.assertEqual(len(base64.b64decode(envelope['signature'])), 256)
            self.assertNotEqual(sign('test').returncode, 0, 'Must refuse overwriting existing envelopes')
            self.assertEqual(sorted(p.suffix for p in artifacts.iterdir()), ['.json', '.json'])

    def test_release_test_sources_and_dependency_are_explicit_opt_in(self):
        build = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('gradleProperty("cinematvUpdateAcceptance").getOrElse("false")', build)
        self.assertIn('if (updateAcceptance) testBuildType = "release"', build)
        self.assertIn('sourceSets["androidTest"].setRoot("src/updateAcceptance")', build)
        self.assertIn('if (updateAcceptance) androidTestImplementation("com.squareup.okhttp3:mockwebserver:', build)
        self.assertFalse((ROOT / 'app/src/main/java/dev/cinematv/data/update/UpdateRepositoryAcceptanceTest.kt').exists())
        source = (ROOT / 'scripts/updates/build-installer-fixtures.sh').read_text()
        self.assertIn('-PcinematvUnsignedCandidate=true -PcinematvUpdatesEnabled=true', source)
        self.assertNotIn('scripts/updates/SignUpdateManifest.java', source)
        self.assertIn('TestOnlySignUpdateManifest', source)
        self.assertIn('test-dex-packages.txt', source)
        workflow = (ROOT / '.github/workflows/update-channels.yml').read_text()
        self.assertIn('repository-acceptance:', workflow)
        self.assertIn('test-update-repository.sh fixtures repository-evidence', workflow)
        runner = (ROOT / 'scripts/updates/test-update-repository.sh').read_text()
        self.assertIn('Refusing existing package', runner)
        self.assertNotIn('uninstall', runner)
        suite = (ROOT / 'app/src/updateAcceptance/java/dev/cinematv/data/update/UpdateRepositoryAcceptanceTest.kt').read_text()
        self.assertEqual(suite.count('@Test fun'), 18)
        self.assertNotIn('assumeTrue', suite)
        import xml.etree.ElementTree as ET
        manifest = ET.parse(ROOT / 'scripts/updates/repository-fixtures/wrong-min-sdk.xml').getroot()
        self.assertEqual(manifest.get('package'), 'dev.cinematv')
        self.assertEqual(manifest.find('uses-sdk').get('{http://schemas.android.com/apk/res/android}minSdkVersion'), '25')


if __name__ == '__main__':
    unittest.main()
