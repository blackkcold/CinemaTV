#!/usr/bin/env python3
"""Prepare the exact existing Latin font bytes for test-side production asset routing."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
lock = json.loads((ROOT / 'scripts/fonts/noto-fonts.lock.json').read_text())
folder = ROOT / '.cache/cinematv-noto-fonts'
folder.mkdir(parents=True, exist_ok=True)
for item in lock['fonts']:
    if item['name'] not in ('NotoSans-Regular.ttf', 'NotoSans-Bold.ttf'):
        continue
    path = folder / item['name']
    if not path.is_file() or path.stat().st_size != item['size'] or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
        with urllib.request.urlopen(item['url'], timeout=60) as response:
            data = response.read(item['size'] + 1)
        if len(data) != item['size'] or hashlib.sha256(data).hexdigest() != item['sha256']:
            raise SystemExit('Pinned font validation failed')
        temporary = path.with_suffix('.download')
        temporary.write_bytes(data); temporary.replace(path)
print('Pinned Latin font bytes verified; fonts are not copied into the Web dist.')
