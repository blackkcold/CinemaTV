"""Offline fixture integrity and generation contracts. No Android SDK or FFmpeg needed."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/generate-test-media.sh'
ASSET = Path('app/src/androidTest/assets/playback-fixture.mp4')
BASH = shutil.which('bash')


class MediaFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        self.script = self.root / 'scripts/generate-test-media.sh'
        shutil.copyfile(SCRIPT, self.script)
        self.fixture = self.root / ASSET
        self.fixture.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / ASSET, self.fixture)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.add_tool('dirname')
        # A restricted PATH proves normal preparation does not need media tools
        # or network/package managers, even when the host happens to have them.
        self.hash_tool = 'sha256sum' if shutil.which('sha256sum') else 'shasum'
        self.add_tool(self.hash_tool)
        self.log = self.root / 'ffmpeg-arguments'

    def add_tool(self, name):
        executable = shutil.which(name)
        self.assertIsNotNone(executable, f'Host command required by test: {name}')
        (self.bin / name).symlink_to(executable)

    def run_script(self, *args):
        env = dict(os.environ, PATH=str(self.bin), FFMPEG_LOG=str(self.log),
                   FIXTURE_REFERENCE=str(ROOT / ASSET))
        return subprocess.run([BASH, str(self.script), *args], cwd=self.temp.name,
                              env=env, text=True, capture_output=True, timeout=10)

    def mock_encoder(self, body):
        for tool in ('mkdir', 'mktemp', 'mv', 'rm', 'cp'):
            self.add_tool(tool)
        encoder = self.bin / 'ffmpeg'
        encoder.write_text(f'#!{BASH}\nset -eu\nprintf "%s\\n" "$@" > "$FFMPEG_LOG"\n{body}\n')
        encoder.chmod(0o755)

    def test_pinned_bytes_match_script_and_provenance(self):
        data = (ROOT / ASSET).read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        match = re.search(r'^expected_sha256=([a-f0-9]{64})$', SCRIPT.read_text(), re.M)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), digest)
        provenance = (ROOT / ASSET.parent / 'README.md').read_text()
        self.assertIn(digest, provenance)
        self.assertIn(f'{len(data):,} bytes', provenance)
        self.assertIn('synthetic', provenance)
        self.assertIn('never as a stream in the user app', provenance)

    def test_valid_committed_bytes_need_no_media_tools_and_are_not_rewritten(self):
        before = self.fixture.stat().st_mtime_ns
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Verified original synthetic instrumentation fixture', result.stdout)
        self.assertEqual(before, self.fixture.stat().st_mtime_ns)
        self.assertFalse(self.log.exists())

    def test_missing_fixture_fails_without_regeneration(self):
        self.fixture.unlink()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Missing committed synthetic fixture', result.stderr)
        self.assertFalse(self.fixture.exists())

    def test_empty_or_changed_bytes_fail_closed_without_repair(self):
        original = self.fixture.read_bytes()
        for data in (b'', original[:-1] + bytes([original[-1] ^ 1])):
            with self.subTest(size=len(data)):
                self.fixture.write_bytes(data)
                result = self.run_script()
                self.assertNotEqual(0, result.returncode)
                self.assertIn('SHA-256 mismatch', result.stderr)
                self.assertEqual(data, self.fixture.read_bytes())

    def test_missing_checksum_tool_fails_closed(self):
        (self.bin / self.hash_tool).unlink()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('requires sha256sum or shasum', result.stderr)

    @unittest.skipUnless(shutil.which('shasum'), 'shasum is not installed on this host')
    def test_shasum_fallback_checks_the_same_pin(self):
        if self.hash_tool != 'shasum':
            (self.bin / self.hash_tool).unlink()
            self.add_tool('shasum')
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.fixture.write_bytes(b'changed')
        self.assertNotEqual(0, self.run_script().returncode)

    def test_unknown_arguments_do_not_bypass_validation_or_mutate_fixture(self):
        original = self.fixture.read_bytes()
        for args in (('--skip-check',), ('',), ('--regenerate', '--force')):
            with self.subTest(args=args):
                result = self.run_script(*args)
                self.assertEqual(2, result.returncode)
                self.assertIn('Usage:', result.stderr)
                self.assertEqual(original, self.fixture.read_bytes())

    def test_explicit_regeneration_requires_encoder(self):
        original = self.fixture.read_bytes()
        result = self.run_script('--regenerate')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Explicit fixture regeneration requires FFmpeg', result.stderr)
        self.assertEqual(original, self.fixture.read_bytes())

    def test_explicit_regeneration_preserves_synthetic_source_parameters(self):
        self.mock_encoder('cp "$FIXTURE_REFERENCE" "${@: -1}"')
        self.fixture.unlink()
        result = self.run_script('--regenerate')
        self.assertEqual(0, result.returncode, result.stderr)
        args = self.log.read_text().splitlines()
        self.assertEqual([
            '-y', '-hide_banner', '-loglevel', 'error',
            '-f', 'lavfi', '-i', 'color=c=0x203e52:size=320x180:rate=12',
            '-f', 'lavfi', '-i', 'sine=frequency=220:sample_rate=44100', '-t', '30',
            '-c:v', 'libx264', '-preset', 'fast', '-crf', '35',
            '-c:a', 'aac', '-b:a', '24k', '-movflags', '+faststart',
        ], args[:-1])
        self.assertEqual((ROOT / ASSET).read_bytes(), self.fixture.read_bytes())
        self.assertFalse(list(self.fixture.parent.glob('.playback-fixture.*')))

    def test_failed_encoder_preserves_existing_fixture_and_cleans_temporary_file(self):
        original = self.fixture.read_bytes()
        self.mock_encoder('printf partial > "${@: -1}"\nexit 9')
        result = self.run_script('--regenerate')
        self.assertEqual(9, result.returncode)
        self.assertEqual(original, self.fixture.read_bytes())
        self.assertFalse(list(self.fixture.parent.glob('.playback-fixture.*')))

    def test_regeneration_cannot_silently_change_the_reviewed_pin(self):
        script_before = self.script.read_bytes()
        self.mock_encoder('printf regenerated > "${@: -1}"')
        result = self.run_script('--regenerate')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('SHA-256 mismatch', result.stderr)
        self.assertEqual(b'regenerated', self.fixture.read_bytes())
        self.assertEqual(script_before, self.script.read_bytes())
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertFalse(list(self.fixture.parent.glob('.playback-fixture.*')))


if __name__ == '__main__':
    unittest.main()
