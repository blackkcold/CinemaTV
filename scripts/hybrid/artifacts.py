#!/usr/bin/env python3
"""Bind hybrid build/device evidence to exact public source and APK bytes.

This tool never signs, publishes, reads a keystore or certifies physical hardware.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def config(root):
    value = json.loads((root / "scripts/hybrid/release.json").read_text())
    require(value.get("schema") == 1 and value.get("channel") == "dev", "Only the reserved dev candidate is supported")
    require(re.fullmatch(r"\d+\.\d+\.\d+-dev\.\d+", value.get("versionName", "")), "Invalid dev version")
    require(type(value.get("versionCode")) is int and
            type(value.get("previousPublicVersionCode")) is int and
            0 < value["previousPublicVersionCode"] < value["versionCode"] < 2_100_000_000,
            "Candidate must advance the global public versionCode")
    require(value.get("applicationId") == "dev.cinematv" and value.get("minSdk") == 26,
            "Hybrid candidate must preserve package and minimum SDK")
    return value


def clean_source(root):
    require(not git(root, "status", "--porcelain", "--untracked-files=all"), "Candidate requires a clean source tree")
    require(not git(root, "ls-files", "--others", "--ignored", "--exclude-standard",
                    "app/src", "backdrop/src", "frontend/tv-web/src", "frontend/tv-web/public",
                    "frontend/tv-web/.env", "frontend/tv-web/.env.local",
                    "frontend/tv-web/.env.production", "frontend/tv-web/.env.production.local"),
            "Untracked ignored files may not alter source inputs")
    git(root, "ls-files", "--error-unmatch", "frontend/tv-web/package-lock.json")
    current = git(root, "rev-parse", "HEAD")
    expected = os.environ.get("EXPECTED_SOURCE_SHA", current)
    require(re.fullmatch(r"[0-9a-f]{40}", expected) and current == expected,
            "Checkout differs from the expected exact source commit")
    return current


def web_inventory(root):
    dist = root / "frontend/tv-web/dist"
    require((dist / "index.html").is_file(), "Missing production Web UI; run npm ci and npm run build")
    files = {}
    for path in sorted(dist.rglob("*")):
        require(not path.is_symlink(), "Web build may not contain symlinks")
        if path.is_file():
            relative = path.relative_to(dist).as_posix()
            files[relative] = {"bytes": path.stat().st_size, "sha256": sha256(path)}
    require(files, "Web build is empty")
    return files


def verify_apk_assets(apk, expected):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "APK contains duplicate ZIP paths")
        require(archive.testzip() is None, "APK ZIP integrity check failed")
        actual = {name.removeprefix("assets/tv-web/"): name for name in names
                  if name.startswith("assets/tv-web/") and not name.endswith("/")}
        require(set(actual) == set(expected), "APK Web asset inventory differs from the tested production build")
        for relative, metadata in expected.items():
            content = archive.read(actual[relative])
            require(len(content) == metadata["bytes"] and hashlib.sha256(content).hexdigest() == metadata["sha256"],
                    f"APK Web asset bytes differ: {relative}")


def require_unsigned_archive(apk):
    """A broken signature is not an unsigned APK suitable for the handoff."""
    with zipfile.ZipFile(apk) as archive:
        require(not any(re.fullmatch(r"META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", name, re.IGNORECASE)
                        for name in archive.namelist()), "Unsigned candidate contains v1 signature material")
        # Android's v2/v3 signing block immediately precedes the ZIP central directory.
        # The trailing magic remains present even if that block's signature is invalid.
        if archive.start_dir >= 24:
            with Path(apk).open("rb") as stream:
                stream.seek(archive.start_dir - 24)
                footer = stream.read(24)
            require(not footer.endswith(b"APK Sig Block 42"), "Unsigned candidate contains an APK signing block")


def source_receipt(root):
    result = {"schema": 1, "sourceCommit": git(root, "rev-parse", "HEAD"),
              "sourceTree": git(root, "rev-parse", "HEAD^{tree}"),
              "frontendSourceTree": git(root, "rev-parse", "HEAD:frontend/tv-web"),
              "applicationSourceTree": git(root, "rev-parse", "HEAD:app/src"),
              "applicationBuildBlob": git(root, "rev-parse", "HEAD:app/build.gradle.kts"),
              "lockfileSha256": sha256(root / "frontend/tv-web/package-lock.json"),
              "webAssets": web_inventory(root)}
    if os.environ.get("GITHUB_ACTIONS") == "true":
        result["workflow"] = {key: os.environ.get(variable, "") for key, variable in (
            ("repository", "GITHUB_REPOSITORY"), ("runId", "GITHUB_RUN_ID"),
            ("runAttempt", "GITHUB_RUN_ATTEMPT"), ("event", "GITHUB_EVENT_NAME"))}
    return result


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x") as output:
        json.dump(value, output, indent=2, ensure_ascii=False)
        output.write("\n")


def candidate_receipt(root, apk, manifest_path, output):
    release = config(root)
    require_unsigned_archive(apk)
    a = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(manifest_path).getroot()
    app, sdk = manifest.find("application"), manifest.find("uses-sdk")
    require(app is not None and sdk is not None, "Incomplete APK manifest")
    require(manifest.get("package") == release["applicationId"] and
            manifest.get(a + "versionName") == release["versionName"] and
            manifest.get(a + "versionCode") == str(release["versionCode"]) and
            sdk.get(a + "minSdkVersion") == str(release["minSdk"]), "APK release identity does not match reservation")
    require(app.get(a + "debuggable", "false") == "false", "Public candidate must not be debuggable")
    require(app.get(a + "label") == "CinemaTV Dev", "Wrong candidate launcher label")
    require([node.get(a + "value") for node in app.findall("meta-data")
             if node.get(a + "name") == "dev.cinematv.UPDATE_CHANNEL"] == ["dev"], "Wrong packaged channel")
    receipt = source_receipt(root)
    verify_apk_assets(apk, receipt["webAssets"])
    identity = json.loads((root / "release/signing-identity.json").read_text())
    require(identity.get("applicationId") == release["applicationId"] and
            re.fullmatch(r"[0-9a-f]{64}", identity.get("certificateSha256", "")), "Invalid public certificate identity")
    require(not output.exists(), "Candidate output already exists; preserve its receipt")
    output.mkdir(parents=True)
    filename = f'CinemaTV-{release["versionName"]}-code{release["versionCode"]}-dev-UNSIGNED.apk'
    shutil.copyfile(apk, output / filename)
    shutil.copyfile(manifest_path, output / "AndroidManifest.xml")
    receipt.update({key: release[key] for key in ("channel", "versionCode", "versionName", "applicationId", "minSdk")})
    receipt.update(status="unsigned-not-installable-not-certified", artifactLabel="NOT FOR INSTALLATION",
                   apk=filename, apkBytes=Path(apk).stat().st_size, unsignedApkSha256=sha256(apk),
                   expectedCertificateSha256=identity["certificateSha256"], updatesEnabled=True,
                   hybridTv=True, personalAccountDefaultEnabled=False,
                   signedArtifactAcceptance=False, physicalTvAcceptance=False)
    write_json(output / "unsigned-receipt.json", receipt)
    (output / "NOT-FOR-INSTALLATION.txt").write_text(
        "NOT FOR INSTALLATION. Public unsigned Dev candidate for the existing external signer.\n"
        "No private key, production signature, signed-APK acceptance or publication is included.\n"
        "Signing changes the APK bytes; verify the final identity and test those final bytes.\n")
    (output / "SHA256SUMS.txt").write_text(f'{receipt["unsignedApkSha256"]}  {filename}\n')
    return receipt


def device_bundle(root, apk, test_apk, output):
    receipt = source_receipt(root)
    verify_apk_assets(apk, receipt["webAssets"])
    require(not output.exists(), "Device bundle already exists")
    output.mkdir(parents=True)
    receipt.update(status="debug-synthetic-test-only", physicalTvAcceptance=False,
                   signedArtifactAcceptance=False, files={})
    for name, source in (("app-debug.apk", apk), ("app-debug-androidTest.apk", test_apk)):
        shutil.copyfile(source, output / name)
        receipt["files"][name] = {"bytes": Path(source).stat().st_size, "sha256": sha256(source)}
    write_json(output / "build-receipt.json", receipt)
    return receipt


def verify_device_bundle(root, folder):
    receipt = json.loads((folder / "build-receipt.json").read_text())
    require(receipt["sourceCommit"] == git(root, "rev-parse", "HEAD") and
            receipt["sourceTree"] == git(root, "rev-parse", "HEAD^{tree}"), "Device checkout differs from built source")
    require(set(receipt["files"]) == {"app-debug.apk", "app-debug-androidTest.apk"}, "Unexpected device bundle inventory")
    for name, metadata in receipt["files"].items():
        path = folder / name
        require(path.stat().st_size == metadata["bytes"] and sha256(path) == metadata["sha256"], "Device APK digest mismatch")
    verify_apk_assets(folder / "app-debug.apk", receipt["webAssets"])
    return receipt


def instrumentation_results(log, expected_methods):
    """adb exit zero alone is not an instrumentation success criterion."""
    require(not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED", log),
            "Instrumentation reported a crash or failure")
    statuses, completed, case = [], [], {}
    for line in log.splitlines():
        match = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
        if match:
            case[match[1]] = match[2].strip()
        status = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if status:
            code = int(status[1])
            statuses.append(code)
            if code == 0:
                require(case.get("class") == "dev.cinematv.HybridHostUiTest", "Unexpected instrumentation test class")
                completed.append(case.get("test"))
            case = {}
    require(statuses and all(code in (0, 1, 2) for code in statuses), "Failed, skipped or assumed instrumentation test")
    require(len(completed) == len(set(completed)) and set(completed) == set(expected_methods),
            "Not every required hybrid device case executed successfully")
    count = re.search(r"OK \((\d+) tests?\)", log)
    require(count and int(count.group(1)) == len(expected_methods), "Missing or wrong instrumentation success count")
    require(re.search(r"INSTRUMENTATION_CODE: -1(?:\s|$)", log), "Instrumentation did not finish successfully")
    return completed


def device_result(root, bundle, evidence, mode):
    receipt = verify_device_bundle(root, bundle)
    source = (root / "app/src/androidTest/java/dev/cinematv/HybridHostUiTest.kt").read_text()
    methods = re.findall(r"@Test\s+fun\s+(\w+)", source)
    require(methods, "No declared hybrid device tests")
    completed = instrumentation_results((evidence / "instrumentation.txt").read_text(), methods)
    screenshots = sorted((evidence / "hybrid-evidence").glob("*.png"))
    require(screenshots, "No actual device screenshot was exported")
    for path in screenshots:
        require(path.stat().st_size > 1024 and path.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n", "Invalid device PNG")
    cases = {}
    for method in methods:
        png = evidence / "hybrid-evidence" / f"{method}.png"
        metadata = evidence / "hybrid-evidence" / f"{method}.json"
        require(png.is_file() and metadata.is_file(), "Missing per-case device evidence")
        case = json.loads(metadata.read_text())
        expected_mode = mode
        if mode == "provider-policy":
            require(case.get("requestedMode") == mode and case.get("api") == 29,
                    "Provider-policy lane must exercise API29 with its actual WebView")
            provider_major = str(case.get("provider") or "").split(".")[0]
            enabled = provider_major.isdigit() and int(provider_major) >= 111 and case.get("messageListener") is True
            expected_mode = "hybrid" if enabled else "native-fallback"
        require(case.get("method") == method and case.get("expectedMode") == expected_mode,
                "Device evidence method or presentation differs from requested lane")
        require(case.get("activity") == "dev.cinematv.MainActivity" and case.get("syntheticContent") is True,
                "Device evidence did not exercise the declared production host")
        api = case.get("api")
        require(isinstance(api, int) and (api == 29 if mode == "provider-policy" else api >= 29 if mode == "hybrid" else api == 26),
                "Device evidence API differs from requested lane")
        cases[method] = {"screenshotSha256": sha256(png), "metadataSha256": sha256(metadata)}
    receipt.update(status="passed-synthetic-device-tests", expectedPresentation=mode,
                   completedTests=completed, deviceMetadata=(evidence / "device.txt").read_text(),
                   testEvidence=cases,
                   screenshots={path.name: sha256(path) for path in screenshots},
                   physicalTvAcceptance=False, signedArtifactAcceptance=False)
    write_json(evidence / "device-receipt.json", receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check-source")
    web = sub.add_parser("web-receipt")
    web.add_argument("--output", type=Path, required=True)
    candidate = sub.add_parser("candidate")
    candidate.add_argument("--apk", type=Path, required=True)
    candidate.add_argument("--manifest", type=Path, required=True)
    candidate.add_argument("--output", type=Path, required=True)
    device = sub.add_parser("device-bundle")
    device.add_argument("--apk", type=Path, required=True)
    device.add_argument("--test-apk", type=Path, required=True)
    device.add_argument("--output", type=Path, required=True)
    verify = sub.add_parser("verify-device-bundle")
    verify.add_argument("--directory", type=Path, required=True)
    result = sub.add_parser("device-result")
    result.add_argument("--bundle", type=Path, required=True)
    result.add_argument("--evidence", type=Path, required=True)
    result.add_argument("--mode", choices=("hybrid", "native-fallback", "provider-policy"), required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command == "check-source":
        print(clean_source(root))
    elif args.command == "web-receipt":
        write_json(args.output, source_receipt(root))
    elif args.command == "candidate":
        candidate_receipt(root, args.apk, args.manifest, args.output)
    elif args.command == "device-bundle":
        device_bundle(root, args.apk, args.test_apk, args.output)
    elif args.command == "verify-device-bundle":
        verify_device_bundle(root, args.directory)
    elif args.command == "device-result":
        device_result(root, args.bundle, args.evidence, args.mode)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        sys.exit(f"Hybrid artifact gate: {error}")
