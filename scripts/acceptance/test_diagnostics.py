import tempfile
import unittest
from pathlib import Path
from diagnostics import classify, failure_text


class DiagnosticTest(unittest.TestCase):
    def test_success_does_not_inherit_recovered_startup_warnings(self):
        result = classify('device offline\nno emulators found\nbooted', 'success')
        self.assertEqual(result['category'], 'no-execution-failure')
        self.assertFalse(result['automaticRetryAllowed'])

    def test_unknown_failure_remains_failure_and_cancel_is_incomplete(self):
        self.assertEqual(classify('', 'failure')['category'], 'unclassified-failure')
        self.assertEqual(classify('device offline', 'cancelled')['category'], 'cancelled-incomplete')

    def test_assertions_and_application_crashes_are_not_environment_retries(self):
        self.assertEqual(classify('oom-kill\nAssertionError', 'failure')['category'], 'test-or-application-failure')
        self.assertEqual(classify('device offline\nFATAL EXCEPTION', 'failure')['category'], 'application-or-window-failure')

    def test_log_and_live_kernel_evidence_are_classified(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'emulator.log').write_text('device offline')
            (root / 'live-environment.json').write_text('{"kernel":{"output":"oom-kill"}}')
            text, names = failure_text((root,))
            self.assertEqual(set(names), {'emulator.log', 'live-environment.json'})
            self.assertEqual(classify(text, 'failure')['category'], 'host-memory-pressure-evidence')

    def test_reads_are_bounded_and_ignore_links_and_generated_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'a.txt').write_text('x' * 100 + 'tail')
            (root / 'b.log').write_text('second')
            (root / 'c.txt').symlink_to(root / 'b.log')
            (root / 'environment.json').write_text('old misleading AssertionError')
            text, names = failure_text((root,), limit=1, tail_bytes=4)
            self.assertEqual((text, names), ('tail', ['a.txt']))
            text, names = failure_text((root,))
            self.assertEqual(names, ['a.txt', 'b.log'])
            self.assertNotIn('AssertionError', text)


if __name__ == '__main__': unittest.main()
