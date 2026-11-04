"""All-class UTP export must retain every unique file and fail on collisions."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'merge-ui-evidence.py'
spec = importlib.util.spec_from_file_location('merge_ui_evidence', SCRIPT)
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class MergeUiEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'utp'
        self.destination = self.root / 'qa/ui-evidence'

    def evidence(self, test_class, name, content):
        path = self.source / test_class / 'ui-evidence' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def test_all_classes_nested_noto_and_identical_duplicates_are_merged(self):
        self.evidence('TvUiRegressionTest', '01-home.png', b'home')
        self.evidence('NotoFontsDeviceTest', 'noto/HK.png', b'HK')
        self.evidence('NotoFontsDeviceTest', 'noto/manifest.json', b'{}')
        self.evidence('AnotherUtpExport', '01-home.png', b'home')
        self.assertEqual(exporter.merge(self.source, self.destination), 3)
        self.assertEqual((self.destination / 'noto/HK.png').read_bytes(), b'HK')
        self.assertEqual((self.destination / '01-home.png').read_bytes(), b'home')
        self.assertEqual(exporter.merge(self.source, self.destination), 3)

    def test_conflicting_class_exports_fail_before_any_write(self):
        self.evidence('a', 'first.png', b'first')
        self.evidence('a', 'noto/HK.png', b'one')
        self.evidence('b', 'noto/HK.png', b'two')
        with self.assertRaisesRegex(ValueError, 'Conflicting evidence'):
            exporter.merge(self.source, self.destination)
        self.assertFalse(self.destination.exists())

    def test_existing_destination_never_overwritten(self):
        self.evidence('a', 'a.png', b'new')
        self.evidence('a', 'z.png', b'conflicting')
        self.destination.mkdir(parents=True)
        (self.destination / 'z.png').write_bytes(b'original')
        with self.assertRaisesRegex(ValueError, 'Conflicting destination'):
            exporter.merge(self.source, self.destination)
        self.assertEqual((self.destination / 'z.png').read_bytes(), b'original')
        self.assertFalse((self.destination / 'a.png').exists())

    def test_file_directory_collision_fails_before_write(self):
        self.evidence('a', 'noto', b'file')
        self.evidence('b', 'noto/HK.png', b'image')
        with self.assertRaisesRegex(ValueError, 'file/directory conflict'):
            exporter.merge(self.source, self.destination)
        self.assertFalse(self.destination.exists())

    def test_symlink_evidence_is_rejected(self):
        path = self.evidence('a', 'original.png', b'original')
        (path.parent / 'link.png').symlink_to(path)
        with self.assertRaisesRegex(ValueError, 'Symlink evidence'):
            exporter.merge(self.source, self.destination)
        self.assertFalse(self.destination.exists())

    def test_missing_output_is_allowed_for_classes_without_evidence(self):
        self.assertEqual(exporter.merge(self.source, self.destination), 0)
        self.assertFalse(self.destination.exists())


if __name__ == '__main__':
    unittest.main()
