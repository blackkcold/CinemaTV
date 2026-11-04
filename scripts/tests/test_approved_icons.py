"""Host checks for approved V3 source-to-native geometry. No Android SDK needed."""
import importlib.util
import unittest
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

    def test_no_filters_bitmaps_or_clipped_geometry(self):
        for source in (ROOT/'app/src/main/res/drawable').glob('cinema_v3_*.xml'):
            root=ET.parse(source).getroot()
            self.assertFalse(any(e.tag in ['bitmap','filter'] for e in root.iter()))
            for path in root.iter('path'):
                xmin,xmax,ymin,ymax=generator.BOUNDS[path.get(A+'pathData')]
                # Play's extra 1.4 translation remains within the same viewBox.
                if source.name.startswith('cinema_v3_play_'): xmin+=1.4; xmax+=1.4
                self.assertTrue(0<xmin<xmax<48,(source,xmin,xmax))
                self.assertTrue(0<ymin<ymax<48,(source,ymin,ymax))

if __name__=='__main__': unittest.main()
