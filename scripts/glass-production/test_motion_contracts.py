"""Ensure production motion tests really run without repeating the phone suite."""
from pathlib import Path
import re
import unittest
from run_device import MOTION, FOCUS_RESTORE, ACTION_LAYOUT, RAIL_TRAVERSAL, RAIL_VISIBILITY, MATERIAL, PRODUCTION, BUTTON, BUTTON_EDGE, expected_for

class MotionInstrumentationContract(unittest.TestCase):
    def test_all_real_motion_methods_are_required_on_each_tv_api(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "app/src/androidTest/java/dev/cinematv/FocusMediaCardMotionTest.kt").read_text()
        methods = re.findall(r"@Test\s+fun\s+(\w+)\(", source)
        self.assertEqual(10, len(methods))
        self.assertEqual(set(methods), {method for _, method in MOTION})
        self.assertEqual(42, len(expected_for("tv")))
        for pair in [PRODUCTION, BUTTON, BUTTON_EDGE, MATERIAL, *MOTION, *FOCUS_RESTORE, *ACTION_LAYOUT, *RAIL_TRAVERSAL, *RAIL_VISIBILITY]:
            self.assertEqual(1, expected_for("tv").count(pair))

    def test_interrupted_focus_restore_methods_are_required_on_each_tv_api(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "app/src/androidTest/java/dev/cinematv/InterruptedFocusRestorationTest.kt").read_text()
        methods = re.findall(r"@Test\s+fun\s+(\w+)\(", source)
        self.assertEqual(11, len(methods))
        self.assertEqual(set(methods), {method for _, method in FOCUS_RESTORE})

    def test_glass_action_layout_methods_run_on_each_tv_api(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "app/src/androidTest/java/dev/cinematv/GlassActionLayoutTest.kt").read_text()
        methods = re.findall(r"@Test\s+fun\s+(\w+)\(", source)
        self.assertEqual(6, len(methods))
        self.assertEqual(set(methods), {method for _, method in ACTION_LAYOUT})

    def test_real_rail_traversal_methods_run_on_each_tv_api(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "app/src/androidTest/java/dev/cinematv/ContentRailTraversalTest.kt").read_text()
        methods = re.findall(r"@Test\s+fun\s+(\w+)\(", source)
        self.assertEqual(7, len(methods))
        self.assertEqual(set(methods), {method for _, method in RAIL_TRAVERSAL})

    def test_real_rail_visibility_methods_run_on_each_tv_api(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "app/src/androidTest/java/dev/cinematv/ContentRailVisibilityTest.kt").read_text()
        methods = re.findall(r"@Test\s+fun\s+(\w+)\(", source)
        self.assertEqual(4, len(methods))
        self.assertEqual(set(methods), {method for _, method in RAIL_VISIBILITY})

    def test_phone_keeps_only_its_actual_root_and_touch_checks(self):
        self.assertEqual([PRODUCTION, BUTTON, BUTTON_EDGE], expected_for("phone"))

if __name__ == "__main__":
    unittest.main()
