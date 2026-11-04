"""Offline workflow scope and pre-execution integrity checks."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import prepare_official as helper

WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/official-libretv-positive.yml'

class WorkflowTests(unittest.TestCase):
    def test_narrow_push_and_manual_bounded_read_only(self):
        text = WORKFLOW.read_text()
        self.assertIn('  workflow_dispatch:', text)
        self.assertIn('    branches: [test/official-libretv-positive]', text)
        self.assertIn("github.event_name == 'push' || github.event_name == 'workflow_dispatch'", text)
        paths = text.split('    paths:\n', 1)[1].split('  workflow_dispatch:', 1)[0]
        self.assertEqual([line.strip().removeprefix('- ') for line in paths.splitlines() if line.strip()], [
            '.github/workflows/official-libretv-positive.yml',
            'scripts/libretv-contracts/positive.py',
            'scripts/libretv-contracts/prepare_official.py',
            'scripts/libretv-contracts/test_positive.py',
            'scripts/libretv-contracts/test_workflow.py'])
        self.assertNotIn('branches: [main]', text)
        for trigger in ['  pull_request:', '  schedule:', '  workflow_run:', '  pull_request_target:']:
            self.assertNotIn(trigger, text)
        self.assertIn("github.ref == 'refs/heads/test/official-libretv-positive'", text)
        self.assertIn('contents: read', text)
        self.assertIn('persist-credentials: false', text)
        self.assertIn('cancel-in-progress: false', text)
        self.assertIn('timeout-minutes: 12', text)
        self.assertIn('if: always()', text)
        self.assertIn('qa/official-libretv-positive/*.json', text)
        self.assertNotIn('secrets.', text)
        for forbidden in ['setup-java', 'emulator', 'gradle ', '/api/douban', '/etc/hosts', 'resolv.conf', 'NODE_OPTIONS', 'HTTP_PROXY', 'HTTPS_PROXY']:
            self.assertNotIn(forbidden, text)

    def test_fixed_official_downloads_and_fixture(self):
        text = WORKFLOW.read_text()
        self.assertIn('--proto-redir', text)
        self.assertIn('--max-filesize 67108864', text)
        self.assertIn('https://github.com/LibreSpark/LibreTV/releases/download/v2.21.1/SHA256SUMS', text)
        self.assertIn('https://nodejs.org/dist/v22.23.4/SHASUMS256.txt', text)
        self.assertIn('74d104aa2b4e212163376bcbb1da04bef6670918', text)
        self.assertLess(text.index('prepare_official.py'), text.index('positive.py \\'))
        self.assertNotIn('evidence/positive-static-fixture-2026-11-04.json', text)

    def test_node_manifest_and_bytes_both_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / helper.NODE_ARCHIVE
            archive.write_bytes(b'test-only')
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            manifest = root / 'SHASUMS256.txt'
            with patch.object(helper, 'NODE_SHA256', digest):
                manifest.write_text(f'{digest}  {helper.NODE_ARCHIVE}\n')
                helper.verify_node_manifest(root)
                for bad in [f'{digest}  other.tar.xz\n', f'{"0" * 64}  {helper.NODE_ARCHIVE}\n', f'{digest}  {helper.NODE_ARCHIVE}\n' * 2]:
                    manifest.write_text(bad)
                    with self.assertRaises(ValueError):
                        helper.verify_node_manifest(root)
                manifest.write_text(f'{digest}  {helper.NODE_ARCHIVE}\n')
                archive.write_bytes(b'changed')
                with self.assertRaises(ValueError):
                    helper.verify_node_manifest(root)

    def test_no_extraction_before_both_validations(self):
        with patch.object(helper.base, 'verify_archive', side_effect=ValueError('bad LibreTV')), patch.object(helper, 'verify_node_manifest') as node_check, patch.object(helper.tarfile, 'open') as extract:
            with self.assertRaises(ValueError):
                helper.prepare(Path('/tmp/not-used'))
            node_check.assert_not_called()
            extract.assert_not_called()
        with patch.object(helper.base, 'verify_archive'), patch.object(helper, 'verify_node_manifest', side_effect=ValueError('bad Node')), patch.object(helper.tarfile, 'open') as extract:
            with self.assertRaises(ValueError):
                helper.prepare(Path('/tmp/not-used'))
            extract.assert_not_called()

if __name__ == '__main__':
    unittest.main(verbosity=2)
