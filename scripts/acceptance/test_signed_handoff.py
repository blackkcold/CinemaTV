from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile
from signed_handoff import certificate_identity, compare_payload, manifest_identity, payload

CERT = '0798f1686a28ae4a386a376efcabba3a4c4bb547de760210d944a58455092e8c'
RELEASE = {'applicationId': 'dev.cinematv', 'versionCode': 13, 'previousPublicVersionCode': 12,
           'versionName': '0.9.0-dev.1', 'minSdk': 26}
XML = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.cinematv" android:versionCode="13" android:versionName="0.9.0-dev.1"><uses-sdk android:minSdkVersion="26"/><application android:label="CinemaTV Dev"><meta-data android:name="dev.cinematv.UPDATE_CHANNEL" android:value="dev"/></application></manifest>'''
BASE = {'AndroidManifest.xml': b'binary manifest', 'classes.dex': b'code',
        'assets/tv-web/index.html': b'production', 'META-INF/services/application': b'provider'}


def archive(path, data):
    with zipfile.ZipFile(path, 'w') as value:
        for name, content in data.items(): value.writestr(name, content)


class SignedHandoffTest(unittest.TestCase):
    def test_same_payload_and_only_new_jar_signature_metadata_are_allowed(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / 'unsigned.apk', Path(tmp) / 'signed.apk'
            archive(a, BASE)
            archive(b, {**BASE, 'META-INF/CERT.SF': b'sf', 'META-INF/CERT.RSA': b'rsa', 'META-INF/MANIFEST.MF': b'digest'})
            result = compare_payload(a, b)
            self.assertEqual(result['unchangedPayloadEntries'], len(BASE))
            self.assertEqual(len(result['addedSignatureEntries']), 3)

    def test_application_changes_and_meta_inf_resource_changes_are_rejected(self):
        for name in BASE:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                a, b = Path(tmp) / 'a.apk', Path(tmp) / 'b.apk'
                archive(a, BASE); archive(b, {**BASE, name: b'tampered'})
                with self.assertRaises(ValueError): compare_payload(a, b)

    def test_additional_payload_and_removed_files_are_rejected(self):
        for changed in ({**BASE, 'META-INF/injected': b'extra'}, {k: v for k, v in BASE.items() if not k.startswith('assets/')}):
            with tempfile.TemporaryDirectory() as tmp:
                a, b = Path(tmp) / 'a.apk', Path(tmp) / 'b.apk'
                archive(a, BASE); archive(b, changed)
                with self.assertRaises(ValueError): compare_payload(a, b)

    def test_existing_manifest_mf_cannot_be_silently_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / 'a.apk', Path(tmp) / 'b.apk'
            archive(a, {**BASE, 'META-INF/MANIFEST.MF': b'original'})
            archive(b, {**BASE, 'META-INF/MANIFEST.MF': b'changed'})
            with self.assertRaises(ValueError): compare_payload(a, b)

    def test_path_traversal_duplicate_and_symlink_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'bad.apk'
            for name in ('../escape', '/absolute', 'a\\b', 'a//b', 'a/./b'):
                archive(path, {**BASE, name: b'bad'})
                with self.subTest(name=name), self.assertRaises(ValueError): payload(path)
            archive(path, BASE)
            with warnings.catch_warnings(), zipfile.ZipFile(path, 'a') as value:
                warnings.simplefilter('ignore'); value.writestr('classes.dex', b'duplicate')
            with self.assertRaises(ValueError): payload(path)
            archive(path, BASE)
            with zipfile.ZipFile(path, 'a') as value:
                item = zipfile.ZipInfo('link'); item.external_attr = 0o120777 << 16
                value.writestr(item, b'classes.dex')
            with self.assertRaises(ValueError): payload(path)

    def test_original_certificate_is_required_and_source_stamp_is_not_signer(self):
        good = f'Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: {CERT}\n'
        self.assertEqual(certificate_identity(good, CERT), CERT)
        for bad in (good.replace(CERT, '1' * 64), good.replace('true', 'false'),
                    good + f'Signer #2 certificate SHA-256 digest: {CERT}\n',
                    good.replace('Signer #1', 'Source Stamp Signer'), ''):
            with self.subTest(bad=bad), self.assertRaises(ValueError): certificate_identity(bad, CERT)

    def test_current_and_previous_identity_are_separate(self):
        self.assertEqual(manifest_identity(XML, RELEASE)['versionCode'], 13)
        previous = XML.replace('versionCode="13"', 'versionCode="12"').replace('0.9.0-dev.1', '0.8.0-dev.2')
        self.assertEqual(manifest_identity(previous, RELEASE, previous=True)['versionCode'], 12)
        with self.assertRaises(ValueError): manifest_identity(previous, RELEASE)
        with self.assertRaises(ValueError): manifest_identity(XML, RELEASE, previous=True)

    def test_debug_wrong_channel_package_sdk_or_version_rejected(self):
        variants = [XML.replace('<application ', '<application android:debuggable="true" '),
                    XML.replace('<application ', '<application android:testOnly="true" '),
                    XML.replace('android:value="dev"', 'android:value="stable"'),
                    XML.replace('package="dev.cinematv"', 'package="other.app"'),
                    XML.replace('minSdkVersion="26"', 'minSdkVersion="29"'),
                    XML.replace('0.9.0-dev.1', '0.9.0-preview'),
                    '<!DOCTYPE x [<!ENTITY a "x">]>' + XML]
        for variant in variants:
            with self.subTest(xml=variant), self.assertRaises(ValueError): manifest_identity(variant, RELEASE)


if __name__ == '__main__': unittest.main()
