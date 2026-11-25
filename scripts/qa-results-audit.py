#!/usr/bin/env python3
"""Fail closed on device-lane JUnit results.

This is the companion to preview-release.py's device evidence check: a lane may not be
green when it executed no tests, skipped a required class, failed/errored, or claims a TV
lane without a television build identity.
"""
import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def test_cases(path):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
    for suite in suites:
        yield from suite.findall("testcase")


def selector_matches(selector, classname, name):
    if "#" in selector:
        wanted_class, wanted_name = selector.split("#", 1)
        return (classname == wanted_class or classname.endswith("." + wanted_class)) and name == wanted_name
    return classname == selector or classname.endswith("." + selector)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", required=True)
    parser.add_argument("--require-executed", default="")
    parser.add_argument("--forbid-skipped", action="store_true")
    parser.add_argument("--require-tv", action="store_true")
    parser.add_argument("--characteristics", default="")
    args = parser.parse_args()

    root = Path(args.results)
    files = [root] if root.is_file() else sorted(root.rglob("TEST-*.xml"))
    errors = []
    if not files:
        errors.append("no-junit-xml")

    tests = failures = errors_count = skipped = 0
    seen = []
    for path in files:
        try:
            for case in test_cases(path):
                tests += 1
                classname = case.get("classname", "")
                name = case.get("name", "")
                seen.append((classname, name))
                if case.find("failure") is not None:
                    failures += 1
                if case.find("error") is not None:
                    errors_count += 1
                if case.find("skipped") is not None:
                    skipped += 1
        except ET.ParseError as error:
            errors.append(f"invalid-xml:{path}:{error}")

    if tests == 0:
        errors.append("no-tests")
    if failures:
        errors.append("failures")
    if errors_count:
        errors.append("errors")
    if args.forbid_skipped and skipped:
        errors.append("skipped")

    for selector in (part.strip() for part in args.require_executed.split(",")):
        if not selector:
            continue
        if not any(selector_matches(selector, classname, name) for classname, name in seen):
            errors.append(f"missing:{selector}")

    if args.require_tv and "tv" not in args.characteristics.lower():
        errors.append("not-tv")

    print(f"files={len(files)} tests={tests} failures={failures} errors={errors_count} skipped={skipped}")
    for error in errors:
        print(error)
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
