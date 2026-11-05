#!/usr/bin/env python3
"""Pin one nondebuggable APK to a source commit; do not rebuild on the device job."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

PACKAGE = "dev.cinematv.glassbenchmark"
APK_NAME = "app-glassBenchmark.apk"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_build(directory, sha, aapt):
    assert re.fullmatch(r"[0-9a-f]{40}", sha), "A full real source commit is required"
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == sha
    subprocess.run(["git", "diff", "--quiet", "HEAD"], check=True)
    apk = directory / APK_NAME
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        assert "AndroidManifest.xml" in archive.namelist()
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    assert f"package: name='{PACKAGE}'" in badging, "Benchmark package must be isolated"
    assert "application-debuggable" not in badging, "APK is debuggable"
    (directory / "apk-badging.txt").write_text(badging)
    receipt = {"schema": 1, "sourceSha": sha, "apk": APK_NAME, "apkSha256": sha256(apk),
               "package": PACKAGE, "debuggable": False, "buildType": "glassBenchmark",
               "inspection": "aapt2 dump badging; no application-debuggable flag",
               "scope": "Same release-inherited APK for all Full/Blur/Basic runs. Debug signature is not debuggability."}
    (directory / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def verify_build(directory, sha):
    receipt = json.loads((directory / "build-receipt.json").read_text())
    assert receipt["schema"] == 1 and receipt["sourceSha"] == sha
    assert receipt["package"] == PACKAGE and receipt["debuggable"] is False
    assert receipt["apk"] == APK_NAME and receipt["buildType"] == "glassBenchmark"
    assert receipt["apkSha256"] == sha256(directory / APK_NAME), "APK does not match build receipt"
    badging = (directory / "apk-badging.txt").read_text()
    assert f"package: name='{PACKAGE}'" in badging and "application-debuggable" not in badging
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["build", "verify"])
    parser.add_argument("--directory", type=Path, default=Path("dist/glass-performance"))
    parser.add_argument("--sha", required=True)
    parser.add_argument("--aapt", type=Path)
    args = parser.parse_args()
    result = write_build(args.directory, args.sha, args.aapt) if args.operation == "build" else verify_build(args.directory, args.sha)
    print(json.dumps(result, indent=2))
