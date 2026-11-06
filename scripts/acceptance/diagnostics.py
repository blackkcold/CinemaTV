#!/usr/bin/env python3
"""Record bounded environment diagnostics; never infer a business failure is flaky."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from manifest import ROOT, environment, now, write


def capture(command, budget=8):
    try:
        value = subprocess.run(command, timeout=budget, capture_output=True, text=True)
        return {'exitCode': value.returncode, 'output': (value.stdout + value.stderr)[-60000:]}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'error': type(error).__name__}


def classify(text):
    if any(item in text for item in ('FAILURES!!!', 'AssertionError', 'ComparisonFailure', 'assertExists')):
        return {'category': 'test-or-application-failure', 'automaticRetryAllowed': False}
    if any(item in text.lower() for item in ('out of memory: killed process', 'oom-kill', 'killed process')):
        return {'category': 'host-memory-pressure-evidence', 'automaticRetryAllowed': False,
                'note': 'One fresh-runner retry may be requested after reviewing original evidence.'}
    if any(item in text for item in ('device offline', 'no emulators found', 'Emulator exited before', 'Timed out waiting')):
        return {'category': 'environment-or-startup-failure', 'automaticRetryAllowed': False,
                'note': 'Not proof of a unique root cause; preserve this attempt before one reviewed retry.'}
    return {'category': 'unclassified-or-no-failure', 'automaticRetryAllowed': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lane', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.getenv('ANDROID_HOME', '/nonexistent'))
    config = json.loads((ROOT / 'scripts/acceptance/plan.json').read_text())['lanes'][args.lane]
    data = {'schema': 1, 'capturedAt': now(), 'lane': args.lane, 'scope': config['scope'],
            'workflow': environment(), 'hostMemory': capture(['free', '-m']),
            'processes': capture(['ps', '-eo', 'pid=,ppid=,stat=,comm=']),
            'softwareRenderingNotHardwareCertification': True, 'systemImages': {}}
    for path in sorted((sdk / 'system-images').glob('*/*/*/source.properties')):
        data['systemImages'][str(path.relative_to(sdk))] = path.read_text()
    for name in ('device.txt', 'webview-provider.txt', 'installed-apks.json'):
        path = args.evidence / name
        if path.is_file(): data[name] = path.read_text()[-60000:]
    startup = Path(str(args.evidence) + '-emulator')
    for name in ('version.txt', 'config.txt', 'avd-config.ini', 'max-ui-width-at-boot.txt', 'status.txt'):
        path = startup / name
        if path.is_file(): data['tv-' + name] = path.read_text()[-60000:]
    combined = '\n'.join(path.read_text(errors='replace')[-60000:] for directory in (args.evidence, startup)
                         if directory.is_dir() for path in directory.glob('*.txt'))
    data['failureClassification'] = classify(combined)
    write(args.evidence / 'environment.json', data)


if __name__ == '__main__': main()
