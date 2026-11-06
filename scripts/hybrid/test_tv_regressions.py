import json
from pathlib import Path
import tempfile
import unittest
from tv_regressions import suites, verify_audit_captures


class AdditionalTvEvidenceTests(unittest.TestCase):
    def test_provider_mode_selects_hybrid_cases_without_skipping_native_regressions(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            folder = evidence / 'hybrid-evidence'
            folder.mkdir()
            metadata = folder / 'case.json'
            (folder / 'case-input-trace.json').write_text('[]')
            metadata.write_text(json.dumps({'method': 'case', 'expectedMode': 'native-fallback'}))
            selected = suites(evidence)
            self.assertIn('dev.cinematv.playback.NativePlaybackSessionTest', selected)
            self.assertTrue(any('SourceSubscriptionsUiTest#nativeTvRemote' in key for key in selected))
            self.assertNotIn('dev.cinematv.HybridAuditUiTest', selected)
            metadata.write_text(json.dumps({'method': 'case', 'expectedMode': 'hybrid'}))
            self.assertIn('dev.cinematv.HybridAuditUiTest', suites(evidence))
            (folder / 'other.json').write_text(json.dumps({'method': 'other', 'expectedMode': 'native-fallback'}))
            with self.assertRaisesRegex(ValueError, 'disagree'):
                suites(evidence)

    def test_restored_host_capture_must_match_case_and_exact_installed_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            folder = evidence / 'audit-evidence'
            folder.mkdir()
            completed = ['dev.cinematv.HybridAuditUiTest#restore']
            value = dict(method='restore', capture='restore', expectedMode='hybrid',
                         webViewPresent=True, activity='dev.cinematv.MainActivity',
                         syntheticContent=True, installedApkSha256='a' * 64)
            (folder / 'restore.png').write_bytes(b'\x89PNG\r\n\x1a\n' + bytes(1100))
            metadata = folder / 'restore.json'
            metadata.write_text(json.dumps(value))
            self.assertEqual(set(verify_audit_captures(evidence, completed, 'a' * 64)), {'restore'})
            for field, invalid in [('installedApkSha256', 'b' * 64), ('webViewPresent', False),
                                   ('syntheticContent', False), ('method', 'different')]:
                metadata.write_text(json.dumps(dict(value, **{field: invalid})))
                with self.subTest(field=field), self.assertRaises(ValueError):
                    verify_audit_captures(evidence, completed, 'a' * 64)
            metadata.unlink()
            with self.assertRaisesRegex(ValueError, 'Missing'):
                verify_audit_captures(evidence, completed, 'a' * 64)


if __name__ == '__main__':
    unittest.main()
