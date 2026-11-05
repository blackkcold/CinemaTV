#!/usr/bin/env python3
"""Run one official integration after UTP installation, without secret runner arguments."""
import json
import os
from pathlib import Path
import re
import subprocess

CLASS = 'dev.cinematv.OfficialLibreTvPlaybackAcceptanceTest'
METHOD = 'officialAuthenticationSearchDetailDecodePauseResumeAndCheckpoint'


def passed(output):
    # am instrument's process exit code alone does not indicate JUnit success.
    codes = re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', output, re.M)
    return (codes == ['1', '0'] and
            re.search(r'^INSTRUMENTATION_CODE: -1\s*$', output, re.M) is not None and
            re.search(r'^OK \(1 test\)\s*$', output, re.M) is not None and
            output.count('INSTRUMENTATION_STATUS: class=' + CLASS) == 2 and
            output.count('INSTRUMENTATION_STATUS: test=' + METHOD) == 2 and
            'INSTRUMENTATION_STATUS: numtests=1' in output and
            not any(marker in output for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed.', 'INSTRUMENTATION_ABORTED')))


def adb(*args, timeout=30, **kwargs):
    return subprocess.run(['adb', *args], timeout=timeout, check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, **kwargs)


def main():
    evidence = Path('qa/official-android-result.json')
    result = {'result': 'failed', 'class': CLASS, 'scope': 'repository and Media3 with static source fixture'}
    try:
        credential = Path(os.environ['CINEMA_OFFICIAL_CREDENTIAL_FILE'])
        raw = credential.read_bytes()
        if not 1 <= len(raw) <= 4096:
            raise ValueError('Invalid disposable credential file size')
        # UTP may uninstall packages during teardown. Install these exact compiled APKs
        # after it completes, before credential transfer; no install can then clear the file.
        for apk in ('app/build/outputs/apk/debug/app-debug.apk',
                    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
            if not Path(apk).is_file():
                raise ValueError('Missing exact compiled instrumentation APK')
            installed = adb('install', '-r', '-t', apk, timeout=90)
            if b'Success' not in installed.stdout:
                raise ValueError('APK installation was not confirmed')
        for package in ('dev.cinematv', 'dev.cinematv.test'):
            if not adb('shell', 'pm', 'path', package).stdout.startswith(b'package:'):
                raise ValueError('Required installed package absent')
        runners = adb('shell', 'pm', 'list', 'instrumentation').stdout
        if b'dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner' not in runners:
            raise ValueError('Expected instrumentation runner absent')
        adb('shell', 'run-as dev.cinematv mkdir -p files')
        adb('shell', "run-as dev.cinematv sh -c 'cat > files/official-acceptance.json'", input=raw)
        proc = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS,
                               'dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner'],
                              timeout=360, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        output = proc.stdout
        # Auth values never appear in arguments or expected test output. Defense-in-depth redact
        # the synthetic password before storing unexpected instrumentation diagnostics.
        password = json.loads(raw)['password']
        output = output.replace(password, '[redacted]')
        output = re.sub(r'ltv_session=[^\s;]+', 'ltv_session=[redacted]', output)
        Path('qa/official-android-instrumentation.txt').write_text(output)
        result['result'] = 'passed' if proc.returncode == 0 and passed(output) else 'failed'
    except Exception as error:
        result['errorType'] = type(error).__name__
    finally:
        try:
            adb('shell', 'run-as dev.cinematv rm -f files/official-acceptance.json')
        except Exception:
            result['credentialCleanup'] = 'failed; disposable emulator teardown required'
            result['result'] = 'failed'
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(json.dumps(result, indent=2) + '\n')
    return 0 if result['result'] == 'passed' else 1

if __name__ == '__main__':
    raise SystemExit(main())
