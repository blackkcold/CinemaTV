"""Offline safety/lifecycle tests; no network and no Android acceptance claims."""
import contextlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/libretv-contracts'))
import android_acceptance as lifecycle
import run_android_test as android

class OfficialAndroidLifecycleTest(unittest.TestCase):
    def test_session_private_credentials_and_cleanup_on_failure(self):
        remembered = []
        @contextlib.contextmanager
        def fake_server(node, directory, password):
            self.assertRegex(password, r'^[A-Za-z0-9_-]{43}$')
            yield 23456
        with tempfile.TemporaryDirectory() as tmp, patch.object(lifecycle, 'prepare', return_value=Path('/verified/node')), patch.object(lifecycle, 'verify_node'), patch.object(lifecycle.tarfile, 'open'), patch.object(lifecycle.base, 'server', fake_server), patch.object(lifecycle, 'validate_status'):
            with self.assertRaises(RuntimeError):
                with lifecycle.session(Path(tmp)) as credential:
                    remembered.append(credential)
                    self.assertEqual(credential.stat().st_mode & 0o777, 0o600)
                    self.assertEqual(credential.parent.stat().st_mode & 0o777, 0o700)
                    self.assertEqual(json.loads(credential.read_text())['server'], 'http://10.0.2.2:23456')
                    raise RuntimeError('test failure')
            self.assertFalse(remembered[0].exists())
            self.assertFalse(remembered[0].parent.exists())

    def test_status_fails_closed(self):
        safe = dict(version='2.21.1', passwordRequired=True, verified=False,
                    defaultSources=[], defaultLiveSources=[], defaultSubscriptions=[], defaultRecommendSource=None)
        with patch.object(lifecycle.base, 'request', return_value=(200, {}, json.dumps(safe))):
            lifecycle.validate_status(12345)
        for key, value in [('version', 'other'), ('passwordRequired', False), ('verified', True),
                           ('defaultSources', [{}]), ('defaultLiveSources', [{}]),
                           ('defaultSubscriptions', [{}]), ('defaultRecommendSource', {})]:
            with self.subTest(key=key), patch.object(lifecycle.base, 'request', return_value=(200, {}, json.dumps(dict(safe, **{key: value})))):
                with self.assertRaises(ValueError):
                    lifecycle.validate_status(12345)

    def test_context_exits_on_success_and_failure_and_report_has_no_password(self):
        for status in (0, 7):
            closed = []
            @contextlib.contextmanager
            def fake_session(directory):
                try:
                    yield Path('/private/random-credential.json')
                finally:
                    closed.append(True)
            with tempfile.TemporaryDirectory() as tmp, patch.object(lifecycle, 'session', fake_session), patch.object(lifecycle.subprocess, 'Popen') as process:
                process.return_value.wait.return_value = status
                process.return_value.poll.return_value = status
                report = Path(tmp) / 'report.json'
                self.assertEqual(lifecycle.execute(Path(tmp), ['true'], report), int(status != 0))
                self.assertEqual(closed, [True])
                self.assertNotIn('credential', report.read_text())
                self.assertNotIn('password', report.read_text())
                self.assertEqual(json.loads(report.read_text())['commandExitStatus'], status)

    def test_timeout_terminates_command_and_closes_session(self):
        closed = []
        @contextlib.contextmanager
        def fake_session(directory):
            try:
                yield Path('/private/credential.json')
            finally:
                closed.append(True)
        with tempfile.TemporaryDirectory() as tmp, patch.object(lifecycle, 'session', fake_session), patch.object(lifecycle.subprocess, 'Popen') as process, patch.object(lifecycle.os, 'killpg') as kill:
            process.return_value.wait.side_effect = [subprocess.TimeoutExpired('test', 1100), 0]
            process.return_value.poll.return_value = None
            self.assertEqual(lifecycle.execute(Path(tmp), ['test'], Path(tmp) / 'report.json'), 1)
            kill.assert_called_once()
            self.assertEqual(closed, [True])

    def test_instrumentation_requires_exact_single_test_success(self):
        output = (f'INSTRUMENTATION_STATUS: class={android.CLASS}\n'
                  f'INSTRUMENTATION_STATUS: test={android.METHOD}\n'
                  'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n'
                  f'INSTRUMENTATION_STATUS: class={android.CLASS}\n'
                  f'INSTRUMENTATION_STATUS: test={android.METHOD}\n'
                  'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n')
        self.assertTrue(android.passed(output))
        for broken in (output.replace('CODE: 0', 'CODE: -2'), output.replace('OK (1 test)', 'OK (0 tests)'),
                       output.replace(android.CLASS, 'another.Class'), output + 'Process crashed.',
                       output.replace('INSTRUMENTATION_CODE: -1', ''), ''):
            self.assertFalse(android.passed(broken))

    def test_standalone_gate_required_and_runs_after_normal_suite(self):
        shell = (ROOT / 'scripts/run-device-qa.sh').read_text()
        self.assertIn('RunnerArguments.class=$CINEMA_CLASSES', shell)
        self.assertIn('grep -Fxv "$CINEMA_OFFICIAL_CLASS"', shell)
        self.assertLess(shell.index(':app:connectedDebugAndroidTest'), shell.index('run_android_test.py'))
        self.assertIn('run_android_test.py || CINEMA_TEST_STATUS=$?', shell)
        self.assertIn('if [ "$CINEMA_TEST_STATUS" -ne 0 ]; then exit "$CINEMA_TEST_STATUS"; fi', shell)
        runner = (ROOT / 'scripts/libretv-contracts/run_android_test.py').read_text()
        self.assertLess(runner.index("adb('install'"), runner.index("input=raw"))
        self.assertIn("'dev.cinematv.test'", runner)

    def test_synthetic_secret_not_in_gradle_arguments_and_only_fixed_fixture(self):
        shell = (ROOT / 'scripts/run-device-qa.sh').read_text()
        self.assertIn('files/official-acceptance.json', shell)
        self.assertNotIn('RunnerArguments.password', shell)
        self.assertEqual(lifecycle.validate_url(lifecycle.FIXTURE), lifecycle.FIXTURE)
        self.assertIn('Only owned emulator host alias allowed', (ROOT / 'app/src/androidTest/java/dev/cinematv/OfficialLibreTvPlaybackAcceptanceTest.kt').read_text())

if __name__ == '__main__':
    unittest.main()
