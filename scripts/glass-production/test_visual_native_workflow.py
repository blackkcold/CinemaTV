"""Keep focused API31/35 acceptance on the visual workflow's single APK build."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class VisualNativeWorkflow(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / ".github/workflows/glass-visual-review.yml").read_text()
        self.native = self.workflow.split("  native-glass:\n", 1)[1]

    def test_two_native_jobs_reuse_the_single_build(self):
        self.assertEqual(["31", "35"], re.findall(r"^          - api: (\d+)$", self.native, re.M))
        for required in ("needs: build", "fail-fast: false", "actions/download-artifact@v4", "name: glass-visual-review-apks", "path: dist", 'evidence.py verify-build --directory dist --sha "$GITHUB_SHA"'):
            self.assertIn(required, self.native)
        self.assertEqual(1, self.workflow.count(":app:assembleDebug :app:assembleDebugAndroidTest"))
        self.assertLess(self.native.index("verify-build"), self.native.index("reactivecircus/android-emulator-runner@v2"))

    def test_native_host_and_bounded_runner_match_production(self):
        production = (ROOT / ".github/workflows/production-glass.yml").read_text()
        for required in ("runs-on: ubuntu-24.04", "timeout-minutes: 30", "sudo chmod 666 /dev/kvm", "api-level: ${{ matrix.api }}", "arch: x86_64", "profile: pixel_2", "emulator-build: '15004761'", "disable-animations: false", "-gpu lavapipe -feature -Vulkan -no-snapshot -no-boot-anim -memory 3072", 'script: bash scripts/glass-production/run-matrix.sh \'${{ matrix.api }}\' "$GITHUB_SHA"'):
            self.assertIn(required, production)
            self.assertIn(required, self.native)
        self.assertIn("target: default", self.native)
        runner = (ROOT / "scripts/glass-production/run-matrix.sh").read_text()
        self.assertIn("set -euo pipefail", runner)
        self.assertEqual(2, runner.count("timeout --signal=TERM --kill-after=10s 10m"))
        self.assertIn('--layouts tv\nif [[ "$1" == 35 ]]', runner)
        self.assertIn('--layouts phone', runner)

    def test_native_artifacts_are_distinct_always_saved_and_not_promotion(self):
        for required in ("if: always()", "name: glass-visual-review-native-api${{ matrix.api }}", "path: qa/production-glass/", "if-no-files-found: error"):
            self.assertIn(required, self.native)
        for forbidden in ("continue-on-error", "gradle/actions", "gradle --", "connectedDebugAndroidTest", "run-device-qa", "android_acceptance.py", "preview-release.py", "promotion-receipt", "screenrecord"):
            self.assertNotIn(forbidden, self.native)


if __name__ == "__main__":
    unittest.main()
