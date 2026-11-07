#!/usr/bin/env python3
"""Validate explicitly reviewed capture bytes and stage Git blobs, never refs/releases."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import zipfile
from PIL import Image
from manifest import ROOT, digest, read, require, write
from visual_receipts import ANDROID_STATES, BROWSER_STATES

REPOSITORY = 'blackkcold/CinemaTV'
SHA256 = re.compile(r'[0-9a-f]{64}')
SHA1 = re.compile(r'[0-9a-f]{40}')
BROWSER_PROJECTS = {'css540-dpr2': (1920, 1080), 'tv720': (1280, 720),
                    'tv1080': (1920, 1080), 'tv4k': (3840, 2160)}
ANDROID_PROJECTS = {'hybrid1080': (1920, 1080), 'hybrid4k': (3840, 2160)}


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode()


def member(archive, name, maximum):
    require(isinstance(name, str) and not name.startswith('/') and '..' not in Path(name).parts and '\\' not in name,
            'Invalid capture path')
    info = archive.getinfo(name)
    require(not stat.S_ISLNK(info.external_attr >> 16), 'Capture symlink')
    require(not info.is_dir() and 0 < info.file_size <= maximum, 'Capture exceeds bounded size')
    return archive.read(info)


def unique_json(archive, suffix):
    names = [name for name in archive.namelist() if name == suffix or name.endswith('/' + suffix)]
    require(len(names) == 1, f'Missing or duplicate {suffix}')
    return json.loads(member(archive, names[0], 2 * 1024 * 1024))


def prepare(archive_path, artifact, review):
    require(SHA256.fullmatch(artifact.get('zipSha256', '')) and digest(archive_path) == artifact['zipSha256'], 'Artifact digest mismatch')
    require(SHA1.fullmatch(artifact.get('sourceCommit', '')), 'Missing exact capture source')
    require(type(artifact.get('id')) is int and artifact['id'] > 0 and type(artifact.get('runId')) is int and artifact['runId'] > 0,
            'Invalid artifact/run identity')
    require(review.get('status') == 'explicitly-reviewed' and review.get('reviewedBy') and review.get('reviewedAt'), 'No explicit visual review')
    entries = artifact.get('images', [])
    require(isinstance(entries, list) and 0 < len(entries) <= 100, 'Missing or excessive reviewed images')
    prepared = {}
    with zipfile.ZipFile(archive_path) as archive:
        require(len(archive.infolist()) <= 3000 and len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate or excessive archive members')
        for item in entries:
            family, project, state = item['family'], item['project'], item['state']
            if family == 'hybrid-browser':
                require(project in BROWSER_PROJECTS and state in BROWSER_STATES, 'Unknown browser baseline')
                expected_size = BROWSER_PROJECTS[project]
                result = unique_json(archive, 'production-results/results.json')
                counts = result['stats']
                require(counts['expected'] == 56 and all(counts[key] == 0 for key in ('unexpected', 'flaky', 'skipped')) and not result.get('errors'),
                        'Browser visual contracts did not all pass')
            else:
                require(family == 'hybrid-android' and project in ANDROID_PROJECTS and state in ANDROID_STATES, 'Unknown Android baseline')
                expected_size = ANDROID_PROJECTS[project]
                receipt = unique_json(archive, 'android-visual-receipt.json')
                require(receipt['sourceCommit'] == artifact['sourceCommit'] and receipt['status'] == 'passed-android-visual-contracts' and
                        receipt['comparisonStatus'] == 'candidate-only-not-approved', 'Actual-TV capture suite did not pass')
            require(len(item.get('reason', '').strip()) >= 20, 'Missing per-state review reason')
            raw = member(archive, item['imagePath'], 32 * 1024 * 1024)
            metadata_bytes = member(archive, item['metadataPath'], 256 * 1024)
            require(hashlib.sha256(raw).hexdigest() == item['imageSha256'] and
                    hashlib.sha256(metadata_bytes).hexdigest() == item['metadataSha256'], 'Reviewed bytes changed')
            metadata = json.loads(metadata_bytes)
            require(metadata['sourceCommit'] == artifact['sourceCommit'] and metadata['status'] == 'candidate-only-not-approved', 'Stale or noncandidate image')
            require(metadata['sha256'] == item['imageSha256'], 'Capture receipt digest mismatch')
            environment = metadata['environment']
            require(environment['family'] == family + '-production', 'Wrong presentation family')
            if family == 'hybrid-browser':
                size = (round(environment['viewport']['width'] * environment['dpr']), round(environment['viewport']['height'] * environment['dpr']))
                require(metadata['image'] == state + '.png', 'Wrong browser state')
            else:
                size = (environment['width'], environment['height'])
                require(environment.get('visualEpochMs') == 4070944800000 and environment.get('locale') and environment.get('timeZone'),
                        'Android clock or locale was not fixed and recorded')
                require(metadata['state'] == state and metadata['apkSha256'] == receipt['files']['app-debug.apk']['sha256'], 'Wrong Android state or APK')
            require(size == expected_size, 'Capture metadata is not the expected raster')
            with Image.open(io.BytesIO(raw)) as image:
                require(image.format == 'PNG' and image.size == expected_size, 'Not the expected original PNG raster')
                image.load()
            destination = f'design/ui/goldens/{family}/{project}/{state}'
            require(destination + '.png' not in prepared, 'Duplicate reviewed destination')
            approval = {'schema': 1, 'status': 'reviewed-baseline', 'environment': environment,
                        'sha256': item['imageSha256'], 'sourceCommit': artifact['sourceCommit'],
                        'sourceWorkflowRun': artifact['runId'], 'sourceArtifactId': artifact['id'],
                        'sourceArtifactSha256': artifact['zipSha256'], 'sourceMetadataSha256': item['metadataSha256'],
                        'reviewedBy': review['reviewedBy'], 'reviewedAt': review['reviewedAt'], 'reviewReason': item['reason']}
            prepared[destination + '.png'] = raw
            prepared[destination + '.json'] = json_bytes(approval)
    return prepared


def api(endpoint, payload=None):
    require(endpoint.startswith(f'repos/{REPOSITORY}/'), 'Foreign API endpoint')
    command = ['gh', 'api', endpoint]
    if payload is not None:
        # The only mutation supported by this tool is creating an unattached blob.
        require(endpoint == f'repos/{REPOSITORY}/git/blobs', 'Only blob creation is allowed')
        command += ['--method', 'POST', '--input', '-']
    result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                            text=True, capture_output=True, timeout=90, check=True)
    return json.loads(result.stdout)


def stage(manifest, output):
    require(os.getenv('GITHUB_ACTIONS') == 'true' and os.getenv('GITHUB_REPOSITORY') == REPOSITORY,
            'Staging is only allowed in this repository Actions context')
    require(os.getenv('GITHUB_REF') == 'refs/heads/dev-hybird', 'Do not stage from another development line')
    review = read(manifest)
    require(review.get('schema') == 1 and 0 < len(review.get('artifacts', [])) <= 8, 'Invalid review manifest')
    output.mkdir(parents=True, exist_ok=False)
    all_entries = {}
    for artifact in review['artifacts']:
        remote = api(f'repos/{REPOSITORY}/actions/artifacts/{artifact["id"]}')
        require(not remote['expired'] and remote['workflow_run']['id'] == artifact['runId'] and
                remote['workflow_run']['head_sha'] == artifact['sourceCommit'], 'Remote artifact provenance changed')
        require(remote['digest'] == 'sha256:' + artifact['zipSha256'] and remote['size_in_bytes'] < 512 * 1024 * 1024,
                'Remote artifact digest or size differs')
        archive = output / f'{artifact["id"]}.zip'
        with archive.open('xb') as stream:
            subprocess.run(['gh', 'api', f'repos/{REPOSITORY}/actions/artifacts/{artifact["id"]}/zip'],
                           stdout=stream, stderr=subprocess.PIPE, timeout=240, check=True)
        entries = prepare(archive, artifact, review)
        require(not all_entries.keys() & entries.keys(), 'Duplicate destination across artifacts')
        all_entries.update(entries)
        archive.unlink()
    # All captures are validated before creating the first immutable Git object.
    elements = []
    for path, data in sorted(all_entries.items()):
        value = api(f'repos/{REPOSITORY}/git/blobs', {'encoding': 'base64', 'content': base64.b64encode(data).decode()})
        expected = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        require(value['sha'] == expected, 'Git blob identity differs from reviewed bytes')
        elements.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': expected})
    write(output / 'reviewed-blob-elements.json', {'schema': 1, 'status': 'staged-objects-not-attached',
          'reviewManifestSha256': digest(manifest), 'stagingSourceCommit': os.environ['GITHUB_SHA'],
          'treeElements': elements, 'refChanged': False, 'publicationAllowed': False})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'design/ui/goldens/hybrid-review.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    stage(args.manifest, args.output)


if __name__ == '__main__': main()
