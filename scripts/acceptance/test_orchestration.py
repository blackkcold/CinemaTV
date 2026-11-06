import unittest
import yaml
from manifest import ROOT, read


class OrchestrationTest(unittest.TestCase):
    def test_declared_lanes_are_executed_in_exact_order(self):
        plan = read(ROOT / 'scripts/acceptance/plan.json')['lanes']
        jobs = yaml.safe_load((ROOT / '.github/workflows/hybrid-acceptance.yml').read_text())['jobs']
        previous = 'build'
        for name in plan:
            job = jobs[name]
            needs = job['needs']; needs = [needs] if isinstance(needs, str) else needs
            self.assertEqual(set(needs), {'build', previous})
            self.assertEqual(job['uses'], './.github/workflows/acceptance-device.yml')
            self.assertEqual(job['with']['lane'], name)
            self.assertIn('!cancelled()', job['if'])
            self.assertNotIn('concurrency', job)
            previous = name
        self.assertEqual(list(plan)[-2:], ['native4k', 'hybrid4k'])
        self.assertTrue(set(plan) <= set(jobs['acceptance']['needs']))

    def test_device_job_owns_one_global_lock_and_has_no_compilation(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/acceptance-device.yml').read_text())
        self.assertNotIn('concurrency', workflow)
        job = workflow['jobs']['device']
        self.assertEqual(job['concurrency'], {'group': 'cinematv-emulator-exclusive', 'cancel-in-progress': False, 'queue': 'max'})
        text = '\n'.join(str(step.get('run', '')) for step in job['steps'])
        self.assertNotIn('assemble', text)
        self.assertNotIn('gradle ', text)
        self.assertIn('if: always()', (ROOT / '.github/workflows/acceptance-device.yml').read_text())

    def test_branch_workflow_cannot_publish_or_merge(self):
        for path in ('hybrid-acceptance.yml', 'acceptance-build.yml', 'acceptance-device.yml'):
            text = (ROOT / '.github/workflows' / path).read_text()
            self.assertNotIn('gh release ', text)
            self.assertNotIn('gh pr merge', text)
            self.assertNotIn('git push', text)
        text = (ROOT / '.github/workflows/hybrid-acceptance.yml').read_text()
        self.assertIn('branches: [fix/hybrid-acceptance-serial-qa]', text)


if __name__ == '__main__': unittest.main()
