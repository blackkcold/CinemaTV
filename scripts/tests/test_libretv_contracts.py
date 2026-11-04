"""Offline host tests. These do not count as official LibreTV acceptance."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, MagicMock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('libretv_contracts', ROOT / 'scripts/libretv-contracts/run.py')
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class LibreTvContractsTest(unittest.TestCase):
    def test_environment_is_loopback_and_telemetry_disabled(self):
        env = runner.child_env(1234, 'test-only')
        self.assertEqual('127.0.0.1', env['HOSTNAME'])
        self.assertEqual('1234', env['PORT'])
        self.assertEqual('1', env['NEXT_TELEMETRY_DISABLED'])
        self.assertEqual('test-only', env['PASSWORD'])
        self.assertNotIn('COOKIE_SECURE', env)

    def test_environment_does_not_inherit_credentials_or_sources(self):
        with patch.dict(os.environ, {'PASSWORD': 'forbidden', 'DEFAULT_SOURCES': 'forbidden',
                                     'NODE_OPTIONS': 'forbidden', 'HTTP_PROXY': 'forbidden'}):
            env = runner.child_env(1234, None)
        self.assertNotIn('PASSWORD', env)
        for key in ('DEFAULT_SOURCES', 'NODE_OPTIONS', 'HTTP_PROXY', 'PROXY_SECRET'):
            self.assertNotIn(key, env)

    def test_matching_published_checksum_and_bytes_are_both_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, sums = Path(tmp) / runner.ARCHIVE, Path(tmp) / 'SHA256SUMS'
            archive.write_bytes(b'original host test fixture, not upstream code')
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            sums.write_text(f'{digest}  {runner.ARCHIVE}\n')
            with patch.object(runner, 'SHA256', digest):
                runner.verify_archive(archive, sums)
                archive.write_bytes(b'tampered')
                with self.assertRaisesRegex(ValueError, 'archive checksum'):
                    runner.verify_archive(archive, sums)
            with self.assertRaisesRegex(ValueError, 'Published checksum'):
                runner.verify_archive(archive, sums)

    def test_wrong_filename_rejected_even_with_matching_digest(self):
        with tempfile.TemporaryDirectory() as tmp:
            sums = Path(tmp) / 'SHA256SUMS'
            sums.write_text(f'{runner.SHA256}  different-release.tar.gz\n')
            with self.assertRaisesRegex(ValueError, 'Published checksum'):
                runner.verify_archive(Path(tmp) / 'not-read', sums)

    def test_http_client_targets_only_loopback_and_closes(self):
        conn = MagicMock()
        conn.getresponse.return_value.status = 200
        conn.getresponse.return_value.getheaders.return_value = [('Content-Type', 'application/json')]
        conn.getresponse.return_value.read.return_value = b'{}'
        with patch.object(runner.http.client, 'HTTPConnection', return_value=conn) as constructor:
            result = runner.request(1234, '/api/auth', method='POST', body={'password': 'disposable'})
        constructor.assert_called_once_with('127.0.0.1', 1234, timeout=10)
        self.assertEqual(200, result[0])
        self.assertEqual({'password': 'disposable'}, json.loads(conn.request.call_args.kwargs['body']))
        conn.close.assert_called_once()

    def test_http_client_closes_after_protocol_error(self):
        conn = MagicMock()
        conn.request.side_effect = OSError('test failure')
        with patch.object(runner.http.client, 'HTTPConnection', return_value=conn):
            with self.assertRaises(OSError):
                runner.request(1234, '/api/status')
        conn.close.assert_called_once()

    def test_process_always_terminated_after_failed_assertion(self):
        proc = MagicMock()
        proc.poll.return_value = None
        with patch.object(runner.subprocess, 'Popen', return_value=proc) as popen, patch.object(runner, 'request'):
            with self.assertRaisesRegex(RuntimeError, 'test assertion'):
                with runner.server(Path('/official/node'), '/separate/upstream', 'disposable'):
                    raise RuntimeError('test assertion')
        proc.terminate.assert_called_once()
        proc.wait.assert_called_once_with(timeout=5)
        self.assertEqual(runner.subprocess.DEVNULL, popen.call_args.kwargs['stdout'])
        self.assertEqual(runner.subprocess.DEVNULL, popen.call_args.kwargs['stderr'])

    def test_startup_exit_is_not_reported_as_success(self):
        proc = MagicMock()
        proc.poll.return_value = 1
        with patch.object(runner.subprocess, 'Popen', return_value=proc):
            with self.assertRaisesRegex(RuntimeError, 'exited during startup'):
                with runner.server(Path('/official/node'), '/separate/upstream', None):
                    self.fail('Failed process must not be yielded')
        proc.terminate.assert_called_once()


if __name__ == '__main__':
    unittest.main()
