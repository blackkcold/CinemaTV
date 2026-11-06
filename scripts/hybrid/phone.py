#!/usr/bin/env python3
"""Verify the exact APK phone regression suite, including repository fixtures."""
import argparse
import json
from pathlib import Path
import re
import struct
import sys
from artifacts import require, sha256, verify_device_bundle, write_json

SUITES = {
    'dev.cinematv.MobileNavigationSmokeTest': 'MobileNavigationSmokeTest.kt',
    'dev.cinematv.MobilePlaybackSmokeTest': 'MobilePlaybackSmokeTest.kt',
    'dev.cinematv.MobileChannelIdentityTest': 'MobileChannelIdentityTest.kt',
    'dev.cinematv.MobilePlaybackIntentTest': 'MobilePlaybackIntentTest.kt',
    'dev.cinematv.SourceSubscriptionsUiTest#phoneCanPreviewImportRefreshAndStopWithoutLosingDisabledSources': 'SourceSubscriptionsUiTest.kt',
    'dev.cinematv.data.LibreTvProtocolTest': 'data/LibreTvProtocolTest.kt',
    'dev.cinematv.playback.NativePlaybackSessionTest': 'playback/NativePlaybackSessionTest.kt',
}


def expected_cases(root, selected=None):
    result = set()
    for selector, filename in (SUITES if selected is None else selected).items():
        name, _, method = selector.partition('#')
        source = (root / 'app/src/androidTest/java/dev/cinematv' / filename).read_text()
        methods = re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)', source)
        require(methods, f'No declared regression methods in {name}')
        if method:
            require(method in methods, f'Unknown declared regression {selector}')
            methods = [method]
        result.update(f'{name}#{method}' for method in methods)
    return result


def verify_results(log, expected):
    require(not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED', log),
            'Phone instrumentation reported failure')
    completed, case = [], {}
    for line in log.splitlines():
        match = re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
        if match:
            case[match[1]] = match[2].strip()
        status = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if status:
            code = int(status[1])
            require(code in (0, 1, 2), 'Phone instrumentation failed or skipped a case')
            if code == 0:
                completed.append(f'{case.get("class")}#{case.get("test")}')
            case = {}
    require(len(completed) == len(set(completed)) and set(completed) == expected,
            'Phone instrumentation did not execute every declared regression case')
    count = re.search(r'OK \((\d+) tests?\)', log)
    require(count and int(count.group(1)) == len(expected), 'Phone success count differs from declared cases')
    require(re.search(r'INSTRUMENTATION_CODE: -1(?:\s|$)', log), 'Phone instrumentation did not finish')
    return completed


def verify_phone_captures(evidence):
    screenshots = sorted((evidence / 'ui-evidence').glob('*.png'))
    required = {'mobile-player-portrait.png', 'mobile-player-landscape.png'}
    require(required <= {path.name for path in screenshots}, 'Phone portrait or landscape playback capture missing')
    for path in screenshots:
        content = path.read_bytes()
        require(len(content) > 1024 and content[:8] == b'\x89PNG\r\n\x1a\n' and content[12:16] == b'IHDR', 'Invalid phone screenshot')
        width, height = struct.unpack('>II', content[16:24])
        require(width > 0 and height > 0, 'Empty phone screenshot')
        if path.name in required:
            portrait = path.name == 'mobile-player-portrait.png'
            require((height > width) if portrait else (width > height), 'Phone capture orientation does not match its case')
    return {path.name: sha256(path) for path in screenshots}


def result(root, bundle, evidence):
    receipt = verify_device_bundle(root, bundle)
    completed = verify_results((evidence / 'instrumentation.txt').read_text(), expected_cases(root))
    screenshots = verify_phone_captures(evidence)
    receipt.update(status='passed-synthetic-phone-tests', completedTests=completed,
                   screenshots=screenshots,
                   deviceMetadata=(evidence / 'device.txt').read_text(),
                   physicalTvAcceptance=False, signedArtifactAcceptance=False)
    write_json(evidence / 'phone-receipt.json', receipt)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classes', action='store_true')
    parser.add_argument('--bundle', type=Path)
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    if args.classes:
        print(','.join(SUITES))
    else:
        try:
            result(Path.cwd(), args.bundle, args.evidence)
        except (ValueError, OSError, KeyError) as error:
            sys.exit(f'Phone artifact gate: {error}')
