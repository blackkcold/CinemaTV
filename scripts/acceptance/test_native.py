import unittest
from pathlib import Path
from native import class_methods, declared_cases, profiles, ready_window
from diagnostics import classify
from manifest import ROOT, read


class NativeContractTest(unittest.TestCase):
    def test_multiple_classes_have_independent_methods(self):
        text = '''package example
class First { @Test fun first() { val s = "class Second { @Test fun fake() {} }" } }
class Second { /* @Test fun fake() {} */ @Test fun second() {} }
'''
        self.assertEqual(class_methods(text, 'example.First'), ['first'])
        self.assertEqual(class_methods(text, 'example.Second'), ['second'])
        self.assertIsNone(class_methods(text, 'other.First'))

    def test_plan_has_exact_profiles_and_configuration(self):
        plan = read(ROOT / 'scripts/acceptance/plan.json')['lanes']
        self.assertEqual(set(plan), set(profiles()))
        for lane, profile in profiles().items():
            self.assertEqual(profile['configuration'], plan[lane]['configuration'])
            if profile['kind'] == 'native':
                self.assertGreaterEqual(len(declared_cases(profile['classes'])), plan[lane]['minimumCases'])

    def test_unknown_class_and_method_fail_closed(self):
        for selector in ('dev.cinematv.Nonexistent', 'dev.cinematv.MobileNavigationSmokeTest#missing'):
            with self.assertRaises(ValueError): declared_cases(selector)

    def test_account_multiclass_file_keeps_all_seventeen(self):
        self.assertEqual(len(declared_cases(profiles()['account33']['classes'])), 17)

    def test_launcher_readiness_rejects_frozen_or_unfocused_window(self):
        text = 'mAwake=true mScreenOnFully=true mWindowManagerDrawComplete=true mCurrentFocus=Window{abc com.android.launcher3/.Launcher}'
        self.assertTrue(ready_window(text))
        self.assertFalse(ready_window(text + ' mDisplayFrozen=true'))
        self.assertFalse(ready_window(text.replace('com.android.launcher3', 'crash.dialog')))

    def test_assertion_is_never_reclassified_as_environment(self):
        result = classify('device offline\nFAILURES!!!\nAssertionError')
        self.assertEqual(result['category'], 'test-or-application-failure')
        self.assertFalse(result['automaticRetryAllowed'])

    def test_environment_classification_does_not_claim_unique_cause(self):
        self.assertEqual(classify('device offline')['category'], 'environment-or-startup-failure')
        self.assertFalse(classify('oom-kill')['automaticRetryAllowed'])
        self.assertEqual(classify('normal output')['category'], 'unclassified-or-no-failure')


if __name__ == '__main__': unittest.main()
