"""Host source/palette guardrails, not a replacement for native Compose layout tests."""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/dev/cinematv/ui/CinemaWidgets.kt"
NATIVE = ROOT / "app/src/androidTest/java/dev/cinematv/GlassActionLayoutTest.kt"


def luminance(argb):
    channels = [(argb >> shift & 255) / 255 for shift in (16, 8, 0)]
    linear = [value / 12.92 if value <= .04045 else ((value + .055) / 1.055) ** 2.4
              for value in channels]
    return sum(value * weight for value, weight in zip(linear, (.2126, .7152, .0722)))


def contrast(a, b):
    return (max(a, b) + .05) / (min(a, b) + .05)


class GlassActionSelectionTest(unittest.TestCase):
    def test_selection_stays_on_the_existing_native_button(self):
        source = SOURCE.read_text().split("fun GlassAction(", 1)[1].split("fun TvInput(", 1)[0]
        self.assertIn(".semantics { this.selected = selected }", source)
        self.assertIn("role = Role.Button, onClick = onClick", source)
        self.assertIn(".heightIn(min = 40.dp)", source)
        self.assertIn("selected = selected, prominent = prominent, layerBlock", source)
        self.assertNotIn("label +", source)
        self.assertNotIn("✓", source)

    def test_marker_is_conditional_non_interactive_and_outside_label_measurement(self):
        source = SOURCE.read_text().split("fun GlassAction(", 1)[1].split("fun TvInput(", 1)[0]
        marker = source.split("if (selected) {", 1)[1]
        self.assertLess(source.index("Row("), source.index("if (selected) {"))
        for required in ("Box(Modifier.matchParentSize())", "Alignment.BottomCenter",
                         "offset(y = (-1).dp)", ".size(width = 16.dp, height = 4.dp)",
                         '.testTag("glass-action-selection-indicator")',
                         ".background(Ink, RoundedCornerShape(50))", ".padding(1.dp)",
                         ".background(Ivory, RoundedCornerShape(50))"):
            self.assertIn(required, marker)
        for forbidden in ("focus =", "press =", "effects.", "graphicsLayer", "animate", "clickable",
                          "focusable", "cinemaGlassMaterial", "saveLayer", "drawBackdrop"):
            self.assertNotIn(forbidden, marker)

    def test_opaque_two_tone_palette_has_contrast_over_any_backdrop_luminance(self):
        source = SOURCE.read_text()
        colors = [int(re.search(r"val " + name + r" = Color\(0x([0-9A-F]+)\)", source)[1], 16)
                  for name in ("Ink", "Ivory")]
        self.assertTrue(all(color >> 24 == 255 for color in colors))
        dark, light = map(luminance, colors)
        self.assertGreater(contrast(dark, light), 15)
        # At least one part of the opaque rim/core pair remains distinguishable
        # even when a Full/Blur scene has any luminance between black and white.
        for background in (index / 1000 for index in range(1001)):
            self.assertGreater(max(contrast(dark, background), contrast(light, background)), 4)

    def test_native_contract_covers_reselection_compact_layout_and_real_dpad(self):
        source = NATIVE.read_text()
        for required in ("fun selectedStatePreservesCompactBoundsLabelsAndIconSemantics()",
                         "fun selectionRemainsIndependentOfNativeFocusPressAndReducedMotion()",
                         "listOf(false, true, false)", "listOf(false, true).forEachIndexed",
                         "SemanticsProperties.Selected, isSelected", "selected.value = false",
                         "selected.value = true", "KeyEvent.KEYCODE_DPAD_CENTER",
                         'selectionIndicator("first").assertDoesNotExist()',
                         'button("icon-only").assertContentDescriptionEquals("打开搜索")',
                         "Selection must not cover label pixels", "Focus/press must not remeasure"):
            self.assertIn(required, source)
        for forbidden in ("captureToImage(", "PixelCopy", "takeScreenshot("):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
