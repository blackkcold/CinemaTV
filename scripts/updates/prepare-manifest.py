#!/usr/bin/env python3
"""Prepare public metadata from a signed APK. Does not read keys, sign, upload or publish."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
MAX_BYTES = 256 * 1024 * 1024


def prepare(apk, channel, url, fingerprint, notes, now, apksigner, apkanalyzer):
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise ValueError("Expected public certificate SHA-256 fingerprint, lowercase without colons")
    if channel not in ("stable", "dev"):
        raise ValueError("Unknown channel")
    if not re.fullmatch(r"https://github\.com/blackkcold/CinemaTV/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\.apk", url):
        raise ValueError("Use an immutable official release APK URL")
    if not 0 < apk.stat().st_size <= MAX_BYTES or len(notes) > 8000:
        raise ValueError("Invalid APK or notes size")
    verified = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", str(apk)],
                              check=True, text=True, capture_output=True).stdout
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", verified)
    if len(signers) != 1 or signers[0].lower() != fingerprint:
        raise ValueError("APK must have exactly the approved fixed signing certificate")
    manifest = ET.fromstring(subprocess.run([apkanalyzer, "manifest", "print", str(apk)],
                                            check=True, text=True, capture_output=True).stdout)
    app = manifest.find("application")
    sdk = manifest.find("uses-sdk")
    if app is None or sdk is None or manifest.get("package") != "dev.cinematv":
        raise ValueError("Wrong package or missing manifest data")
    if app.get(ANDROID + "debuggable", "false") != "false":
        raise ValueError("Debug APKs must not enter update feeds")
    channels = [entry.get(ANDROID + "value") for entry in app.findall("meta-data")
                if entry.get(ANDROID + "name") == "dev.cinematv.UPDATE_CHANNEL"]
    if channels != [channel]:
        raise ValueError("APK channel does not match selected channel")
    version = int(manifest.get(ANDROID + "versionCode", "0"))
    name = manifest.get(ANDROID + "versionName", "")
    minimum = int(sdk.get(ANDROID + "minSdkVersion", "0"))
    if not 0 < version <= 2100000000 or not 0 < len(name) <= 80 or minimum < 26:
        raise ValueError("Invalid version or minimum SDK")
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return dict(schema=1, channel=channel, versionCode=version, versionName=name,
                applicationId="dev.cinematv", minSdk=minimum, apkUrl=url,
                apkBytes=apk.stat().st_size, sha256=digest,
                publishedAt=now, expiresAt=now + 7 * 86400, notes=notes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--channel", choices=("stable", "dev"), required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--certificate-sha256", required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--apkanalyzer", required=True)
    args = parser.parse_args()
    result = prepare(args.apk, args.channel, args.url, args.certificate_sha256,
                     args.notes.read_text(encoding="utf-8"), int(time.time()), args.apksigner, args.apkanalyzer)
    args.output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print("Public unsigned payload prepared; review, sign outside GitHub, then validate before publishing.")


if __name__ == "__main__":
    main()
