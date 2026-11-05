#!/usr/bin/env python3
"""Verify actual instrumentation/negative-fixture identity before uploading public test bytes."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

out, signer, analyzer = sys.argv[1:]
out = Path(out)
receipt = json.loads((out / 'fixture-receipt.json').read_text())
a = '{http://schemas.android.com/apk/res/android}'
expected_certificate = receipt['fixtures']['base']['certificateSha256']
inspected = {}
for name, package in [('repository-test', 'dev.cinematv.test'), ('wrong-package', 'dev.cinematv.wrongpackage'),
                      ('wrong-min-sdk', 'dev.cinematv')]:
    apk = out / (name + '.apk')
    signature = subprocess.check_output([signer, 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', signature)
    assert len(certs) == 1 and certs[0].lower() == expected_certificate
    root = ET.fromstring(subprocess.check_output([analyzer, 'manifest', 'print', str(apk)], text=True))
    assert root.get('package') == package
    if name == 'repository-test':
        instrumentation = root.find('instrumentation')
        assert instrumentation.get(a + 'targetPackage') == 'dev.cinematv'
        assert instrumentation.get(a + 'name') == 'androidx.test.runner.AndroidJUnitRunner'
    else:
        assert root.get(a + 'versionCode') == '101'
        assert root.get(a + 'versionName') == '0.0.0-stable.TEST-ONLY'
        assert root.find('uses-sdk').get(a + 'minSdkVersion') == ('25' if name == 'wrong-min-sdk' else '26')
        app = root.find('application')
        assert app.get(a + 'debuggable', 'false') == 'false'
        assert [m.get(a + 'value') for m in app.findall('meta-data') if m.get(a + 'name') == 'dev.cinematv.UPDATE_CHANNEL'] == ['stable']
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    inspected[name] = dict(packageName=package, certificateSha256=expected_certificate, sha256=digest)
    if name != 'repository-test':
        inspected[name]['minSdk'] = int(root.find('uses-sdk').get(a + 'minSdkVersion'))
receipt['repositoryAcceptance'] = inspected
(out / 'fixture-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
