#!/usr/bin/env python3
"""Write ExportOptions.plist for an App Store Connect export."""

from __future__ import annotations

import argparse
import plistlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "iOS" / "ExportOptions.plist"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--team", required=True, help="Apple Developer team ID")
    parser.add_argument("--profile", required=True, help="provisioning profile name")
    parser.add_argument("--output", required=True, type=Path, help="path to write")
    args = parser.parse_args()

    options = plistlib.loads(TEMPLATE.read_bytes())
    options["teamID"] = args.team
    options["provisioningProfiles"] = {"uk.ewancroft.fathom": args.profile}

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(plistlib.dumps(options, sort_keys=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
