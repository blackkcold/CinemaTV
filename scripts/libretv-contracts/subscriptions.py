#!/usr/bin/env python3
"""Black-box SourceList contracts against an unmodified, pinned official server.

Only immutable original fixture JSON is fetched. example.com entries are parser
inputs, never a source of media or a stream requested by this suite.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import secrets
import tarfile
import tempfile
from urllib.parse import urlencode
from positive import base, fetch, verify_node


def contracts(node, directory, commit):
    if not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise ValueError('An immutable fixture commit is required')
    urls = {name: f'https://raw.githubusercontent.com/blackkcold/CinemaTV/{commit}/scripts/libretv-contracts/fixtures/{name}'
            for name in ['audit-source-list.json', 'audit-tvbox.json']}
    checks = []

    def check(name, predicate):
        if not predicate:
            raise AssertionError(name)
        checks.append(name)

    for name, url in urls.items():
        status, _, body = fetch(url, 65536)
        local = (Path(__file__).parent / 'fixtures' / name).read_bytes()
        check(f'immutable original fixture {name}', status == 200 and hashlib.sha256(body).digest() == hashlib.sha256(local).digest())
    subscription = {'url': urls['audit-source-list.json'], 'name': 'Synthetic subscription', 'refreshDays': 3}
    password = secrets.token_urlsafe(32)
    with base.server(node, directory, password, [subscription]) as port:
        def call(path, **kwargs):
            code, headers, body = base.request(port, path, **kwargs)
            check(f'HTTP200 {path.split("?")[0]}', code == 200)
            return headers, json.loads(body)
        _, status = call('/api/status')
        check('subscriptions-only server advertises the configured subscription',
              status['defaultSources'] == [] and status['defaultLiveSources'] == [] and
              status['defaultSubscriptions'] == [subscription])
        headers, _ = call('/api/auth', method='POST', body={'password': password})
        cookie = headers.get('set-cookie', '').split(';', 1)[0]
        for name, url in urls.items():
            path = '/api/source-list?' + urlencode({'url': url})
            _, payload = call(path, cookie=cookie)
            check(f'{name} imports one unique VOD and live entry',
                  len(payload['sources']) == 1 and len(payload['liveSources']) == 1)
            check(f'{name} preserves direct URLs',
                  payload['sources'][0]['url'] == 'https://example.com/api.php/provide/vod' and
                  payload['liveSources'][0]['url'] == 'https://example.com/cinematv-audit.m3u')
            if name == 'audit-tvbox.json':
                check('TVBOX unsupported engine/XML/text are skipped with reasons',
                      payload['stats']['format'] == 'tvbox' and payload['stats']['skipped'] == 3 and
                      payload['stats']['skippedByReason'] == {'spider': 1, 'xml': 1, 'nonM3uLive': 1})
        code, _, _ = base.request(port, '/api/source-list?' + urlencode({'url': 'http://127.0.0.1:9/private'}), cookie=cookie)
        check('private subscription URL remains blocked by upstream policy', code == 403)
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', choices=sorted(base.RELEASES), required=True)
    parser.add_argument('--commit', required=True)
    for name in ['archive', 'sums', 'node', 'node-archive', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    base.select_release(args.version)
    evidence = {'result': 'failed', 'upstreamVersion': base.VERSION, 'upstreamCommit': base.COMMIT,
                'archiveSha256': base.SHA256, 'scope': 'controlled SourceList and TVBOX parsing; no media fetched'}
    try:
        base.verify_archive(args.archive, args.sums)
        verify_node(args.node, args.node_archive)
        with tempfile.TemporaryDirectory(prefix='cinematv-subscriptions-') as directory:
            with tarfile.open(args.archive) as archive:
                archive.extractall(directory, filter='data')
            evidence['checks'] = contracts(args.node.resolve(), directory, args.commit)
        evidence['result'] = 'passed'
    except Exception as error:
        evidence['errorType'] = type(error).__name__
        if isinstance(error, AssertionError):
            evidence['failedCheck'] = str(error)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    print(f"{evidence['result'].upper()}: official subscription parsing {base.VERSION}")
    return 0 if evidence['result'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
