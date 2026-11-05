"""Keep focused API31/35 acceptance on the visual workflow's single APK build."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class VisualNativeWorkflow(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / ".github/workflows/glass-visual-review.yml").read_text()
        self.native = self.workflow.split("  native-glass:\n", 1)[1]

    def test_native_only_skip_requires_the_exact_branch_ref(self):
        self.assertIn("branches: [feature/glass-visual-review, feature/glass-native-review]", self.workflow)
        canonical = self.workflow.split("  visual-review:\n", 1)[1].split("  native-glass:\n", 1)[0]
        conditions = re.findall(r"^    if: (.+)$", canonical, re.M)
        self.assertEqual(["${{ github.ref != 'refs/heads/feature/glass-native-review' }}"], conditions)
        branch = re.fullmatch(r"\$\{\{ github\.ref != '([^']+)' \}\}", conditions[0])[1]
        # Push and manual dispatch use the same ref predicate. Similar branch
        # names, tags and other manual refs must not silently skip canonical QA.
        for event in ("push", "workflow_dispatch"):
            for ref, should_capture in (("refs/heads/feature/glass-native-review", False),
                                        ("refs/heads/feature/glass-visual-review", True),
                                        ("refs/heads/feature/glass-native-review-extra", True),
                                        ("refs/tags/feature/glass-native-review", True),
                                        ("refs/heads/main", True)):
                with self.subTest(event=event, ref=ref):
                    self.assertEqual(should_capture, ref != branch)
        # The source build and focused native jobs remain required on both branches.
        for job in (self.workflow.split("  build:\n", 1)[1].split("  visual-review:\n", 1)[0], self.native):
            self.assertNotRegex(job.split("    steps:\n", 1)[0], r"(?m)^    if:")

    def test_native_only_summary_does_not_claim_current_commit_capture_or_release(self):
        build = self.workflow.split("  build:\n", 1)[1].split("  visual-review:\n", 1)[0]
        scope = build.split("      - name: Declare native-only diagnostic scope\n", 1)[1].split("      - uses:", 1)[0]
        self.assertIn("if: ${{ github.ref == 'refs/heads/feature/glass-native-review' }}", scope)
        for required in ("$GITHUB_SHA", "$GITHUB_REF", "$GITHUB_STEP_SUMMARY", "intentionally SKIPPED", "No canonical screenshots were executed or validated for this source SHA", "not screenshot approval or release acceptance", "Previous-commit screenshots do not validate this commit", "full four-device release matrix remains required"):
            self.assertIn(required, scope)
        self.assertLess(build.index("Declare native-only diagnostic scope"), build.index("actions/setup-java@v4"))

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
