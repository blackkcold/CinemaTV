#!/usr/bin/env python3
"""Offline byte/face verification. Standard library only; run after prepareNotoFonts."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[2]


def table(data, face_offset, tag):
    count = struct.unpack_from('>H', data, face_offset + 4)[0]
    for i in range(count):
        name, _, offset, size = struct.unpack_from('>4sIII', data, face_offset + 12 + 16 * i)
        if name == tag:
            return offset, size
    raise AssertionError(f'Missing SFNT table: {tag}')


def names(data, face_offset):
    start, _ = table(data, face_offset, b'name')
    _, count, strings = struct.unpack_from('>HHH', data, start)
    result = {}
    for i in range(count):
        platform, _, _, name_id, length, offset = struct.unpack_from('>HHHHHH', data, start + 6 + 12 * i)
        if platform == 3:
            result[name_id] = data[start + strings + offset:start + strings + offset + length].decode('utf-16-be')
    return result


def verify(directory):
    lock = json.loads((ROOT / 'scripts/fonts/noto-fonts.lock.json').read_text())
    total = compressed = 0
    for font in lock['fonts']:
        data = (directory / font['name']).read_bytes()
        assert len(data) == font['size'], font['name']
        assert hashlib.sha256(data).hexdigest() == font['sha256'], font['name']
        total += len(data)
        compressed += len(zlib.compress(data, 6))
        if font['name'].endswith('.ttc'):
            assert data[:4] == b'ttcf'
            count = struct.unpack_from('>I', data, 8)[0]
            assert count == 10
            offsets = struct.unpack_from('>' + 'I' * count, data, 12)
            for i, region in enumerate(('JP', 'KR', 'SC', 'TC', 'HK')):
                info = names(data, offsets[i])
                assert info[1] == f'Noto Sans CJK {region}', info
                assert '2.004' in info[5]
            # Large glyph program is shared, not duplicated once per region.
            assert len({table(data, offset, b'CFF ') for offset in offsets}) == 1
        else:
            assert data[:4] == b'\x00\x01\x00\x00'
            assert '2.008' in names(data, 0)[5]
        print(f"PASS {font['name']}: {len(data):,} bytes, pinned SHA256")
    for license_name in ('OFL-NotoSans.txt', 'OFL-NotoSansCJK.txt'):
        license_text = (ROOT / 'app/src/main/assets/fonts' / license_name).read_text()
        assert 'SIL OPEN FONT LICENSE Version 1.1' in license_text
        assert 'Copyright' in license_text
    print(f'PASS regional indexes, shared CFF, versions, bundled OFL. Total {total:,} bytes; DEFLATE estimate {compressed:,} bytes (not measured APK growth).')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--assets', type=Path, default=ROOT / 'app/build/generated/notoAssets/fonts')
    verify(parser.parse_args().assets)
