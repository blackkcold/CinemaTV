#!/usr/bin/env python3
"""Export the original A1's static transparent layers; Android animates translation only."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'design/branding/cinematv-tv-banner.svg'
DESTINATION = ROOT / 'app/src/main/res/drawable-nodpi'


def layer_svg(layer):
    root = ET.parse(SOURCE).getroot()
    for child in list(root):
        if child.tag.split('}')[-1] not in ('defs', 'title', 'desc') and child.get('id') != f'{layer}-scale':
            root.remove(child)
    ET.register_namespace('', 'http://www.w3.org/2000/svg')
    return ET.tostring(root, encoding='unicode')


if __name__ == '__main__':
    rsvg, inkscape = shutil.which('rsvg-convert'), shutil.which('inkscape')
    if not (rsvg or inkscape):
        raise SystemExit('Install librsvg2-bin or Inkscape to render SVG Gaussian blur faithfully.')
    with tempfile.TemporaryDirectory() as tmp:
        for layer in ('slow', 'medium', 'fast'):
            source = Path(tmp) / f'{layer}.svg'
            source.write_text(layer_svg(layer))
            target = DESTINATION / f'cinema_brand_{layer}.png'
            command = ([rsvg, '-w', '640', '-h', '360', str(source), '-o', str(target)] if rsvg else
                       [inkscape, str(source), '--export-type=png', f'--export-filename={target}',
                        '--export-width=640', '--export-height=360'])
            subprocess.run(command, check=True)
