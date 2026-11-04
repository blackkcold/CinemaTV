"""Host checks for approved V3 source-to-native geometry. No Android SDK needed."""
import importlib.util
import unittest
import math
from decimal import Decimal
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('icon_generator', ROOT/'scripts/icons/generate_vectors.py')
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)
A = '{http://schemas.android.com/apk/res/android}'

class ApprovedIconsTest(unittest.TestCase):
    def vector(self, name, tier):
        return ET.parse(ROOT/f'app/src/main/res/drawable/cinema_v3_{name}_{tier}.xml').getroot()

    def test_generated_resources_match_sources(self):
        for tier,folder in [('default','compact/default'),('selected','compact/selected'),('mono','monochrome')]:
            sources=list((ROOT/'design/icons-v3/svg'/folder).glob('*.svg'))
            self.assertEqual(10,len(sources))
            for source in sources:
                with self.subTest(icon=source.stem,tier=tier):
                    actual=ROOT/f'app/src/main/res/drawable/cinema_v3_{source.stem}_{tier}.xml'
                    self.assertEqual(generator.convert(source),actual.read_text())
                    root=self.vector(source.stem,tier)
                    self.assertEqual('48',root.get(A+'viewportWidth'))
                    self.assertEqual('48',root.get(A+'viewportHeight'))
                    self.assertEqual(str(source.stem=='back').lower(),root.get(A+'autoMirrored'))

    def test_selected_geometry_is_identical(self):
        for source in (ROOT/'design/icons-v3/svg/compact/default').glob('*.svg'):
            def paths(tier):
                return [(p.get(A+'pathData'),p.get(A+'fillType')) for p in self.vector(source.stem,tier).iter('path')]
            self.assertEqual(paths('default'),paths('selected'))

    def test_apertures_and_optical_offset_survive(self):
        for name in ['search','settings','info']:
            self.assertEqual('evenOdd',next(self.vector(name,'default').iter('path')).get(A+'fillType'))
        play=self.vector('play','default')
        self.assertEqual('1.4',next(play.iter('group')).get(A+'translateX'))
        library=self.vector('library','default')
        self.assertEqual(3,len(list(library.iter('path'))))
        self.assertEqual('evenOdd',next(self.vector('library','mono').iter('path')).get(A+'fillType'))

    def test_precision_reduction_has_bounded_continuous_geometry_error(self):
        changed = []
        for original in generator.BOUNDS:
            compact = generator.compact_path(original)
            if original == compact:
                continue
            changed.append(original)
            before = generator.COMMAND.findall(original)
            after = generator.COMMAND.findall(compact)
            self.assertEqual(len(before), len(after))
            for (command, args), (new_command, new_args) in zip(before, after):
                self.assertEqual(command, new_command)
                if command not in ('M', 'L', 'Q'):
                    self.assertEqual(args, new_args)
                    continue
                old = [Decimal(x) for x in generator.NUMBER.findall(args)]
                new = [Decimal(x) for x in generator.NUMBER.findall(new_args)]
                self.assertEqual(len(old), len(new))
                self.assertEqual(0, len(old) % 2)
                for i in range(0, len(old), 2):
                    dx, dy = abs(old[i]-new[i]), abs(old[i+1]-new[i+1])
                    self.assertLessEqual(dx, Decimal('0.00005'))
                    self.assertLessEqual(dy, Decimal('0.00005'))
                    self.assertLessEqual(math.hypot(float(dx), float(dy)), .001)
            # Only the absolute M/L/Q/Z outer Settings contour is changed. For
            # lines/closing edges and quadratic Beziers, each point is a convex
            # combination of endpoints/control points. Thus the same <= sqrt(2)
            # * 0.00005 bound holds continuously, not just at sampled points.
            outer, inner = original.split('Z M', 1)
            new_outer, new_inner = compact.split('Z M', 1)
            self.assertEqual(inner, new_inner)  # Exact aperture, including arcs.
            self.assertTrue(set(c for c, _ in generator.COMMAND.findall(outer)) <= {'M', 'L', 'Q'})
            self.assertLess(len(compact), 3000)
        self.assertEqual(1, len(changed))  # One shared Settings path; all other geometry exact.

    def test_media3_overrides_are_explicit_and_limited(self):
        root = ET.parse(ROOT/'app/src/main/res/values/cinema_media3_icons.xml').getroot()
        self.assertEqual({'exo_styled_controls_play', 'exo_styled_controls_pause'},
                         {item.get('name') for item in root})
        for item in root:
            self.assertEqual('true', item.get('{http://schemas.android.com/tools}override'))
            self.assertEqual('drawable', item.get('type'))
            name = item.get('name').removeprefix('exo_styled_controls_')
            self.assertEqual(f'@drawable/cinema_v3_{name}_default', item.text)

    def test_no_filters_bitmaps_or_clipped_geometry(self):
        for source in (ROOT/'app/src/main/res/drawable').glob('cinema_v3_*.xml'):
            root=ET.parse(source).getroot()
            self.assertFalse(any(e.tag in ['bitmap','filter'] for e in root.iter()))
            for path in root.iter('path'):
                bounds = {generator.compact_path(d): b for d, b in generator.BOUNDS.items()}
                xmin,xmax,ymin,ymax=bounds[path.get(A+'pathData')]
                # Include the proven coordinate-rounding envelope.
                xmin-=.001; xmax+=.001; ymin-=.001; ymax+=.001
                # Play's extra 1.4 translation remains within the same viewBox.
                if source.name.startswith('cinema_v3_play_'): xmin+=1.4; xmax+=1.4
                self.assertTrue(0<xmin<xmax<48,(source,xmin,xmax))
                self.assertTrue(0<ymin<ymax<48,(source,ymin,ymax))

if __name__=='__main__': unittest.main()
