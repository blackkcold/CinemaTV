"""Synthetic candidate-script contract. No keys, Android build or network."""
import json
import subprocess
import unittest
import test_unsigned_candidates as candidates

SCRIPT = candidates.ROOT / 'scripts/updates/build-douban-dev-candidate.sh'


class DoubanDevCandidateTest(unittest.TestCase):
    def setUp(self):
        self.fixture = candidates.UnsignedCandidateTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        for name in ('DoubanDeveloperOptions', 'DoubanFeatureAccess'):
            path = self.fixture.repo / f'app/src/main/java/dev/cinematv/data/douban/{name}.kt'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('// Synthetic source-presence fixture only\n')
        self.fixture.commit()

    def run_candidate(self, *args, **env):
        return subprocess.run(['bash', str(SCRIPT), *args], cwd=self.fixture.repo,
                              env={**self.fixture.env, **env}, capture_output=True, text=True)

    def test_reserved_dev12_receipt_preserves_unverified_boundaries(self):
        result = self.run_candidate()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        receipt = json.loads((self.fixture.repo / 'candidate-dev-account/unsigned-receipt.json').read_text())
        self.assertEqual(('dev', 12, '0.8.0-dev.2'),
                         (receipt['channel'], receipt['versionCode'], receipt['versionName']))
        self.assertTrue(receipt['personalAccountExperiment'])
        self.assertFalse(receipt['personalAccountDefaultEnabled'])
        self.assertFalse(receipt['liveAccountLoginVerified'])
        self.assertFalse(receipt['crossChannelMigrationVerified'])
        self.assertEqual(self.fixture.git('rev-parse', 'HEAD^{tree}'), receipt['sourceTree'])

    def test_refuses_stable_argument_and_any_signing_credential(self):
        self.assertNotEqual(0, self.run_candidate('stable').returncode)
        self.assertNotEqual(0, self.run_candidate(CINEMATV_KEYSTORE_PASSWORD='SYNTHETIC').returncode)
        self.assertFalse((self.fixture.sdk / 'gradle-args.json').exists())

    def test_refuses_valid_signature_or_changed_build_inputs(self):
        self.assertNotEqual(0, self.run_candidate(SYNTHETIC_VALID_SIGNATURE='1').returncode)
        self.assertNotEqual(0, self.run_candidate(SYNTHETIC_MUTATE_SOURCE='1').returncode)
