#!/usr/bin/env python3
"""Own the isolated official process for one Android QA command; never a home server."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import tarfile
import tempfile
from positive import base, validate_url, verify_node
from prepare_official import prepare, NODE_ARCHIVE

FIXTURE = 'https://raw.githubusercontent.com/blackkcold/CinemaTV/74d104aa2b4e212163376bcbb1da04bef6670918/scripts/libretv-contracts/fixtures/blender-bbb-static-cms.json'


def validate_status(port):
    code, _, body = base.request(port, '/api/status')
    status = json.loads(body)
    if not (code == 200 and status.get('version') == base.VERSION and
            status.get('passwordRequired') is True and status.get('verified') is False and
            all(status.get(k) == [] for k in ('defaultSources', 'defaultLiveSources', 'defaultSubscriptions')) and
            status.get('defaultRecommendSource') is None):
        raise ValueError('Official isolated server preflight failed')


@contextlib.contextmanager
def session(directory):
    validate_url(FIXTURE)
    node = prepare(directory).resolve()
    verify_node(node, directory / NODE_ARCHIVE)
    with tempfile.TemporaryDirectory(prefix='cinematv-official-android-') as temporary:
        root = Path(temporary)
        os.chmod(root, 0o700)
        upstream = root / 'upstream'
        upstream.mkdir()
        with tarfile.open(directory / base.ARCHIVE) as archive:
            archive.extractall(upstream, filter='data')
        password = secrets.token_urlsafe(32)
        with base.server(node, upstream, password) as port:
            validate_status(port)
            credentials = root / 'android-credentials.json'
            credentials.write_text(json.dumps({'server': f'http://10.0.2.2:{port}', 'password': password, 'fixture': FIXTURE}))
            os.chmod(credentials, 0o600)
            yield credentials


def execute(directory, command, report):
    result = {'result': 'failed', 'scope': 'official LibreTV process lifecycle; Android assertions are in instrumentation results',
              'upstreamVersion': base.VERSION, 'archiveSha256': base.SHA256,
              'fixture': FIXTURE, 'limitations': ['Static fixture, not a dynamic CMS', 'No home server or full-film acceptance']}
    child = None
    def interrupted(signum, frame):
        raise InterruptedError('QA interrupted')
    previous = {s: signal.signal(s, interrupted) for s in (signal.SIGTERM, signal.SIGINT)}
    try:
        with session(directory) as credentials:
            env = dict(os.environ, CINEMA_OFFICIAL_CREDENTIAL_FILE=str(credentials))
            child = subprocess.Popen(command, env=env, start_new_session=True)
            try:
                status = child.wait(timeout=1380)
                result['commandExitStatus'] = status
                result['result'] = 'passed' if status == 0 else 'failed'
            finally:
                if child.poll() is None:
                    os.killpg(child.pid, signal.SIGTERM)
                    try:
                        child.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(child.pid, signal.SIGKILL)
                        child.wait(timeout=5)
        return 0 if result['result'] == 'passed' else 1
    except Exception as error:
        result['errorType'] = type(error).__name__
        return 1
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(result, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('A bounded device QA command is required')
    return execute(args.directory.resolve(), command, args.report)

if __name__ == '__main__':
    raise SystemExit(main())
