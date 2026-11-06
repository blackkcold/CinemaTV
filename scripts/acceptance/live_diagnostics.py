#!/usr/bin/env python3
"""Capture bounded diagnostics before the emulator action tears its guest down."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from diagnostics import capture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--exit-code', type=int, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', os.getenv('ANDROID_SERIAL', 'emulator-5554')]
    data = {'originalExitCode': args.exit_code, 'hostMemory': capture(['free', '-m']),
            'adbState': capture(adb + ['get-state']), 'device': capture(adb + ['shell', 'getprop', 'ro.build.fingerprint']),
            'size': capture(adb + ['shell', 'wm', 'size']), 'density': capture(adb + ['shell', 'wm', 'density']),
            'webView': capture(adb + ['shell', 'dumpsys', 'webviewupdate'])}
    if args.exit_code:
        for name, command in (('window', ['shell', 'dumpsys', 'window']),
                              ('activity', ['shell', 'dumpsys', 'activity', 'top']),
                              ('memory', ['shell', 'dumpsys', 'meminfo', 'dev.cinematv']),
                              ('testLog', ['logcat', '-d', '-t', '1500', '-v', 'threadtime', 'TestRunner:I', 'CinemaTVQa:I', 'AndroidRuntime:E', '*:S'])):
            data[name] = capture(adb + command)
        data['kernel'] = capture(['sudo', '-n', 'dmesg', '-T'])
        screenshot = args.evidence / 'failure-compositor.png'
        with screenshot.open('wb') as stream:
            try:
                result = subprocess.run(adb + ['exec-out', 'screencap', '-p'], stdout=stream, stderr=subprocess.DEVNULL, timeout=10)
                if result.returncode: screenshot.unlink(missing_ok=True)
            except (OSError, subprocess.TimeoutExpired): screenshot.unlink(missing_ok=True)
    (args.evidence / 'live-environment.json').write_text(json.dumps(data, indent=2) + '\n')


if __name__ == '__main__': main()
