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


def classify(text, outcome=None):
    # Recovered startup warnings do not make a successful execution a failure.
    # This diagnosis precedes the provenance gate; it is never a pass decision.
    if outcome == 'success':
        return {'category': 'no-execution-failure', 'automaticRetryAllowed': False}
    if outcome == 'cancelled':
        return {'category': 'cancelled-incomplete', 'automaticRetryAllowed': False}
    if any(item in text for item in ('FAILURES!!!', 'AssertionError', 'ComparisonFailure', 'assertExists')):
        return {'category': 'test-or-application-failure', 'automaticRetryAllowed': False}
    if any(item in text for item in ('FATAL EXCEPTION', 'ANR in dev.cinematv', 'Application Not Responding:')):
        return {'category': 'application-or-window-failure', 'automaticRetryAllowed': False}
    if any(item in text for item in ('pixel comparison failed', 'Screenshot comparison failed')):
        return {'category': 'visual-comparison-failure', 'automaticRetryAllowed': False}
    if any(item in text.lower() for item in ('out of memory: killed process', 'oom-kill', 'killed process')):
        return {'category': 'host-memory-pressure-evidence', 'automaticRetryAllowed': False,
                'note': 'One fresh-runner retry may be requested after reviewing original evidence.'}
    if any(item in text for item in ('device offline', 'no emulators found', 'Emulator exited before', 'Timed out waiting')):
        return {'category': 'environment-or-startup-failure', 'automaticRetryAllowed': False,
                'note': 'Not proof of a unique root cause; preserve this attempt before one reviewed retry.'}
    return {'category': 'unclassified-failure' if outcome == 'failure' else 'unclassified-or-no-failure',
            'automaticRetryAllowed': False}


def failure_text(directories, limit=64, tail_bytes=60000):
    """Bounded tails, including live kernel evidence; never follow a symlink."""
    parts, included = [], []
    for directory in directories:
        if not directory.is_dir() or directory.is_symlink(): continue
        for path in sorted(directory.rglob('*')):
            if len(included) >= limit: return '\n'.join(parts), included
            if path.is_symlink() or not path.is_file(): continue
            if any(parent.is_symlink() for parent in path.parents if parent != directory.parent): continue
            if path.suffix not in ('.txt', '.log') and path.name != 'live-environment.json': continue
            with path.open('rb') as stream:
                stream.seek(max(0, path.stat().st_size - tail_bytes))
                text = stream.read(tail_bytes).decode('utf-8', errors='replace')
            parts.append(text)
            included.append(str(path.relative_to(directory)))
    return '\n'.join(parts), included


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lane', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--outcome', choices=('success', 'failure', 'cancelled'), required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.getenv('ANDROID_HOME', '/nonexistent'))
    config = json.loads((ROOT / 'scripts/acceptance/plan.json').read_text())['lanes'][args.lane]
    data = {'schema': 1, 'capturedAt': now(), 'lane': args.lane, 'scope': config['scope'],
            'executionOutcomeBeforeReceipt': args.outcome,
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
    combined, included = failure_text((args.evidence, startup))
    data['failureEvidenceFiles'] = included
    data['failureClassification'] = classify(combined, args.outcome)
    write(args.evidence / 'environment.json', data)


if __name__ == '__main__': main()
