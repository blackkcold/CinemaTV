#!/usr/bin/env python3
"""Verify an externally signed APK; never read keys, sign, install or publish."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from manifest import ROOT, digest, now, read, require, safe_file, source, write
sys.path.insert(0, str(ROOT / 'scripts/hybrid'))
from artifacts import config, require_unsigned_archive, verify_apk_assets

ANDROID = '{http://schemas.android.com/apk/res/android}'
SIGNATURE_ENTRY = re.compile(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)', re.I)
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024


def payload(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_ARCHIVE_BYTES, 'Invalid APK input')
    result = {}
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(entries and len(entries) <= 20000, 'Unexpected APK entry count')
        require(len(entries) == len({item.filename for item in entries}), 'Duplicate APK ZIP paths')
        require(sum(item.file_size for item in entries) <= MAX_ARCHIVE_BYTES, 'APK expands beyond bounded size')
        for item in entries:
            name = item.filename
            pure = PurePosixPath(name)
            require(name and '\\' not in name and '\0' not in name and not pure.is_absolute() and
                    '..' not in pure.parts and str(pure) == name.rstrip('/'), 'Invalid APK ZIP path')
            require(not stat.S_ISLNK(item.external_attr >> 16) and not item.flag_bits & 1, 'Symlink or encrypted APK entry')
            require(item.file_size <= 128 * 1024 * 1024, 'Oversized APK entry')
            if item.is_dir(): continue
            hasher = hashlib.sha256(); count = 0
            with archive.open(item) as stream:
                while block := stream.read(1024 * 1024):
                    count += len(block)
                    require(count <= item.file_size, 'ZIP entry size overflow')
                    hasher.update(block)
            require(count == item.file_size, 'APK ZIP size mismatch')
            result[name] = {'bytes': count, 'sha256': hasher.hexdigest()}
    require('AndroidManifest.xml' in result and 'classes.dex' in result, 'APK lacks manifest or application code')
    return result


def compare_payload(unsigned, signed):
    # Only newly added JAR signature metadata is exempt. Existing META-INF app
    # resources, the binary manifest, DEX, libraries and packaged Web bytes remain exact.
    original, final = payload(unsigned), payload(signed)
    require(set(original) <= set(final), 'Signed APK lost application entries')
    require(all(original[name] == final[name] for name in original), 'Signing changed application payload')
    additions = set(final) - set(original)
    require(all(SIGNATURE_ENTRY.fullmatch(name) for name in additions), 'Signed APK added non-signature payload')
    return {'unchangedPayloadEntries': len(original), 'addedSignatureEntries': sorted(additions),
            'payloadSha256': hashlib.sha256(json.dumps(original, sort_keys=True, separators=(',', ':')).encode()).hexdigest()}


def manifest_identity(text, release, previous=False):
    require('<!DOCTYPE' not in text.upper() and '<!ENTITY' not in text.upper(), 'DTD/entity declarations are prohibited')
    root = ET.fromstring(text)
    app, sdk = root.find('application'), root.find('uses-sdk')
    require(root.tag == 'manifest' and app is not None and sdk is not None, 'Incomplete APK manifest')
    expected_code = release['previousPublicVersionCode'] if previous else release['versionCode']
    require(root.get('package') == release['applicationId'] and root.get(ANDROID + 'versionCode') == str(expected_code), 'Wrong package or versionCode')
    if not previous:
        require(root.get(ANDROID + 'versionName') == release['versionName'], 'Wrong versionName')
    require(sdk.get(ANDROID + 'minSdkVersion') == str(release['minSdk']), 'Minimum Android support changed')
    require(app.get(ANDROID + 'debuggable', 'false') == 'false' and app.get(ANDROID + 'testOnly', 'false') == 'false', 'Not a production-installable APK')
    require(app.get(ANDROID + 'label') == 'CinemaTV Dev', 'Wrong launcher identity')
    channels = [node.get(ANDROID + 'value') for node in app.findall('meta-data')
                if node.get(ANDROID + 'name') == 'dev.cinematv.UPDATE_CHANNEL']
    require(channels == ['dev'], 'Wrong or ambiguous update channel')
    return {'applicationId': release['applicationId'], 'versionName': root.get(ANDROID + 'versionName'),
            'versionCode': expected_code, 'minSdk': release['minSdk'], 'channel': 'dev', 'debuggable': False}


def certificate_identity(output, expected):
    require(re.fullmatch(r'[0-9a-f]{64}', expected), 'Invalid pinned certificate fingerprint')
    matches = re.findall(r'^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$', output, re.M)
    require(matches == [('1', expected)] or matches == [('1', expected.upper())], 'Wrong, additional or missing signing certificate')
    require(re.search(r'^Verified using v2 scheme[^\n]*:\s*true\s*$', output, re.M), 'API26-compatible v2 verification is required')
    return expected


def checked_tool(command, evidence, name, budget=120):
    value = subprocess.run(command, timeout=budget, capture_output=True, text=True)
    log = value.stdout + value.stderr
    (evidence / name).write_text(log)
    require(value.returncode == 0, f'{name}: verification tool rejected the artifact')
    return log


def load_candidate(directory):
    receipt = read(directory / 'unsigned-receipt.json')
    expected = source()
    require(receipt['sourceCommit'] == expected['commit'] and receipt['sourceTree'] == expected['tree'], 'Unsigned candidate belongs to another source')
    require(receipt.get('status') == 'unsigned-not-installable-not-certified' and receipt.get('signedArtifactAcceptance') is False, 'Candidate is not an unsigned handoff')
    release = config(ROOT)
    for key in ('applicationId', 'channel', 'versionName', 'versionCode', 'minSdk'):
        require(receipt[key] == release[key], f'Candidate identity mismatch: {key}')
    require(Path(receipt['apk']).name == receipt['apk'], 'Candidate APK name must be a basename')
    apk = safe_file(directory, receipt['apk'])
    require(apk.stat().st_size == receipt['apkBytes'] and digest(apk) == receipt['unsignedApkSha256'], 'Unsigned APK bytes changed')
    require_unsigned_archive(apk)
    payload(apk)
    verify_apk_assets(apk, receipt['webAssets'])
    public = read(ROOT / 'release/signing-identity.json')
    require(public['applicationId'] == release['applicationId'] and receipt['expectedCertificateSha256'] == public['certificateSha256'], 'Candidate certificate differs from repository identity')
    return receipt, release, apk


def verify(directory, signed, output, sdk, previous=None):
    require(not output.exists(), 'Keep prior verification evidence; use a fresh directory')
    output.mkdir(parents=True)
    receipt, release, unsigned = load_candidate(directory)
    require(signed.resolve() != unsigned.resolve(), 'Do not overwrite the unsigned candidate')
    original_signed_hash = digest(signed)
    equivalent = compare_payload(unsigned, signed)
    signer = sdk / 'build-tools/36.0.0/apksigner'
    align = sdk / 'build-tools/36.0.0/zipalign'
    analyzers = sorted((sdk / 'cmdline-tools').glob('*/bin/apkanalyzer'))
    require(signer.is_file() and align.is_file() and analyzers, 'Android SDK build-tools 36.0.0 and apkanalyzer are required')
    analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
    if not analyzer.is_file(): analyzer = analyzers[-1]
    cert = receipt['expectedCertificateSha256']
    signature = checked_tool([str(signer), 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', str(signed)], output, 'signature.txt')
    certificate_identity(signature, cert)
    checked_tool([str(align), '-c', '-P', '16', '-v', '4', str(signed)], output, 'alignment.txt')
    decoded = checked_tool([str(analyzer), 'manifest', 'print', str(signed)], output, 'AndroidManifest.xml')
    identity = manifest_identity(decoded, release)
    verify_apk_assets(signed, receipt['webAssets'])
    prior = None
    if previous is not None:
        previous_hash = digest(previous)
        payload(previous)
        signature = checked_tool([str(signer), 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', str(previous)], output, 'previous-signature.txt')
        certificate_identity(signature, cert)
        decoded = checked_tool([str(analyzer), 'manifest', 'print', str(previous)], output, 'previous-AndroidManifest.xml')
        prior = {**manifest_identity(decoded, release, previous=True), 'apkSha256': previous_hash}
        require(digest(previous) == previous_hash, 'Previous APK changed during verification')
    require(digest(signed) == original_signed_hash, 'Signed APK changed during verification')
    result = {'schema': 1, 'source': source(), 'verifiedAt': now(),
              'status': 'signed-integrity-verified-device-acceptance-pending',
              'unsignedReceiptSha256': digest(directory / 'unsigned-receipt.json'),
              'unsignedApkSha256': receipt['unsignedApkSha256'], 'signedApkSha256': original_signed_hash,
              'signedApkBytes': signed.stat().st_size, 'certificateSha256': cert,
              'identity': identity, 'payloadComparison': equivalent, 'previous': prior,
              'privateKeyRead': False, 'signingPerformed': False, 'productionSignatureVerified': True,
              'signedArtifactAcceptance': False, 'upgradeAcceptance': False,
              'physicalDeviceCertified': False, 'publicationAllowed': False, 'branchOnly': True}
    write(output / 'signed-handoff.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate-directory', type=Path, required=True)
    parser.add_argument('--signed-apk', type=Path, required=True)
    parser.add_argument('--previous-apk', type=Path)
    parser.add_argument('--android-sdk', type=Path, default=Path(os.getenv('ANDROID_HOME', '/nonexistent')))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.candidate_directory, args.signed_apk, args.output, args.android_sdk, args.previous_apk)
        print(json.dumps(result, indent=2))
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile, ET.ParseError, subprocess.SubprocessError) as error:
        print(f'Signed handoff rejected: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__': sys.exit(main())
