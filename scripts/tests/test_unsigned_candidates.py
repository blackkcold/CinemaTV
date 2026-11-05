"""Exercise the real candidate script with synthetic APK bytes, never keys or Gradle."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/updates/build-unsigned-candidate.sh'


class UnsignedCandidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='cinematv-public-candidate-test-')
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.repo, self.sdk, self.bin = root / 'repo', root / 'sdk', root / 'bin'
        for path in (self.repo, self.sdk, self.bin):
            path.mkdir()
        self.env = dict(PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        ANDROID_HOME=str(self.sdk), HOME=str(root),
                        GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        for path, text in {
            '.gitignore': '**/build/\n*.apk\n',
            'app/src/main/keep.txt': 'accepted app source\n',
            'backdrop/src/main/keep.txt': 'accepted backdrop source\n',
            'app/build.gradle.kts': 'synthetic build configuration\n',
        }.items():
            target = self.repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
        self.git('init', '-q')
        self.commit()
        self.tool(self.bin / 'gradle', '''
import json, os, pathlib, sys, zipfile
sdk = pathlib.Path(os.environ['ANDROID_HOME'])
(sdk / 'gradle-args.json').write_text(json.dumps(sys.argv[1:]))
args = dict(arg[2:].split('=', 1) for arg in sys.argv[1:] if arg.startswith('-P'))
channel = args['cinematvChannel']
values = dict(package='dev.cinematv', versionCode=args['cinematvVersionCode'],
    versionName=args['cinematvVersionName'], channel=channel, minSdk='26',
    label='CinemaTV Dev' if channel == 'dev' else 'CinemaTV', debuggable='false')
values.update(json.loads(os.environ.get('SYNTHETIC_MANIFEST_OVERRIDES', '{}')))
manifest = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
    'package="{package}" android:versionCode="{versionCode}" android:versionName="{versionName}">'
    '<uses-sdk android:minSdkVersion="{minSdk}"/>'
    '<application android:debuggable="{debuggable}" android:label="{label}">'
    '<meta-data android:name="dev.cinematv.UPDATE_CHANNEL" android:value="{channel}"/>'
    '</application></manifest>').format(**values)
(sdk / 'manifest.xml').write_text(manifest)
apk = pathlib.Path('app/build/outputs/apk/release/app-release-unsigned.apk')
apk.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(apk, 'w') as archive:
    archive.writestr('AndroidManifest.xml', manifest)
    archive.writestr('TEST-ONLY.txt', 'Synthetic non-installable host-test fixture. No signatures.')
if os.environ.get('SYNTHETIC_MUTATE_SOURCE'):
    pathlib.Path('app/src/main/keep.txt').write_text('changed during build')
''')
        self.tool(self.sdk / 'build-tools/36.0.0/apksigner', '''
import os, sys
sys.exit(0 if os.environ.get('SYNTHETIC_VALID_SIGNATURE') else 1)
''')
        self.tool(self.sdk / 'cmdline-tools/latest/bin/apkanalyzer', '''
import os, pathlib
print((pathlib.Path(os.environ['ANDROID_HOME']) / 'manifest.xml').read_text())
''')

    def tool(self, path, source):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#!' + sys.executable + '\n' + source)
        path.chmod(0o755)

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, env=self.env, text=True).strip()

    def commit(self):
        self.git('add', '.')
        self.git('-c', 'user.name=Synthetic Test', '-c', 'user.email=test@example.invalid',
                 'commit', '-qm', 'Synthetic public candidate fixture')

    def run_script(self, *args, **environment):
        return subprocess.run(['bash', str(SCRIPT), *args], cwd=self.repo,
                              env={**self.env, **environment}, capture_output=True, text=True)

    def assert_no_build(self, result, message):
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message, result.stdout + result.stderr)
        self.assertFalse((self.sdk / 'gradle-args.json').exists())

    def check_candidate(self, args, channel, code, name, output):
        result = self.run_script(*args, GITHUB_ACTIONS='true', GITHUB_REPOSITORY='blackkcold/CinemaTV',
                                 GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2',
                                 GITHUB_EVENT_NAME='pull_request', GITHUB_REF='refs/pull/32/merge',
                                 CANDIDATE_SOURCE_HEAD='a' * 40)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        folder = self.repo / output
        receipt = json.loads((folder / 'unsigned-receipt.json').read_text())
        self.assertEqual((receipt['channel'], receipt['versionCode'], receipt['versionName']),
                         (channel, code, name))
        self.assertEqual(receipt['sourceCommit'], self.git('rev-parse', 'HEAD'))
        self.assertEqual(receipt['sourceTree'], self.git('rev-parse', 'HEAD^{tree}'))
        self.assertEqual(receipt['sourceHead'], 'a' * 40)
        for key, ref in [('applicationSourceTree', 'app/src'), ('backdropSourceTree', 'backdrop/src'),
                         ('applicationBuildBlob', 'app/build.gradle.kts')]:
            self.assertEqual(receipt[key], self.git('rev-parse', 'HEAD:' + ref))
        self.assertEqual(receipt['workflow']['runId'], '123')
        self.assertEqual(receipt['workflow']['runAttempt'], '2')
        self.assertEqual(receipt['workflow']['ref'], 'refs/pull/32/merge')
        self.assertEqual(receipt['artifactLabel'], 'NOT FOR INSTALLATION')
        self.assertEqual(receipt['status'], 'unsigned-not-installable-not-certified')
        self.assertEqual(receipt['applicationId'], 'dev.cinematv')
        self.assertEqual(receipt['minSdk'], 26)
        apk = folder / f'CinemaTV-{name}-code{code}-{channel}-UNSIGNED.apk'
        self.assertEqual(receipt['unsignedApkSha256'], hashlib.sha256(apk.read_bytes()).hexdigest())
        self.assertEqual(receipt['apkBytes'], apk.stat().st_size)
        self.assertIn('NOT FOR INSTALLATION', (folder / 'NOT-FOR-INSTALLATION.txt').read_text())
        build_args = json.loads((self.sdk / 'gradle-args.json').read_text())
        for expected in [f'-PcinematvChannel={channel}', f'-PcinematvVersionCode={code}',
                         f'-PcinematvVersionName={name}', '-PcinematvUnsignedCandidate=true',
                         '-PcinematvUpdatesEnabled=true', ':app:testReleaseUnitTest', ':app:lintRelease']:
            self.assertIn(expected, build_args)

    def test_default_stable_reservation_and_receipt_remain_compatible(self):
        self.check_candidate([], 'stable', 10, '0.8.0', 'candidate')

    def test_explicit_stable_reservation(self):
        self.check_candidate(['stable'], 'stable', 10, '0.8.0', 'candidate')

    def test_dev_reservation_and_provenance(self):
        self.check_candidate(['dev'], 'dev', 11, '0.8.0-dev.1', 'candidate-dev')

    def test_invalid_arguments_reject_before_build(self):
        for args in [('preview',), ('dev', 'arbitrary-version')]:
            with self.subTest(args=args):
                self.assert_no_build(self.run_script(*args), 'Usage:')

    def test_existing_receipt_is_never_overwritten(self):
        folder = self.repo / 'candidate-dev'
        folder.mkdir()
        receipt = folder / 'unsigned-receipt.json'
        receipt.write_text('preserve this receipt')
        self.assert_no_build(self.run_script('dev'), 'Candidate output already exists')
        self.assertEqual(receipt.read_text(), 'preserve this receipt')

    def test_dirty_tracked_source_rejected(self):
        (self.repo / 'app/src/main/keep.txt').write_text('dirty')
        self.assert_no_build(self.run_script('dev'), 'clean tracked source tree')

    def test_untracked_source_rejected(self):
        (self.repo / 'app/src/main/untracked.txt').write_text('dirty')
        self.assert_no_build(self.run_script('dev'), 'clean tracked source tree')

    def test_ignored_source_rejected(self):
        (self.repo / 'app/src/main/ignored.apk').write_text('synthetic ignored source')
        self.assert_no_build(self.run_script('dev'), 'untracked ignored files')

    def test_account_experiment_rejected_even_for_dev(self):
        path = self.repo / 'app/src/main/java/dev/cinematv/data/douban/Account.kt'
        path.parent.mkdir(parents=True)
        path.write_text('// synthetic unaccepted account source')
        self.commit()
        for channel in ('stable', 'dev'):
            self.assert_no_build(self.run_script(channel), 'unaccepted Douban personal-account sources')

    def test_source_mutation_during_build_rejected(self):
        result = self.run_script('dev', SYNTHETIC_MUTATE_SOURCE='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.repo / 'candidate-dev').exists())

    def test_valid_signature_rejected(self):
        result = self.run_script('dev', SYNTHETIC_VALID_SIGNATURE='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('unexpectedly has a valid signature', result.stdout)
        self.assertFalse((self.repo / 'candidate-dev').exists())

    def test_wrong_packaged_identity_never_gets_receipt(self):
        for field, value in [('package', 'wrong.package'), ('versionCode', '10'),
                             ('versionName', '0.8.0'), ('channel', 'stable'),
                             ('minSdk', '25'), ('label', 'CinemaTV'), ('debuggable', 'true')]:
            with self.subTest(field=field):
                result = self.run_script('dev', SYNTHETIC_MANIFEST_OVERRIDES=json.dumps({field: value}))
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.repo / 'candidate-dev/unsigned-receipt.json').exists())
                # Only synthetic output from this test is removed to isolate the next case.
                import shutil
                shutil.rmtree(self.repo / 'candidate-dev')

    def test_credential_names_rejected_without_printing_values(self):
        for name in ('CINEMATV_KEYSTORE_PATH', 'CINEMATV_KEY_ALIAS', 'CINEMATV_KEY_PASSWORD'):
            with self.subTest(name=name):
                result = self.run_script('dev', **{name: 'SYNTHETIC-NOT-A-CREDENTIAL'})
                self.assert_no_build(result, 'signing credentials')
                self.assertNotIn('SYNTHETIC-NOT-A-CREDENTIAL', result.stdout + result.stderr)

    def test_dedicated_jobs_cannot_sign_or_publish(self):
        workflow = (ROOT / '.github/workflows/update-channels.yml').read_text()
        for channel, output in [('stable', 'candidate'), ('dev', 'candidate-dev')]:
            job = workflow.split(f'  unsigned-{channel}-candidate:\n', 1)[1].split('\n  unsigned-', 1)[0]
            self.assertIn('ref: ${{ github.sha }}', job)
            self.assertIn(f'UNSIGNED-{channel}-candidate-NOT-FOR-INSTALLATION', job)
            self.assertIn(f'path: {output}/', job)
            self.assertNotIn('secrets.', job)
            self.assertNotIn('contents: write', job)
            self.assertNotIn('gh release', job)
            self.assertNotIn('apksigner sign', job)


if __name__ == '__main__':
    unittest.main()
