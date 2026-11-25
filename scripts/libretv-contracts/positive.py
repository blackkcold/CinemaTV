#!/usr/bin/env python3
"""Controlled static-fixture parsing against unmodified official LibreTV; not a dynamic CMS test."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import secrets
import subprocess
import tarfile
import tempfile
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('negative_contracts', ROOT / 'run.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
NODE_SHA256 = '1805bcd35e6815559bd2eb97db438d3fdb0eb3a5e6a977b7c1badca76337e9ce'
UUID = '6402b77c-b61f-4a06-96ca-c8420a2becf4'
MEDIA = f'https://video.blender.org/object-storage/web_videos/{UUID}-240.mp4'

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def validate_url(url):
    if not re.fullmatch(r'https://raw\.githubusercontent\.com/blackkcold/CinemaTV/[0-9a-f]{40}/scripts/libretv-contracts/fixtures/blender-bbb-static-cms\.json', url):
        raise ValueError('Only an immutable authorized CinemaTV fixture URL is allowed')
    return url

def fetch(url, limit, headers=None):
    with build_opener(NoRedirect).open(Request(url, headers=headers or {}), timeout=30) as response:
        body = response.read(limit + 1)
        if len(body) > limit:
            raise ValueError('Response exceeded bounded body size')
        return response.status, response.headers, body

def verify_node(node, archive):
    if hashlib.sha256(archive.read_bytes()).hexdigest() != NODE_SHA256:
        raise ValueError('Official Node archive digest mismatch')
    with tarfile.open(archive) as bundle:
        expected = bundle.extractfile('node-v22.23.4-linux-x64/bin/node').read()
    if hashlib.sha256(node.read_bytes()).digest() != hashlib.sha256(expected).digest():
        raise ValueError('Node executable differs from verified official archive')
    if subprocess.check_output([str(node), '--version'], text=True).strip() != 'v22.23.4':
        raise ValueError('Wrong Node runtime')

def contracts(node, directory, url, fixture, check, diagnostics):
    item = fixture['list'][0]
    source = {'key': 'controlled-blender-static', 'name': 'Controlled static Blender fixture (not dynamic API)', 'url': url}
    password = secrets.token_urlsafe(32)
    with base.server(node, directory, password) as port:
        def call(path, **kwargs):
            code, headers, text = base.request(port, path, **kwargs)
            check('HTTP 200 ' + path.split('?')[0], code == 200)
            return headers, json.loads(text)
        _, status = call('/api/status')
        check('official version and no configured content sources', status['version'] == base.VERSION and all(status[k] == [] for k in ['defaultSources', 'defaultLiveSources', 'defaultSubscriptions']) and status['defaultRecommendSource'] is None)
        headers, auth = call('/api/auth', method='POST', body={'password': password})
        cookie = headers.get('set-cookie', '').split(';', 1)[0]
        check('synthetic authentication succeeds', auth.get('success') is True and cookie.startswith('ltv_session='))
        def valid_list(items):
            return len(items) == 1 and all(items[0].get(k) == v for k, v in {'vodId': UUID, 'name': item['vod_name'], 'sourceKey': source['key'], 'sourceName': source['name'], 'sourceUrl': url}.items())
        _, result = call('/api/search', method='POST', cookie=cookie, body={'wd': 'Big Buck Bunny', 'sources': [source], 'filterAdult': True})
        diagnostics['searchResultCount'] = len(result.get('list', []))
        diagnostics['searchFailures'] = [{'sourceKey': f.get('sourceKey'), 'timedOut': f.get('timedOut'), 'error': str(f.get('error', ''))[:240]} for f in result.get('failures', [])]
        check('static fixture positive search mapping', valid_list(result['list']) and result['failures'] == [])
        for cached in [False, True]:
            code, headers, text = base.request(port, '/api/search?stream=1', method='POST', cookie=cookie, body={'wd': item['vod_name'], 'sources': [source], 'filterAdult': True})
            events = [json.loads(line) for line in text.splitlines()]
            check('NDJSON HTTP/content type', code == 200 and 'application/x-ndjson' in headers.get('content-type', ''))
            check('NDJSON ' + ('cached done' if cached else 'uncached source and done'), [e['type'] for e in events] == (['done'] if cached else ['source', 'done']))
            check('NDJSON positive mapped list/no failures', valid_list(events[-1]['list']) and events[-1]['failures'] == [])
            if not cached:
                check('NDJSON source identity and list', events[0]['sourceKey'] == source['key'] and valid_list(events[0]['list']))
        path = '/api/detail?' + urlencode({'id': UUID, 'source': json.dumps(source)})
        _, detail = call(path, cookie=cookie)
        check('detail exact real permitted episode', detail['episodes'] == [MEDIA])
        check('detail title and complete attribution retained', detail['videoInfo']['title'] == item['vod_name'] and detail['videoInfo']['desc'] == item['vod_content'])
        _, cached = call(path, cookie=cookie)
        check('cached detail equality', cached == detail)
        alternate_source = {**source, 'key': 'second-client-local-key'}
        _, second_client = call('/api/detail?' + urlencode({'id': UUID, 'source': json.dumps(alternate_source)}), cookie=cookie)
        check('detail cache retains first client key for same source URL',
              second_client['videoInfo']['sourceKey'] == source['key'] and
              second_client['videoInfo']['sourceUrl'] == source['url'] and
              second_client['episodes'] == detail['episodes'])
        headers, _ = call('/api/auth', method='DELETE', cookie=cookie)
        check('logout expires synthetic cookie', 'Max-Age=0' in headers.get('set-cookie', ''))
        _, auth = call('/api/auth')
        check('cookie-free request unverified', auth['verified'] is False)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', choices=sorted(base.RELEASES), default='2.21.1')
    for arg in ['archive', 'sums', 'node', 'node-archive', 'output']:
        parser.add_argument('--' + arg, type=Path, required=True)
    parser.add_argument('--fixture-url', required=True)
    args = parser.parse_args()
    base.select_release(args.version)
    evidence = {'schemaVersion': 1, 'startedAt': datetime.now(timezone.utc).isoformat(), 'result': 'failed', 'scope': 'controlled static fixture positive protocol parsing only', 'upstreamVersion': base.VERSION, 'upstreamCommit': base.COMMIT, 'archiveSha256': base.SHA256, 'nodeVersion': 'v22.23.4', 'nodeArchiveSha256': NODE_SHA256, 'checks': [], 'limitations': ['Not a dynamic CMS/search/pagination/ID-filtering service', 'No unknown-ID correctness asserted', 'No Android/device/home-service acceptance or full-film decode', 'No Douban endpoint or arbitrary source requested', 'Upstream SSRF/auth/fetch unchanged; process loopback-only and ephemeral']}
    def check(name, predicate):
        if not predicate:
            raise AssertionError('Contract failed: ' + name)
        evidence['checks'].append(name)
    try:
        url = validate_url(args.fixture_url)
        base.verify_archive(args.archive, args.sums)
        verify_node(args.node.resolve(), args.node_archive)
        manifest = json.loads((ROOT / 'fixtures/blender-bbb-static-cms.manifest.json').read_text())
        raw = (ROOT / 'fixtures/blender-bbb-static-cms.json').read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        check('local fixture matches reviewed manifest', digest == manifest['fixture']['sha256'])
        evidence.update(fixtureUrl=url, fixtureSha256=digest)
        for query in ['', '?ac=videolist&wd=Big%20Buck%20Bunny&pg=1', '?ac=videolist&ids=' + UUID]:
            status, _, body = fetch(url + query, 65536)
            check('published immutable fixture query digest ' + (query or '(plain)'), status == 200 and hashlib.sha256(body).hexdigest() == digest)
        status, headers, body = fetch(MEDIA, 65536, {'Range': 'bytes=0-65535'})
        check('fresh permitted media range availability', status == 206 and headers.get('Content-Range') == 'bytes 0-65535/52991998' and headers.get('Content-Type', '').startswith('video/mp4') and len(body) == 65536 and body[4:8] == b'ftyp')
        evidence['freshMediaRange'] = {'url': MEDIA, 'status': status, 'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest(), 'contentRange': headers.get('Content-Range')}
        with tempfile.TemporaryDirectory(prefix='cinematv-libretv-positive-') as tmp:
            with tarfile.open(args.archive) as archive:
                archive.extractall(tmp, filter='data')
            contracts(args.node.resolve(), tmp, url, json.loads(raw), check, evidence.setdefault('diagnostics', {}))
        evidence['result'] = 'passed'
    except Exception as error:
        # Never export response bodies, cookies, passwords or environment values.
        evidence['errorType'] = type(error).__name__
        if isinstance(error, AssertionError):
            evidence['failedCheck'] = str(error)
    finally:
        evidence['finishedAt'] = datetime.now(timezone.utc).isoformat()
        evidence['passed'] = len(evidence['checks'])
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    print(f"{evidence['result'].upper()}: {evidence['passed']} controlled static-fixture checks; see {args.output}")
    return 0 if evidence['result'] == 'passed' else 1

if __name__ == '__main__':
    raise SystemExit(main())
