#!/usr/bin/env python3
"""Verify the hosted OAuth client metadata for Fathom (issue #8).

The Authorization Server reads website/client-metadata.json from
https://fathom.ewancroft.uk, and both apps are built against the redirect
scheme declared in it. This script fails when the contract between the
metadata and the client drifts, and with --live also fails when the
deployed site does not serve what the metadata references.

Offline checks (default, no network):
  shape:  every required field present and correctly typed
  origin: client_id is its own https origin plus /client-metadata.json, and
          every referenced document is https on that same origin
  native: application_type native, token_endpoint_auth_method none, and the
          one redirect URI is uk.ewancroft.fathom:/oauth/callback

Live checks (--live):
  fetch:  the client_id URL and every *_uri return HTTP 200
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

REDIRECT_URIS = ["uk.ewancroft.fathom:/oauth/callback"]
REQUIRED_STRING_FIELDS = ["client_id", "client_name", "client_uri", "logo_uri", "tos_uri", "policy_uri", "scope"]


def metadata_path() -> Path:
    directory = Path(__file__).resolve().parent.parent
    path = directory / "website" / "client-metadata.json"
    if not path.is_file():
        sys.exit(f"error: {path} not found")
    return path


def origin(url: str) -> str:
    after_scheme = url.split("://", 1)[1]
    return after_scheme.split("/", 1)[0]


def check_shape(metadata: dict) -> list[str]:
    problems = []
    for field in REQUIRED_STRING_FIELDS:
        if not isinstance(metadata.get(field), str) or not metadata[field]:
            problems.append(f"field '{field}' must be a non-empty string")
    return problems


def check_contracts(metadata: dict) -> list[str]:
    problems = []
    client_id = metadata.get("client_id", "")
    if not client_id.startswith("https://"):
        problems.append(f"client_id must be https, got '{client_id}'")
    elif not client_id.endswith("/client-metadata.json"):
        problems.append(f"client_id must be the origin plus /client-metadata.json, got '{client_id}'")

    home = origin(client_id)
    for field in ("client_uri", "logo_uri", "tos_uri", "policy_uri"):
        url = metadata.get(field, "")
        if not isinstance(url, str) or not url.startswith("https://"):
            problems.append(f"{field} must be an https URL")
        elif origin(url) != home:
            problems.append(f"{field} must be on the client_id origin {home}, got '{url}'")

    if metadata.get("redirect_uris") != REDIRECT_URIS:
        problems.append(f"redirect_uris must be exactly {REDIRECT_URIS}, got {metadata.get('redirect_uris')!r}")
    if metadata.get("application_type") != "native":
        problems.append(f"application_type must be 'native', got {metadata.get('application_type')!r}")
    if metadata.get("token_endpoint_auth_method") != "none":
        problems.append("token_endpoint_auth_method must be 'none' for a public native client")
    if metadata.get("dpop_bound_access_tokens") is not True:
        problems.append("dpop_bound_access_tokens must be true")
    grants = set(metadata.get("grant_types", []))
    if {"authorization_code", "refresh_token"} - grants:
        problems.append("grant_types must include authorization_code and refresh_token")
    if metadata.get("response_types") != ["code"]:
        problems.append(f"response_types must be ['code'], got {metadata.get('response_types')!r}")
    return problems


def check_live(metadata: dict, timeout: float) -> list[str]:
    problems = []
    urls = [metadata.get("client_id", "")] + [metadata.get(f, "") for f in ("client_uri", "logo_uri", "tos_uri", "policy_uri")]
    for url in urls:
        if not url:
            problems.append("a referenced URL is missing; not fetched")
            continue
        try:
            with urllib.request.urlopen(url, timeout=timeout) as response:
                status = response.status
        except Exception as error:  # noqa: BLE001 - report the network failure, not its shape
            status = f"{error}"
        if status != 200:
            problems.append(f"{url} did not return 200 (got {status})")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="also fetch every referenced URL and require HTTP 200")
    parser.add_argument("--timeout", type=float, default=15.0, help="per-request timeout in seconds (live only)")
    args = parser.parse_args()

    metadata = json.loads(metadata_path().read_text())

    problems = check_shape(metadata) + check_contracts(metadata)
    if args.live:
        problems += check_live(metadata, args.timeout)

    if problems:
        print("client metadata check failed:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"client metadata ok ({metadata['client_id']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
