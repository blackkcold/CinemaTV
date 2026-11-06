import unittest
from phone import verify_results


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


if __name__ == '__main__':
    unittest.main()
