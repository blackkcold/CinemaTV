import unittest
import struct
import tempfile
from pathlib import Path
from phone import expected_cases, verify_phone_captures, verify_results


class PhoneResultTests(unittest.TestCase):
    def log(self, pairs, total=None):
        text = ''.join(f'INSTRUMENTATION_STATUS: class={name}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: 0\n' for name, method in pairs)
        return text + f'OK ({len(pairs) if total is None else total} tests)\nINSTRUMENTATION_CODE: -1\n'

    def test_all_cases_are_required_with_class_identity(self):
        expected = {'first.Class#same', 'second.Class#same'}
        log = self.log([('first.Class', 'same'), ('second.Class', 'same')])
        self.assertEqual(set(verify_results(log, expected)), expected)
        for bad in [self.log([('first.Class', 'same')]), self.log([('first.Class', 'same')] * 2),
                    log.replace('second.Class', 'other.Class'), log.replace('CODE: 0', 'CODE: -3'),
                    log.replace('OK (2 tests)', 'OK (1 test)'), log.replace('INSTRUMENTATION_CODE: -1', ''),
                    log + 'Process crashed\n']:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                verify_results(bad, expected)

    def test_zero_exit_or_success_text_is_not_evidence(self):
        for text in ['', 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n']:
            with self.assertRaises(ValueError):
                verify_results(text, {'Class#case'})

    def test_method_selection_requires_a_declared_case(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'app/src/androidTest/java/dev/cinematv/Fixture.kt'
            source.parent.mkdir(parents=True)
            source.write_text('@Test fun phone() {}\n@Test fun television() {}\n')
            self.assertEqual(expected_cases(root, {'Fixture#phone': 'Fixture.kt'}), {'Fixture#phone'})
            with self.assertRaisesRegex(ValueError, 'Unknown declared'):
                expected_cases(root, {'Fixture#removed': 'Fixture.kt'})

    def test_both_named_phone_orientations_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            folder = evidence / 'ui-evidence'
            folder.mkdir()
            def write(name, width, height):
                # Synthetic header fixture; not a device acceptance screenshot.
                (folder / name).write_bytes(b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' +
                                           struct.pack('>II', width, height) + bytes(1100))
            write('mobile-player-portrait.png', 1080, 1920)
            with self.assertRaisesRegex(ValueError, 'capture missing'):
                verify_phone_captures(evidence)
            write('mobile-player-landscape.png', 1920, 1080)
            self.assertEqual(len(verify_phone_captures(evidence)), 2)
            write('mobile-player-landscape.png', 1080, 1920)
            with self.assertRaisesRegex(ValueError, 'orientation'):
                verify_phone_captures(evidence)


if __name__ == '__main__':
    unittest.main()
