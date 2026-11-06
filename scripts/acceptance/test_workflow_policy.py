import unittest
import yaml
from workflow_policy import LOCK, affected_workflows, migrate


class WorkflowPolicyTest(unittest.TestCase):
    def fixture(self):
        return '''name: Existing workflow
on: workflow_dispatch
concurrency:
  group: caller-${{ github.ref }}
  cancel-in-progress: true
jobs:
  build:
    runs-on: ubuntu-24.04
    steps:
      - run: echo no emulator
  devices:
    # Preserve this explanation.
    runs-on: ubuntu-24.04
    strategy:
      fail-fast: false
      matrix:
        api: [26, 36]
    steps:
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: ${{ matrix.api }}
'''

    def test_adds_leaf_lock_matrix_limit_and_preserves_comments(self):
        result = migrate(self.fixture(), True)
        data = yaml.safe_load(result)
        self.assertEqual(data['jobs']['devices']['concurrency'], LOCK)
        self.assertEqual(data['jobs']['devices']['strategy']['max-parallel'], 1)
        self.assertFalse(data['concurrency']['cancel-in-progress'])
        self.assertEqual(data['concurrency']['queue'], 'max')
        self.assertNotIn('concurrency', data['jobs']['build'])
        self.assertIn('# Preserve this explanation.', result)
        self.assertEqual(migrate(result, True), result)

    def test_device_command_is_recognized(self):
        result = migrate(self.fixture().replace('uses: reactivecircus/android-emulator-runner@v2', 'run: bash scripts/hybrid/run-tv-emulator.sh'), True)
        self.assertEqual(yaml.safe_load(result)['jobs']['devices']['concurrency'], LOCK)

    def test_caller_and_leaf_never_share_lock(self):
        with self.assertRaisesRegex(ValueError, 'Outer workflow'):
            migrate(self.fixture().replace('caller-${{ github.ref }}', LOCK['group']), True)
        data = {'leaf.yml': yaml.safe_load(self.fixture()),
                'caller.yml': {'jobs': {'child': {'uses': './.github/workflows/leaf.yml'}}},
                'outer.yml': {'jobs': {'child': {'uses': './.github/workflows/caller.yml'}}},
                'host.yml': {'jobs': {'check': {'steps': [{'run': 'echo okay'}]}}}}
        self.assertEqual(affected_workflows(data), {'leaf.yml', 'caller.yml', 'outer.yml'})

    def test_replaces_incorrect_existing_lock_without_touching_steps(self):
        original = self.fixture().replace('    # Preserve this explanation.',
            '    concurrency:\n      group: old-lock\n      cancel-in-progress: true\n    # Preserve this explanation.')
        expected = yaml.safe_load(original)['jobs']['devices']['steps']
        result = yaml.safe_load(migrate(original, True))
        self.assertEqual(result['jobs']['devices']['steps'], expected)
        self.assertEqual(result['jobs']['devices']['concurrency'], LOCK)

    def test_source_only_workflow_is_unchanged(self):
        text = 'name: unit\non: push\njobs:\n  unit:\n    steps:\n      - run: python3 -m unittest\n'
        self.assertEqual(migrate(text, False), text)


if __name__ == '__main__': unittest.main()
