#!/usr/bin/env python3
"""Create public TEST ONLY payloads bound to the actual signed installer fixture bytes."""
import hashlib
import json
from pathlib import Path
import sys
import time


def sha256(path: Path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def payloads(out: Path, now: int):
    receipt = json.loads((out / 'fixture-receipt.json').read_text())
    assert receipt['testOnly'] is True
    destination = out / 'manifests'
    destination.mkdir()

    def fixture(name):
        metadata = receipt['fixtures'][name]
        apk = out / (name + '.apk')
        digest = sha256(apk)
        assert digest == metadata['sha256']
        return dict(schema=1, channel=metadata['channel'], versionCode=metadata['versionCode'],
                    versionName=metadata['versionName'], applicationId=metadata['packageName'], minSdk=26,
                    apkUrl=f'https://github.com/blackkcold/CinemaTV/releases/download/TEST-ONLY/{name}.apk',
                    apkBytes=apk.stat().st_size, sha256=digest,
                    publishedAt=now - 60, expiresAt=now + 86400, notes='TEST ONLY disposable emulator fixture')

    stable, dev = fixture('stable'), fixture('dev')
    cases = {'stable': stable, 'dev': dev, 'wrong-envelope-key': {**stable, 'versionCode': 199},
             'tamper-source': stable, 'expired': {**stable, 'publishedAt': now - 3600, 'expiresAt': now - 60},
             'wrong-package': {**stable, 'applicationId': 'test.wrong.package'},
             'wrong-channel': {**stable, 'channel': 'dev'},
             'invalid-version': {**stable, 'versionCode': 0}, 'unsupported-sdk': {**stable, 'minSdk': 1000},
             'http-url': {**stable, 'apkUrl': stable['apkUrl'].replace('https:', 'http:')},
             'foreign-url': {**stable, 'apkUrl': stable['apkUrl'].replace('github.com', 'example.com')},
             'rollback': {**dev, 'versionCode': 101}, 'bad-digest': {**stable, 'sha256': '0' * 64},
             'size-too-large': {**stable, 'apkBytes': stable['apkBytes'] + 1},
             'size-too-small': {**stable, 'apkBytes': stable['apkBytes'] - 1},
             'archive-channel': {**stable, 'channel': 'dev'},
             'archive-version': {**stable, 'versionCode': 103},
             'archive-version-name': {**stable, 'versionName': '0.0.0-incorrect.TEST-ONLY'},
             'wrong-apk-key': fixture('wrong-key')}
    for case, filename in [('archive-package', 'wrong-package.apk'), ('archive-sdk', 'wrong-min-sdk.apk')]:
        apk = out / filename
        cases[case] = {**stable, 'apkBytes': apk.stat().st_size, 'sha256': sha256(apk),
                       'apkUrl': stable['apkUrl'].replace('stable.apk', filename)}
    for name, content in cases.items():
        (destination / (name + '.payload.json')).write_text(json.dumps(content, separators=(',', ':')))


if __name__ == '__main__':
    payloads(Path(sys.argv[1]), int(time.time()))
