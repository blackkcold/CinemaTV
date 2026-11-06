#!/usr/bin/env python3
"""Aggregate exact evidence; a successful capture is never visual acceptance."""
import argparse
import json
import os
from pathlib import Path
import sys
from manifest import ROOT, digest, environment, now, read, require, source, validate_receipts, verify_inventory, write


def one(directory, filename):
    paths = list(directory.rglob(filename))
    require(len(paths) == 1, f'Expected one {filename}, found {len(paths)}')
    return paths[0]


def validate_browser(folder, identity, builds, capture):
    scope = read(one(folder, 'visual-scope.json'))
    require(scope['sourceCommit'] == identity['commit'], 'Browser scope belongs to another source')
    mode = 'candidate-only-not-approved' if capture else 'reviewed-comparison'
    require(scope['mode'] == mode, 'A capture cannot substitute for reviewed visual acceptance')
    result = read(one(folder, 'results.json'))
    stats = result['stats']
    require(stats['expected'] == 56 and stats['unexpected'] == 0 and stats['flaky'] == 0 and stats['skipped'] == 0,
            'Production browser contracts are incomplete, flaky, skipped or failed')
    require(not result.get('errors'), 'Browser runner errors')
    receipt = read(one(folder, 'web-build-receipt.json'))
    require(receipt['sourceCommit'] == identity['commit'] and receipt['sourceTree'] == identity['tree'], 'Browser production build source mismatch')
    require(all(item['webAssets'] == receipt['webAssets'] for item in builds), 'Browser and Android packaged Web assets differ')
    metadata = [path for path in folder.rglob('*.json') if 'production-captures' in path.parts]
    require(len(metadata) == 52, 'Missing production visual captures')
    environments = set()
    for path in metadata:
        item = read(path)
        require(item['sourceCommit'] == identity['commit'], 'Stale visual capture')
        require(item['sha256'] == digest(path.with_suffix('.png')), 'Visual capture bytes differ')
        environments.add(path.parent.name)
    require(environments == {'css540-dpr2', 'tv720', 'tv1080', 'tv4k'}, 'Missing visual viewport family')
    return {'status': mode, 'executedCases': 56, 'captures': 52, 'source': identity}


def validate_upstream(folder, identity):
    path = one(folder, 'upstream-receipt.json')
    receipt = read(path)
    require(receipt['source'] == identity and receipt['status'] == 'passed-controlled-official-contracts', 'Stale or incomplete upstream evidence')
    verify_inventory(path.parent, receipt['evidence'])
    counts = {}
    for version in ('2.21.1', '2.21.2'):
        counts[version] = {}
        for filename, expected in (('session-contracts.json', 52), ('positive-contracts.json', 29), ('subscription-contracts.json', 13)):
            result = read(path.parent / version / filename)
            require(result['upstreamVersion'] == version, 'Wrong official upstream version')
            checks = result['checks']
            require(len(checks) == expected and len(checks) == len(set(checks)), 'Missing or duplicate upstream checks')
            if filename != 'session-contracts.json': require(result.get('result') == 'passed', 'Upstream suite failed')
            counts[version][filename] = len(checks)
    return counts


def collect(directory, capture=False):
    identity = source()
    plan = read(ROOT / 'scripts/acceptance/plan.json')['lanes']
    needs = json.loads(os.environ['ACCEPTANCE_NEEDS'])
    required = {'source', 'browser', 'build', 'protocol', *plan}
    require(required <= set(needs), 'Missing required workflow dependency')
    unsuccessful = {name: needs[name]['result'] for name in required if needs[name]['result'] != 'success'}
    require(not unsuccessful, f'Workflow jobs were not successful: {unsuccessful}')
    builds = [read(path) for path in directory.rglob('acceptance-build.json')]
    build_hashes = {read(path)['configuration']: digest(path) for path in directory.rglob('acceptance-build.json')}
    receipts = []
    for path in directory.rglob('lane-receipt.json'):
        item = read(path)
        verify_inventory(path.parent, item['evidence'])
        require(item['buildReceiptSha256'] == build_hashes.get(item['configuration']), 'Build receipt identity differs')
        require(item['workflow']['runId'] == os.environ['GITHUB_RUN_ID'], 'Evidence imported from another workflow run')
        receipts.append(item)
    value = validate_receipts(identity, plan, builds, receipts)
    value['browser'] = validate_browser(directory / 'browser', identity, builds, capture)
    value['officialLibreTV'] = validate_upstream(directory / 'upstreams', identity)
    value['workflow'] = environment()
    value['finishedAt'] = now()
    value['status'] = 'source-device-passed-visual-review-pending' if capture else 'passed-source-debug-device-and-reviewed-browser-gates'
    value['signedArtifactAcceptance'] = False
    value['branchOnly'] = True
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = collect(args.directory, os.getenv('CINEMA_VISUAL_CAPTURE') == '1')
    except (ValueError, KeyError, OSError, TypeError) as error:
        result = {'schema': 1, 'source': source(), 'status': 'rejected-incomplete-acceptance',
                  'reason': str(error), 'workflow': environment(), 'publicationAllowed': False,
                  'productionSigned': False, 'branchOnly': True}
        write(args.output, result)
        print(json.dumps(result, indent=2)); return 1
    write(args.output, result)
    print(json.dumps(result, indent=2)); return 0


if __name__ == '__main__': sys.exit(main())
