#!/usr/bin/env python3
"""Original black-box checks of an unmodified, separately supplied LibreTV release."""
import argparse
import contextlib
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tarfile
import tempfile
import time
from urllib.parse import urlencode

VERSION = '2.21.1'
COMMIT = '29cbeb3d37b4876f909a0354087a3531af0447f6'
ARCHIVE = f'libretv-{VERSION}-standalone.tar.gz'
SHA256 = 'df9b240c0fdbb8470aaccccc5435f74820d6bea6f4d905b2c83d1e6dd0973786'
RELEASES = {
    '2.21.1': (COMMIT, SHA256),
    '2.21.2': ('722e86fce102aa6701e73a83dd0a14eb5ba707c9',
               '153ac847179afc8ae8764a40f9352b5ef277d8dfb253c602b320fb41b38599f6'),
}


def select_release(version):
    """Only audited, checksum-pinned official releases may execute in this harness."""
    global VERSION, COMMIT, ARCHIVE, SHA256
    COMMIT, SHA256 = RELEASES[version]
    VERSION = version
    ARCHIVE = f'libretv-{VERSION}-standalone.tar.gz'


def verify_archive(archive, sums):
    entries = [line.split() for line in Path(sums).read_text().splitlines() if line.strip()]
    if [SHA256, ARCHIVE] not in entries:
        raise ValueError('Published checksum does not match pinned release')
    digest = hashlib.sha256(Path(archive).read_bytes()).hexdigest()
    if digest != SHA256:
        raise ValueError('Release archive checksum mismatch')


def child_env(port, password, subscriptions=None):
    # Do not inherit credentials, sources, proxy settings, or NODE_OPTIONS.
    env = {'PATH': os.defpath, 'HOSTNAME': '127.0.0.1', 'PORT': str(port),
           'NODE_ENV': 'production', 'NEXT_TELEMETRY_DISABLED': '1'}
    if password is not None:
        env['PASSWORD'] = password
    if subscriptions is not None:
        env['DEFAULT_SUBSCRIPTIONS'] = json.dumps(subscriptions)
    return env


def request(port, path, method='GET', body=None, cookie=None, raw=None):
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
    headers = {}
    if cookie:
        headers['Cookie'] = cookie
    if body is not None or raw is not None:
        headers['Content-Type'] = 'application/json'
    data = raw if raw is not None else json.dumps(body) if body is not None else None
    try:
        conn.request(method, path, body=data, headers=headers)
        response = conn.getresponse()
        return response.status, dict(response.getheaders()), response.read().decode('utf-8')
    finally:
        conn.close()


@contextlib.contextmanager
def server(node, directory, password, subscriptions=None):
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    # Output is intentionally discarded: credentials/cookies never become evidence.
    proc = subprocess.Popen([str(node), 'server.js'], cwd=directory,
                            env=child_env(port, password, subscriptions), stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)
    try:
        for _ in range(100):
            if proc.poll() is not None:
                raise RuntimeError('Isolated upstream exited during startup')
            try:
                request(port, '/api/status')
                break
            except (OSError, http.client.HTTPException):
                time.sleep(0.1)
        else:
            raise RuntimeError('Isolated upstream startup timed out')
        yield port
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)


def run(node, directory):
    checks = []

    def check(name, predicate):
        if not predicate:
            raise AssertionError(f'Contract failed: {name}')
        checks.append(name)

    def call(port, path, status=200, **kwargs):
        code, headers, text = request(port, path, **kwargs)
        check(f'{kwargs.get("method", "GET")} {path.split("?")[0]} HTTP {status}', code == status)
        return headers, json.loads(text)

    with server(node, directory, None) as port:
        _, status = call(port, '/api/status')
        check('missing PASSWORD status is unverified', status['passwordRequired'] is False and status['verified'] is False)
        call(port, '/api/auth', status=503, method='POST', body={'password': 'not-a-secret'})
        call(port, '/api/search', status=503, method='POST', body={'wd': 'test', 'sources': []})
    password = secrets.token_urlsafe(32)
    with server(node, directory, password) as port:
        _, status = call(port, '/api/status')
        check('pinned version', status['version'] == VERSION)
        check('password required and unverified', status['passwordRequired'] is True and status['verified'] is False)
        check('no preset sources or subscriptions', all(status[k] == [] for k in ('defaultSources', 'defaultLiveSources', 'defaultSubscriptions')))
        check('no preset recommendations', status['defaultRecommendSource'] is None)
        call(port, '/api/search', status=401, method='POST', body={'wd': 'test', 'sources': []})
        call(port, '/api/detail?id=test', status=401)
        call(port, '/api/auth', status=400, method='POST', raw='{')
        call(port, '/api/auth', status=401, method='POST', body={'password': password + '-wrong'})
        headers, auth = call(port, '/api/auth', method='POST', body={'password': password})
        cookie_header = headers.get('set-cookie', '')
        cookie = cookie_header.split(';', 1)[0]
        check('successful auth and HttpOnly Lax session', auth.get('success') is True and cookie.startswith('ltv_session=') and 'HttpOnly' in cookie_header and 'SameSite=lax' in cookie_header)
        _, auth = call(port, '/api/auth', cookie=cookie)
        check('session verifies', auth['verified'] is True)
        _, status = call(port, '/api/status', cookie=cookie)
        check('status verifies cookie', status['verified'] is True)
        _, auth = call(port, '/api/auth', cookie='ltv_session=invalid')
        check('invalid session does not verify', auth['verified'] is False)
        call(port, '/api/search', status=401, method='POST', body={'wd': 'test', 'sources': []}, cookie='ltv_session=invalid')
        for raw in ('{',):
            call(port, '/api/search', status=400, method='POST', raw=raw, cookie=cookie)
        for body in ({'wd': '', 'sources': []}, {'wd': 'x' * 101, 'sources': []}, {'wd': 'test', 'sources': []}):
            call(port, '/api/search', status=400, method='POST', body=body, cookie=cookie)
        call(port, '/api/detail?id=bad%2Fid', status=400, cookie=cookie)
        call(port, '/api/detail?id=test&source=%7B', status=400, cookie=cookie)
        blocked = ['file:///etc/passwd', f'http://127.0.0.1:{port}/api/status',
                   'http://localhost:9/cms', 'http://[::1]:9/cms',
                   'http://10.0.0.1:9/cms', 'http://169.254.169.254/latest/meta-data/']
        for i, url in enumerate(blocked):
            source = {'key': f'rejected-{i}', 'name': 'Rejected test address', 'url': url}
            _, result = call(port, '/api/search', method='POST', cookie=cookie,
                             body={'wd': f'test-{i}', 'sources': [source]})
            check(f'search rejects source {i}', result['list'] == [] and len(result['failures']) == 1 and bool(result['failures'][0]['error']))
            call(port, '/api/detail?' + urlencode({'id': 'test', 'source': json.dumps(source)}), status=400, cookie=cookie)
        source = {'key': 'stream-rejected', 'name': 'Rejected test address', 'url': 'http://127.0.0.1:9/cms'}
        body = {'wd': 'stream-uncached', 'sources': [source]}
        for cached in (False, True):
            code, headers, text = request(port, '/api/search?stream=1', method='POST', body=body, cookie=cookie)
            events = [json.loads(line) for line in text.splitlines()]
            check(f'NDJSON rejection {"cached" if cached else "uncached"}', code == 200 and 'application/x-ndjson' in headers.get('content-type', '') and [e['type'] for e in events] == (['done'] if cached else ['source', 'done']) and events[-1]['list'] == [] and len(events[-1]['failures']) == 1)
        headers, _ = call(port, '/api/auth', method='DELETE', cookie=cookie)
        check('logout expires client cookie', 'Max-Age=0' in headers.get('set-cookie', ''))
        _, auth = call(port, '/api/auth')
        check('cleared cookie is unverified', auth['verified'] is False)
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', choices=sorted(RELEASES), default='2.21.1')
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--sums', required=True, type=Path)
    parser.add_argument('--node', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    select_release(args.version)
    verify_archive(args.archive, args.sums)
    node = args.node.resolve()
    runtime = subprocess.check_output([str(node), '--version'], text=True).strip()
    if not runtime.startswith('v22.'):
        raise ValueError('Use an official checksum-verified Node 22 runtime')
    with tempfile.TemporaryDirectory(prefix='cinematv-libretv-contract-') as tmp:
        with tarfile.open(args.archive) as archive:
            archive.extractall(tmp, filter='data')
        checks = run(node, tmp)
    evidence = {'schemaVersion': 1, 'upstreamVersion': VERSION, 'upstreamCommit': COMMIT,
                'archiveSha256': SHA256, 'nodeVersion': runtime,
                'scope': 'isolated official upstream HTTP negative and session contracts',
                'checks': checks, 'passed': len(checks),
                'positiveSearchDetail': 'NOT RUN: requires authorized public HTTPS Apple CMS bridge to permitted media',
                'androidAcceptance': 'NOT RUN'}
    args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    print(f'PASS: {len(checks)} isolated official upstream checks; positive media acceptance NOT RUN')


if __name__ == '__main__':
    main()
