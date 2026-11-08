#!/usr/bin/env python3
"""Mirror version.properties into the generated iOS version fields.

Android reads version.properties directly. Xcode cannot, so the same values
are written into iOS/project.yml and iOS/Info.plist. Run this after a version
bump; `--check` fails when either file has drifted, which is what CI runs.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_FILE = ROOT / "version.properties"
IOS_PROJECT = ROOT / "iOS" / "project.yml"
IOS_PLIST = ROOT / "iOS" / "Info.plist"


def read_version() -> tuple[str, int]:
    values: dict[str, str] = {}
    for line_number, raw in enumerate(VERSION_FILE.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise SystemExit(f"{VERSION_FILE}:{line_number}: expected key=value")
        key, value = (part.strip() for part in line.split("=", 1))
        if key in values:
            raise SystemExit(f"{VERSION_FILE}:{line_number}: duplicate {key}")
        values[key] = value

    name = values.get("versionName", "")
    code_text = values.get("versionCode", "")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", name):
        raise SystemExit("versionName must be three dot-separated integers, for example 0.1.0")
    if not code_text.isdigit():
        raise SystemExit("versionCode must be a positive integer")
    code = int(code_text)
    if not 1 <= code <= 2_100_000_000:
        raise SystemExit("versionCode must be between 1 and 2100000000")
    return name, code


def replace_value(
    path: Path,
    pattern: str,
    replacement: str,
    label: str,
    check: bool,
    changes: list[str],
    flags: int = re.MULTILINE,
) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"Could not find {label} in {path.relative_to(ROOT)}")
    if updated != text:
        changes.append(f"{path.relative_to(ROOT)}: {label}")
        if not check:
            path.write_text(updated, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if the generated iOS version fields are out of date; write nothing",
    )
    args = parser.parse_args()

    name, code = read_version()
    code_text = str(code)
    changes: list[str] = []

    replace_value(
        IOS_PROJECT,
        r'^(\s*MARKETING_VERSION:\s*")[^"]*(")',
        rf"\g<1>{name}\g<2>",
        "MARKETING_VERSION",
        args.check,
        changes,
    )
    replace_value(
        IOS_PROJECT,
        r'^(\s*CURRENT_PROJECT_VERSION:\s*")[^"]*(")',
        rf"\g<1>{code_text}\g<2>",
        "CURRENT_PROJECT_VERSION",
        args.check,
        changes,
    )
    replace_value(
        IOS_PROJECT,
        r'^(\s*CFBundleShortVersionString:\s*")[^"]*(")',
        rf"\g<1>{name}\g<2>",
        "CFBundleShortVersionString",
        args.check,
        changes,
    )
    replace_value(
        IOS_PROJECT,
        r'^(\s*CFBundleVersion:\s*")[^"]*(")',
        rf"\g<1>{code_text}\g<2>",
        "CFBundleVersion",
        args.check,
        changes,
    )

    plist_flags = re.MULTILINE | re.DOTALL
    replace_value(
        IOS_PLIST,
        r"(<key>CFBundleShortVersionString</key>\s*<string>)[^<]*(</string>)",
        rf"\g<1>{name}\g<2>",
        "CFBundleShortVersionString",
        args.check,
        changes,
        plist_flags,
    )
    replace_value(
        IOS_PLIST,
        r"(<key>CFBundleVersion</key>\s*<string>)[^<]*(</string>)",
        rf"\g<1>{code_text}\g<2>",
        "CFBundleVersion",
        args.check,
        changes,
        plist_flags,
    )

    if args.check:
        if changes:
            print("Version files are out of sync with version.properties:", file=sys.stderr)
            for change in changes:
                print(f"  {change}", file=sys.stderr)
            print("Run python3 scripts/sync-version.py.", file=sys.stderr)
            return 1
        print(f"Version {name} ({code}) is in sync.")
        return 0

    if changes:
        print(f"Updated the iOS version fields to {name} ({code}):")
        for change in changes:
            print(f"  {change}")
    else:
        print(f"Version {name} ({code}) is already in sync.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
