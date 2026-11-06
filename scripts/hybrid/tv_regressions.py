#!/usr/bin/env python3
"""Run additional native-session and hybrid audit regressions on the same TV APK."""
import argparse
import json
from pathlib import Path
import sys
from artifacts import require, sha256, verify_device_bundle, write_json
from phone import expected_cases, verify_results


def suites(evidence):
    cases = sorted((evidence / 'hybrid-evidence').glob('*.json'))
    require(cases, 'Primary TV evidence must exist before additional regressions')
    modes = {json.loads(path.read_text()).get('expectedMode') for path in cases
             if 'input-trace' not in path.name and json.loads(path.read_text()).get('method')}
    require(len(modes) == 1 and modes <= {'hybrid', 'native-fallback'}, 'Primary TV cases disagree on presentation')
    selected = {
        'dev.cinematv.playback.NativePlaybackSessionTest': 'playback/NativePlaybackSessionTest.kt',
        'dev.cinematv.SourceSubscriptionsUiTest#nativeTvRemoteCanReadLongPreviewAndFailedRefreshKeepsImportedSources': 'SourceSubscriptionsUiTest.kt',
    }
    if modes == {'hybrid'}:
        selected['dev.cinematv.HybridAuditUiTest'] = 'HybridAuditUiTest.kt'
    return selected


def verify_audit_captures(evidence, completed, apk_hash):
    captures = {}
    for case in completed:
        if not case.startswith('dev.cinematv.HybridAuditUiTest#'):
            continue
        method = case.split('#', 1)[1]
        metadata = evidence / 'audit-evidence' / f'{method}.json'
        image = evidence / 'audit-evidence' / f'{method}.png'
        require(metadata.is_file() and image.is_file(), 'Missing per-case hybrid audit capture')
        data = json.loads(metadata.read_text())
        require(data.get('method') == method and data.get('capture') == method and
                data.get('expectedMode') == 'hybrid' and data.get('webViewPresent') is True and
                data.get('activity') == 'dev.cinematv.MainActivity' and data.get('syntheticContent') is True,
                'Hybrid audit capture does not establish the declared restored production host')
        require(data.get('installedApkSha256') == apk_hash, 'Hybrid audit ran different installed APK bytes')
        require(image.stat().st_size > 1024 and image.read_bytes()[:8] == b'\x89PNG\r\n\x1a\n', 'Invalid hybrid audit PNG')
        captures[method] = {'metadataSha256': sha256(metadata), 'screenshotSha256': sha256(image)}
    return captures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classes', action='store_true')
    parser.add_argument('--bundle', type=Path)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    selected = suites(args.evidence)
    if args.classes:
        print(','.join(selected))
    else:
        root = Path.cwd()
        receipt = verify_device_bundle(root, args.bundle)
        completed = verify_results((args.evidence / 'regressions.txt').read_text(), expected_cases(root, selected))
        captures = verify_audit_captures(args.evidence, completed, receipt['files']['app-debug.apk']['sha256'])
        receipt.update(status='passed-additional-tv-regressions', completedTests=completed,
                       auditEvidence=captures,
                       physicalTvAcceptance=False, signedArtifactAcceptance=False)
        write_json(args.evidence / 'regression-receipt.json', receipt)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        sys.exit(f'TV regression gate: {error}')
