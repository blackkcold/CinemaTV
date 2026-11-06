import unittest
from workflow_policy import device_job


class EmulatorLaunchDetectionTest(unittest.TestCase):
    def test_syntax_check_does_not_take_device_lock(self):
        self.assertFalse(device_job({'steps': [{'run': 'bash -n scripts/hybrid/run-tv-emulator.sh\npython3 -m unittest'}]}))

    def test_comment_and_echo_do_not_take_device_lock(self):
        for command in ('# bash scripts/hybrid/run-tv-emulator.sh', 'echo bash scripts/hybrid/run-tv-emulator.sh'):
            self.assertFalse(device_job({'steps': [{'run': command}]}))

    def test_real_owned_launcher_and_action_take_lock(self):
        self.assertTrue(device_job({'steps': [{'run': 'set -e\nbash scripts/hybrid/run-tv-emulator.sh 36 x86_64'}]}))
        self.assertTrue(device_job({'steps': [{'uses': 'reactivecircus/android-emulator-runner@v2'}]}))


if __name__ == '__main__': unittest.main()
