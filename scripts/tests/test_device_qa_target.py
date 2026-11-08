"""Exercise the action's line-by-line sh contract without allocating an emulator.

Verified against upstream @v2 src/script-parser.ts (newline split) and
src/main.ts (each fragment executed using sh -c), 2026-11-04:
https://github.com/ReactiveCircus/android-emulator-runner/blob/v2/src/script-parser.ts
https://github.com/ReactiveCircus/android-emulator-runner/blob/v2/src/main.ts
No upstream implementation source is copied.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DeviceQaTargetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'scripts/libretv-contracts').mkdir(parents=True)
        shutil.copyfile(ROOT / 'scripts/run-device-qa-target.sh', self.root / 'scripts/run-device-qa-target.sh')
        (self.root / 'scripts/run-device-qa.sh').write_text(
            '#!/usr/bin/env bash\nprintf "%s\\n" normal "$@" > "$CAPTURE"\nexit "$QA_STATUS"\n')
        (self.root / 'scripts/libretv-contracts/android_acceptance.py').write_text(
            'import os, pathlib, sys\n'
            'pathlib.Path(os.environ["CAPTURE"]).write_text("\\n".join(["official"] + sys.argv[1:]) + "\\n")\n'
            'sys.exit(int(os.environ["QA_STATUS"]))\n')
        self.capture = self.root / 'capture'
        self.env = dict(os.environ, RUNNER_TEMP=str(self.root / 'runner temp'), CAPTURE=str(self.capture), QA_STATUS='0')

    def workflow_command(self):
        workflow = (ROOT / '.github/workflows/tv-smoke.yml').read_text()
        # Require a literal single-line scalar, never |, >, or multiline shell control flow.
        action = workflow.split('uses: reactivecircus/android-emulator-runner@v2', 1)[1]
        action = action.split('\n      - ', 1)[0]
        matches = re.findall(r'^          script: (.+)$', action, re.M)
        self.assertEqual(len(matches), 1)
        command = matches[0]
        self.assertEqual(command, "bash scripts/run-device-qa-target.sh '${{ matrix.name }}' '${{ matrix.size }}' '${{ matrix.density }}' '${{ matrix.classes }}'")
        self.assertNotRegex(action, re.compile(r'^            (if |else\s*$|fi\s*$)', re.M))
        return command

    def invoke(self, target, status=0):
        values = dict(name=target, size='1920x1080', density='320', classes='dev.cinematv.One,dev.cinematv.Two#method')
        command = self.workflow_command()
        for key, value in values.items():
            command = command.replace('${{ matrix.' + key + ' }}', value)
        # This is the action's actual outer shell, NOT bash evaluating a whole YAML block.
        return subprocess.run(['/bin/sh', '-c', command], cwd=self.root,
                              env=dict(self.env, QA_STATUS=str(status)), capture_output=True, text=True, timeout=5)

    def test_all_targets_through_actual_action_shell(self):
        for target in ('phone', 'tv1080', 'tv4k', 'tvNative', 'tv29', 'tv31', 'tv36', 'compat26'):
            with self.subTest(target=target):
                result = self.invoke(target)
                self.assertEqual(result.returncode, 0, result.stderr)
                args = self.capture.read_text().splitlines()
                self.assertEqual(args[-3:], ['1920x1080', '320', 'dev.cinematv.One,dev.cinematv.Two#method'])
                if target in ('phone', 'tv1080'):
                    self.assertEqual(args[:-3], ['official', '--directory', str(self.root / 'runner temp/official-libretv-android'), '--report', 'qa/official-lifecycle.json', '--', 'bash', 'scripts/run-device-qa.sh'])
                else:
                    self.assertEqual(args[:-3], ['normal'])

    def test_dispatch_errors_preserve_failure_for_both_paths(self):
        for target in ('phone', 'tv1080', 'tv4k', 'tvNative', 'tv29', 'tv31', 'tv36', 'compat26'):
            with self.subTest(target=target):
                self.assertEqual(self.invoke(target, status=17).returncode, 17)

    def test_unknown_target_and_missing_arguments_fail_before_dispatch(self):
        self.assertEqual(self.invoke('unknown').returncode, 2)
        self.assertFalse(self.capture.exists())
        result = subprocess.run(['bash', 'scripts/run-device-qa-target.sh'], cwd=self.root,
                                env=self.env, capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.capture.exists())

    def test_bash_syntax(self):
        subprocess.run(['bash', '-n', str(ROOT / 'scripts/run-device-qa-target.sh')], check=True, timeout=5)

if __name__ == '__main__':
    unittest.main()
