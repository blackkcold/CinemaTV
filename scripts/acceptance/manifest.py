#!/usr/bin/env python3
"""Exact-source acceptance receipts. No signing, merging or publication authority."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SHA = re.compile(r'[0-9a-f]{64}')


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x') as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write('\n')


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def source():
    current = git('rev-parse', 'HEAD')
    require(current == os.getenv('EXPECTED_SOURCE_SHA', current), 'Unexpected checkout commit')
    require(not git('diff', '--name-only', 'HEAD'), 'Tracked source was modified during acceptance')
    return {'commit': current, 'tree': git('rev-parse', 'HEAD^{tree}')}


def now():
    return datetime.now(timezone.utc).isoformat()


def timestamp(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.utcoffset() is not None, 'Timestamp must include timezone')
    return result


def environment():
    return {key: os.getenv(variable, 'local') for key, variable in (
        ('repository', 'GITHUB_REPOSITORY'), ('runId', 'GITHUB_RUN_ID'),
        ('attempt', 'GITHUB_RUN_ATTEMPT'), ('job', 'GITHUB_JOB'),
        ('runnerOS', 'RUNNER_OS'), ('runnerImage', 'ImageOS'), ('runnerImageVersion', 'ImageVersion'))}


def safe_file(root, relative):
    require(isinstance(relative, str) and relative and '\\' not in relative, 'Invalid evidence path')
    path = Path(relative)
    require(not path.is_absolute() and '..' not in path.parts, 'Evidence path escapes its artifact')
    target = root / path
    require(not any(part.is_symlink() for part in (target, *target.parents) if part != root.parent), 'Evidence symlink')
    require(target.is_file(), f'Missing evidence: {relative}')
    require(target.resolve().is_relative_to(root.resolve()), 'Evidence escapes artifact')
    return target


def inventory(folder, excluded=()):
    result = {}
    for path in sorted(Path(folder).rglob('*')):
        require(not path.is_symlink(), 'Evidence must not contain symlinks')
        if path.is_file() and path.name not in excluded:
            result[path.relative_to(folder).as_posix()] = {'sha256': digest(path), 'bytes': path.stat().st_size}
    require(result, 'Empty evidence inventory')
    return result


def verify_inventory(folder, files):
    require(isinstance(files, dict) and files, 'Missing evidence inventory')
    for relative, metadata in files.items():
        require(isinstance(metadata, dict) and SHA.fullmatch(metadata.get('sha256', '')), 'Invalid digest')
        path = safe_file(folder, relative)
        require(type(metadata.get('bytes')) is int and path.stat().st_size == metadata['bytes'], 'Evidence size differs')
        require(digest(path) == metadata['sha256'], f'Evidence digest differs: {relative}')


def build_receipt(bundle, configuration, output):
    require(configuration in ('preview-debug', 'dev-debug'), 'Unknown build configuration')
    legacy = read(bundle / 'build-receipt.json')
    identity = source()
    require(legacy['sourceCommit'] == identity['commit'] and legacy['sourceTree'] == identity['tree'], 'Build source mismatch')
    require(set(legacy['files']) == {'app-debug.apk', 'app-debug-androidTest.apk'}, 'Incomplete APK bundle')
    verify_inventory(bundle, legacy['files'])
    result = {'schema': 1, 'kind': 'build', 'source': identity, 'configuration': configuration,
              'workflow': environment(), 'files': legacy['files'], 'webAssets': legacy['webAssets'],
              'productionSigned': False, 'publicationAllowed': False}
    write(output, result)
    return result


def record(lane, bundle, folder, started, results, output):
    plan = read(ROOT / 'scripts/acceptance/plan.json')['lanes']
    require(lane in plan, 'Unknown acceptance lane')
    build = read(bundle / 'acceptance-build.json')
    require(build['source'] == source(), 'Device and build source differ')
    require(build['configuration'] == plan[lane]['configuration'], 'Wrong APK configuration for lane')
    verify_inventory(bundle, build['files'])
    cases = []
    for relative in results:
        result = read(safe_file(folder, relative))
        require(result.get('status') in ('passed-synthetic-device-tests', 'passed-additional-tv-regressions',
                                        'passed-synthetic-phone-tests', 'passed-native-device-tests'), 'Unaccepted result status')
        require(result.get('sourceCommit') == build['source']['commit'] and result.get('sourceTree') == build['source']['tree'], 'Result is from another source')
        require(result.get('files') == build['files'], 'Result is from another APK')
        executed = result.get('completedTests')
        require(isinstance(executed, list) and executed and all(isinstance(x, str) and x for x in executed), 'No executed cases')
        cases.extend(executed)
    require(len(cases) == len(set(cases)), 'Duplicate executed cases within lane')
    require(len(cases) >= plan[lane]['minimumCases'], 'Required lane coverage was reduced')
    ended = now()
    require(timestamp(started) <= timestamp(ended), 'Negative device interval')
    require((folder / 'environment.json').is_file(), 'Missing device environment evidence')
    data = {'schema': 1, 'kind': 'device', 'lane': lane, 'source': build['source'],
            'configuration': build['configuration'], 'apkSha256': build['files']['app-debug.apk']['sha256'],
            'testApkSha256': build['files']['app-debug-androidTest.apk']['sha256'],
            'buildReceiptSha256': digest(bundle / 'acceptance-build.json'), 'workflow': environment(),
            'startedAt': started, 'finishedAt': ended, 'status': 'passed', 'completedTests': cases,
            'scope': plan[lane]['scope'], 'results': results,
            'evidence': inventory(folder, excluded=(Path(output).name,)),
            'physicalDeviceCertified': False, 'productionSigned': False, 'publicationAllowed': False}
    write(output, data)
    return data


def validate_receipts(identity, plan, builds, receipts):
    require(len(builds) == len({x['configuration'] for x in builds}), 'Duplicate build receipts')
    configurations = {x['configuration']: x for x in builds}
    require(set(configurations) == {'preview-debug', 'dev-debug'}, 'Missing build configuration')
    for build in builds:
        require(build.get('schema') == 1 and build.get('kind') == 'build' and build['source'] == identity, 'Invalid build provenance')
    require(len(receipts) == len({x['lane'] for x in receipts}), 'Duplicate lane receipts')
    require({x['lane'] for x in receipts} == set(plan), 'Required device lane missing or unexpected')
    intervals = []
    for item in receipts:
        lane = plan[item['lane']]
        require(item.get('schema') == 1 and item.get('kind') == 'device', 'Invalid lane receipt')
        require(item['source'] == identity and item['status'] == 'passed', 'Lane failed or belongs to another source')
        require(item['configuration'] == lane['configuration'], 'Lane build configuration mismatch')
        build = configurations[item['configuration']]
        for field, name in (('apkSha256', 'app-debug.apk'), ('testApkSha256', 'app-debug-androidTest.apk')):
            require(item[field] == build['files'][name]['sha256'], 'Different APK bytes were tested')
        cases = item['completedTests']
        require(len(cases) == len(set(cases)) and len(cases) >= lane['minimumCases'], 'Incomplete or duplicate coverage')
        start, end = timestamp(item['startedAt']), timestamp(item['finishedAt'])
        require(start < end, 'Invalid device interval')
        intervals.append((start, end, item['lane']))
    intervals.sort()
    for previous, following in zip(intervals, intervals[1:]):
        require(previous[1] <= following[0], f'Emulators overlapped: {previous[2]} / {following[2]}')
    require([x[2] for x in intervals] == list(plan), 'Device sequence differs from approved order')
    return {'schema': 1, 'source': identity, 'status': 'passed-source-and-debug-device-gates',
            'lanes': [x['lane'] for x in receipts], 'deviceOrder': [x[2] for x in intervals],
            'countsArePerLaneNotUniqueCoverage': True, 'productionSigned': False,
            'physicalDeviceCertified': False, 'publicationAllowed': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='command', required=True)
    start = subs.add_parser('begin'); start.add_argument('--output', type=Path, required=True)
    build = subs.add_parser('build')
    build.add_argument('--bundle', type=Path, required=True); build.add_argument('--configuration', required=True)
    build.add_argument('--output', type=Path, required=True)
    lane = subs.add_parser('record')
    lane.add_argument('--lane', required=True); lane.add_argument('--bundle', type=Path, required=True)
    lane.add_argument('--evidence', type=Path, required=True); lane.add_argument('--started', type=Path, required=True)
    lane.add_argument('--result', action='append', required=True); lane.add_argument('--output', type=Path, required=True)
    collect = subs.add_parser('collect')
    collect.add_argument('--directory', type=Path, required=True); collect.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'begin':
        write(args.output, {'startedAt': now(), 'source': source(), 'workflow': environment()})
    elif args.command == 'build':
        build_receipt(args.bundle, args.configuration, args.output)
    elif args.command == 'record':
        start = read(args.started)
        require(start['source'] == source(), 'Lane started on another source')
        record(args.lane, args.bundle, args.evidence, start['startedAt'], args.result, args.output)
    else:
        builds = []
        for path in args.directory.rglob('acceptance-build.json'):
            builds.append(read(path))
        receipts = []
        for path in args.directory.rglob('lane-receipt.json'):
            item = read(path)
            verify_inventory(path.parent, item['evidence'])
            receipts.append(item)
        value = validate_receipts(source(), read(ROOT / 'scripts/acceptance/plan.json')['lanes'], builds, receipts)
        write(args.output, value)
        print(json.dumps(value, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, TypeError) as error:
        sys.exit(f'Acceptance gate: {error}')
