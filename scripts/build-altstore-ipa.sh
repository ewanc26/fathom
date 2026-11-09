#!/usr/bin/env bash
# Build an unsigned IPA for AltStore Classic.
#
# AltStore Classic signs the IPA with the user's own Apple ID through
# AltServer. No Apple Distribution certificate or provisioning profile is
# needed for this channel, and the resulting IPA is not an App Store export.
#
# Output: build/release/ios-altstore/Fathom-<version>-<build>.ipa

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

python3 scripts/sync-version.py --check

VERSION_NAME="$(awk -F= '$1 == "versionName" { print $2; exit }' version.properties)"
VERSION_CODE="$(awk -F= '$1 == "versionCode" { print $2; exit }' version.properties)"
if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
    echo "error: could not read the version from version.properties" >&2
    exit 1
fi

./scripts/build-xcframework.sh
(
    cd iOS
    xcodegen generate
)

OUT="$ROOT/build/release/ios-altstore"
ARCHIVE="$OUT/Fathom.xcarchive"
PAYLOAD="$OUT/Payload"
rm -rf "$OUT"
mkdir -p "$PAYLOAD"

(
    cd "$ROOT/iOS"
    xcodebuild archive \
        -project Fathom.xcodeproj \
        -scheme Fathom \
        -configuration Release \
        -destination 'generic/platform=iOS' \
        -archivePath "$ARCHIVE" \
        CODE_SIGNING_ALLOWED=NO \
        CODE_SIGNING_REQUIRED=NO \
        CODE_SIGN_IDENTITY="" \
        EXPANDED_CODE_SIGN_IDENTITY="" \
        PROVISIONING_PROFILE_SPECIFIER=""
)

APP="$ARCHIVE/Products/Applications/Fathom.app"
if [[ ! -d "$APP" ]]; then
    printf 'error: expected app bundle is missing: %s\n' "$APP" >&2
    exit 1
fi

cp -R "$APP" "$PAYLOAD/Fathom.app"

IPA_OUT="$OUT/Fathom-${VERSION_NAME}-${VERSION_CODE}.ipa"
(
    cd "$OUT"
    zip -qry "$(basename "$IPA_OUT")" Payload
)
rm -rf "$PAYLOAD"

TEMP_PLIST="$OUT/Info.plist"
unzip -p "$IPA_OUT" Payload/Fathom.app/Info.plist > "$TEMP_PLIST"
SHORT_VERSION="$(plutil -extract CFBundleShortVersionString raw -o - "$TEMP_PLIST")"
BUILD_NUMBER="$(plutil -extract CFBundleVersion raw -o - "$TEMP_PLIST")"
BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw -o - "$TEMP_PLIST")"
rm -f "$TEMP_PLIST"
if [[ "$SHORT_VERSION" != "$VERSION_NAME" || "$BUILD_NUMBER" != "$VERSION_CODE" ]]; then
    printf 'error: IPA has version %s (%s), expected %s (%s)\n' \
        "$SHORT_VERSION" "$BUILD_NUMBER" "$VERSION_NAME" "$VERSION_CODE" >&2
    exit 1
fi
if [[ "$BUNDLE_ID" != "uk.ewancroft.fathom" ]]; then
    printf 'error: IPA has bundle identifier %s\n' "$BUNDLE_ID" >&2
    exit 1
fi

(
    cd "$OUT"
    shasum -a 256 "$(basename "$IPA_OUT")" > SHA256SUMS
)

printf '\nUnsigned AltStore IPA for %s (%s):\n' "$VERSION_NAME" "$VERSION_CODE"
printf '  %s\n  %s\n' "$IPA_OUT" "$OUT/SHA256SUMS"
printf '\nAltStore Classic will re-sign this IPA with the user\x27s Apple ID.\n'
