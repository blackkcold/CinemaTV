#!/usr/bin/env python3
"""Promote only full-matrix debug prereleases from an identical Git tree."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET

REPO = 'blackkcold/CinemaTV'
VERSION = r'[0-9]+\.[0-9]+\.[0-9]+-(?:preview|rc)(?:\.[0-9]+)?'
MARKER = rf'\[promote-preview run=([1-9][0-9]*) version=({VERSION})\]'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path):
    return json.loads(command('gh', 'api', f'repos/{REPO}/{path}'))


def version():
    return re.search(r'versionName = "([^"]+)"', Path('app/build.gradle.kts').read_text())[1]


def hashes(path):
    # Debug keys differ between runners; compare all ZIP payload except signing
    # metadata too. ZIP parsing excludes the APK v2/v3 signing block itself.
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate APK path')
        digest = hashlib.sha256()
        for name in sorted(names):
            if re.fullmatch(r'META-INF/[^/]+\.(?:RSA|DSA|EC|SF|MF)', name, re.I):
                continue
            data = archive.read(name)
            digest.update(name.encode() + b'\0' + str(len(data)).encode() + b'\0' + data)
    return hashlib.sha256(path.read_bytes()).hexdigest(), digest.hexdigest()


def receipt(args):
    # Generated ignored build outputs are expected; tracked source edits are not.
    command('git', 'diff', '--exit-code', 'HEAD', '--')
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    source = event.get('pull_request', {}).get('head', {})
    sha, payload = hashes(Path('app/build/outputs/apk/debug/app-debug.apk'))
    result = dict(schema=1, repository=os.environ['GITHUB_REPOSITORY'],
                  source_repository=source.get('repo', {}).get('full_name', os.environ['GITHUB_REPOSITORY']),
                  run_id=os.environ['GITHUB_RUN_ID'], attempt=os.environ['GITHUB_RUN_ATTEMPT'],
                  source_head=source.get('sha', os.environ['GITHUB_SHA']),
                  checkout_sha=command('git', 'rev-parse', 'HEAD'),
                  checkout_tree=command('git', 'rev-parse', 'HEAD^{tree}'),
                  version=version(), device=args.device,
                  classes=args.classes.split(',') if args.classes else [],
                  apk_sha256=sha, apk_payload_sha256=payload)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(result, indent=2) + '\n')


def matrix():
    spec = importlib.util.spec_from_file_location('device_matrix', 'scripts/qa-device-matrix.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return {entry['name']: entry['classes'].split(',') for entry in module.DEVICES}


def validate(run, jobs, tree, requested, receipts, classes, payload, sha):
    require(run['repository']['full_name'] == REPO and run['head_repository']['full_name'] == REPO, 'Foreign repository')
    require(run['path'] == '.github/workflows/android.yml' and run['name'] == 'Android TV APK', 'Wrong workflow')
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'Source run not successful')
    require(run['event'] in ('push', 'pull_request', 'workflow_dispatch'), 'Unsupported source event')
    require(run['run_attempt'] == 1, 'Reruns are not supported')
    age = datetime.now(timezone.utc) - datetime.fromisoformat(run['updated_at'].replace('Z', '+00:00'))
    require(timedelta(0) <= age <= timedelta(days=14), 'Source run is stale or future-dated')
    require(re.fullmatch(VERSION, requested), 'Not a prerelease')
    require(set(classes) == {'phone', 'tv1080', 'tv4k', 'compat26'}, 'Not the full matrix')
    for name in ['apk'] + [f'ui-qa / UI / {device}' for device in classes]:
        matches = [job for job in jobs if job['name'] == name]
        require(len(matches) == 1 and matches[0]['conclusion'] == 'success' and matches[0]['status'] == 'completed', f'Missing successful job: {name}')
    require(set(receipts) == {'apk', *classes}, 'Incomplete receipts')
    for device, value in receipts.items():
        require(value['schema'] == 1 and value['repository'] == REPO and value['source_repository'] == REPO, 'Bad receipt origin')
        require(value['run_id'] == str(run['id']) and value['attempt'] == '1', 'Receipt run mismatch')
        require(value['source_head'] == run['head_sha'], 'Receipt source mismatch')
        require(value['checkout_tree'] == tree, 'Tested tree differs from main')
        require(value['version'] == requested and value['device'] == device, 'Receipt version/device mismatch')
        require(value['classes'] == classes.get(device, []), 'Required test classes differ')
        require(value['apk_payload_sha256'] == payload, 'Device APK payload differs')
        require(re.fullmatch('[0-9a-f]{64}', value['apk_sha256']), 'Bad APK hash')
    require(receipts['apk']['apk_sha256'] == sha, 'Published APK digest mismatch')
    require(len({v['checkout_sha'] for v in receipts.values()}) == 1, 'Mixed checkout commits')


def validate_checkout(run, tested, commit, tree):
    require(commit['sha'] == tested and commit['tree']['sha'] == tree, 'Checkout tree not verified by GitHub')
    if run['event'] == 'pull_request':
        parents = commit['parents']
        require(len(parents) == 2 and parents[1]['sha'] == run['head_sha'], 'PR checkout is not the tested source merge')
    else:
        require(tested == run['head_sha'], 'Checkout is not source run head')


def validate_device_evidence(root, selectors):
    cases = []
    for path in (root / 'app/build/outputs/androidTest-results').rglob('*.xml'):
        cases.extend(ET.parse(path).getroot().iter('testcase'))
    require(cases, 'No executed instrumentation results')
    require(not any(case.find('failure') is not None or case.find('error') is not None for case in cases), 'Failed instrumentation case')
    for selector in selectors:
        name, _, method = selector.partition('#')
        if name == 'dev.cinematv.OfficialLibreTvPlaybackAcceptanceTest':
            evidence = root / 'qa'
            result = json.loads((evidence / 'official-android-result.json').read_text())
            lifecycle = json.loads((evidence / 'official-lifecycle.json').read_text())
            verification = json.loads((evidence / 'official-artifact-verification.json').read_text())
            require(result['result'] == 'passed' and result['class'] == name, 'Official Android test not passed')
            require(lifecycle['result'] == 'passed' and lifecycle['commandExitStatus'] == 0, 'Official lifecycle not passed')
            require(verification['result'] == 'passed', 'Official artifacts not verified')
            require(lifecycle['archiveSha256'] == verification['libreTvArchiveSha256'], 'Official artifact mismatch')
            spec = importlib.util.spec_from_file_location('official_runner', 'scripts/libretv-contracts/run_android_test.py')
            runner = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(runner)
            require(runner.passed((evidence / 'official-android-instrumentation.txt').read_text()), 'Official raw instrumentation not passed')
        else:
            selected = [case for case in cases if case.get('classname') == name and (not method or case.get('name') == method)]
            require(selected and all(case.find('skipped') is None for case in selected), f'Missing/skipped required class: {selector}')


def extract(archive, destination):
    require(sum(i.file_size for i in archive.infolist()) <= 1024 * 1024 * 1024, 'Oversized ZIP')
    require(len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate artifact path')
    for info in archive.infolist():
        canonical = Path(info.filename).as_posix() + ('/' if info.is_dir() else '')
        require(info.filename == canonical and '..' not in Path(info.filename).parts and '\\' not in info.filename, 'Noncanonical artifact path')
        require((destination / info.filename).resolve().is_relative_to(destination.resolve()), 'Unsafe artifact path')
        require((info.external_attr >> 16) & 0o170000 != 0o120000, 'Artifact symlink')
    archive.extractall(destination)


def download(artifact, destination):
    require(not artifact['expired'] and artifact['size_in_bytes'] <= 512 * 1024 * 1024, 'Expired/oversized artifact')
    with tempfile.TemporaryFile() as stream:
        subprocess.run(['gh', 'api', f"repos/{REPO}/actions/artifacts/{int(artifact['id'])}/zip"], stdout=stream, check=True)
        require(stream.tell() == artifact['size_in_bytes'], 'Artifact archive size mismatch')
        stream.seek(0)
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        require(artifact.get('digest') == 'sha256:' + digest, 'GitHub artifact digest missing/mismatch')
        stream.seek(0)
        with zipfile.ZipFile(stream) as archive:
            extract(archive, destination)


def promote(args):
    require(os.environ.get('GITHUB_REPOSITORY') == REPO and os.environ.get('GITHUB_EVENT_NAME') == 'push' and os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Requires fixed repository main push')
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    message = event['head_commit']['message']
    markers = re.findall(MARKER, message)
    require(len(markers) == 1 and message.count('[promote-preview') == 1, 'Exactly one valid promotion marker required')
    require('[preview-release]' not in message and '[ui-qa]' not in message, 'Conflicting markers')
    run_id, requested = markers[0]
    current = command('git', 'rev-parse', 'HEAD')
    require(current == os.environ['GITHUB_SHA'] == event['after'], 'Unexpected checkout')
    require(api('git/ref/heads/main')['object']['sha'] == current, 'Main moved')
    tree = command('git', 'rev-parse', 'HEAD^{tree}')
    require(version() == requested, 'Requested version differs from main')
    notes = Path(f'docs/releases/v{requested}.md').read_text()
    run = api(f'actions/runs/{run_id}')
    jobs = api(f'actions/runs/{run_id}/attempts/1/jobs?per_page=100')
    artifacts = api(f'actions/runs/{run_id}/artifacts?per_page=100')
    require(jobs['total_count'] <= 100 and artifacts['total_count'] <= 100, 'Pagination exceeds supported limit')
    classes = matrix()
    output = Path(args.output)
    require(not output.exists(), 'Output already exists')
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        for name in ['CinemaTV-debug-apk', 'preview-build-receipt'] + [f'ui-evidence-{d}' for d in classes]:
            matches = [a for a in artifacts['artifacts'] if a['name'] == name]
            require(len(matches) == 1, f'Missing/duplicate artifact {name}')
            download(matches[0], root / name)
        apk = root / 'CinemaTV-debug-apk/app-debug.apk'
        sha, payload = hashes(apk)
        receipts = {'apk': json.loads((root / 'preview-build-receipt/promotion-receipt.json').read_text())}
        for device in classes:
            receipts[device] = json.loads((root / f'ui-evidence-{device}/qa/promotion-receipt.json').read_text())
        for device, selectors in classes.items():
            validate_device_evidence(root / f'ui-evidence-{device}', selectors)
        validate(run, jobs['jobs'], tree, requested, receipts, classes, payload, sha)
        tested = receipts['apk']['checkout_sha']
        require(re.fullmatch('[0-9a-f]{40}', tested), 'Invalid checkout SHA')
        validate_checkout(run, tested, api(f'git/commits/{tested}'), tree)
        tag = 'v' + requested
        require(not api(f'git/matching-refs/tags/{tag}'), 'Tag or tag prefix exists')
        releases = json.loads(command('gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100'))
        require(not any(r['tag_name'] == tag for page in releases for r in page), 'Release exists')
        (output / f'CinemaTV-{tag}.apk').write_bytes(apk.read_bytes())
        with zipfile.ZipFile(output / f'CinemaTV-{tag}-ui-evidence.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
            for device in classes:
                for path in sorted((root / f'ui-evidence-{device}').rglob('*')):
                    if path.is_file():
                        archive.write(path, path.relative_to(root))
        provenance = dict(source_run=run['html_url'], source_head=run['head_sha'], tested_checkout=tested,
                          tested_tree=tree, released_main=current, version=requested, receipts=receipts,
                          debug_signed=True, same_unsigned_apk_payload=True)
        (output / 'promotion-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
        (output / 'SHA256SUMS.txt').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(output.iterdir())))
        (output / 'release-notes.md').write_text(notes + f'\n\nPromoted debug-signed prerelease, not production-signed.\nTested checkout: `{tested}`\nReleased main: `{current}`\nIdentical Git tree: `{tree}`\nSource: {run["html_url"]}\nDevice APKs have identical unsigned ZIP payloads; debug signatures may differ.\n')
        require(api('git/ref/heads/main')['object']['sha'] == current, 'Main moved before publication')
        if args.publish:
            # Atomic create refuses an existing tag, including a collision after
            # the read-only preflight. Never let gh silently adopt another tag.
            command('gh', 'api', '--method', 'POST', f'repos/{REPO}/git/refs',
                    '-f', f'ref=refs/tags/{tag}', '-f', f'sha={current}')
            ref = api(f'git/ref/tags/{tag}')['object']
            require(ref['type'] == 'commit' and ref['sha'] == current, 'New tag target changed')
            assets = [str(p) for p in sorted(output.iterdir()) if p.name != 'release-notes.md']
            subprocess.run(['gh', 'release', 'create', tag, *assets, '--repo', REPO, '--verify-tag', '--prerelease', '--title', f'CinemaTV {tag}', '--notes-file', str(output / 'release-notes.md')], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    record = commands.add_parser('receipt')
    record.add_argument('--device', required=True)
    record.add_argument('--classes', default='')
    record.add_argument('--output', required=True)
    promotion = commands.add_parser('promote')
    promotion.add_argument('--output', required=True)
    promotion.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    receipt(args) if args.command == 'receipt' else promote(args)
