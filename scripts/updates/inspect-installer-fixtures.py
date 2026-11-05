#!/usr/bin/env python3
"""Inspect actual signed test fixture bytes, never trust build argument intent."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

out, signer, analyzer = sys.argv[1:]
out = Path(out)
a = '{http://schemas.android.com/apk/res/android}'
fixtures = {}
for name, code, channel, version_suffix in [('base', 100, 'dev', 'base'), ('stable', 101, 'stable', 'stable'),
                                            ('dev', 102, 'dev', 'dev'), ('wrong-key', 102, 'dev', 'dev')]:
    apk = out / (name + '.apk')
    verified = subprocess.check_output([signer, 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', verified)
    assert len(certs) == 1
    root = ET.fromstring(subprocess.check_output([analyzer, 'manifest', 'print', str(apk)], text=True))
    app = root.find('application')
    assert root.get('package') == 'dev.cinematv'
    assert int(root.get(a+'versionCode')) == code
    assert root.get(a+'versionName') == f'0.0.0-{version_suffix}.TEST-ONLY'
    assert app.get(a+'debuggable', 'false') == 'false'
    assert [m.get(a+'value') for m in app.findall('meta-data') if m.get(a+'name') == 'dev.cinematv.UPDATE_CHANNEL'] == [channel]
    fixtures[name] = dict(packageName='dev.cinematv', versionCode=code, channel=channel,
                          versionName=root.get(a+'versionName'), debuggable=False,
                          certificateSha256=certs[0].lower(), sha256=hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest())
assert fixtures['base']['certificateSha256'] == fixtures['stable']['certificateSha256'] == fixtures['dev']['certificateSha256']
assert fixtures['wrong-key']['certificateSha256'] != fixtures['base']['certificateSha256']
identity = Path('release/signing-identity.json')
if identity.exists():
    assert all(f['certificateSha256'] != json.loads(identity.read_text())['certificateSha256'] for f in fixtures.values())
receipt = dict(schema=1, testOnly=True,
               sourceCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
               sourceTree=subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip(), fixtures=fixtures)
(out / 'fixture-receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
