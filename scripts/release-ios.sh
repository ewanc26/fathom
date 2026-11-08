#!/usr/bin/env bash
# Archive and export an App Store Connect IPA with a manually installed
# distribution certificate and provisioning profile.
#
# Required variables:
#   IOS_TEAM_ID                       Apple Developer team ID
#   IOS_PROVISIONING_PROFILE_SPECIFIER
#                                      name of the installed App Store profile
#
# The certificate must already be in the login keychain and the profile in
# ~/Library/MobileDevice/Provisioning Profiles. GitHub Actions imports both
# before calling this script. Artifacts go to build/release/ios/.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${IOS_TEAM_ID:?Set IOS_TEAM_ID to the Apple Developer team ID}"
: "${IOS_PROVISIONING_PROFILE_SPECIFIER:?Set IOS_PROVISIONING_PROFILE_SPECIFIER to the installed profile name}"

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

OUT="$ROOT/build/release/ios"
ARCHIVE="$OUT/Fathom.xcarchive"
EXPORT="$OUT/export"
rm -rf "$OUT"
mkdir -p "$OUT"

(
    cd "$ROOT/iOS"
    xcodebuild archive \
        -project Fathom.xcodeproj \
        -scheme Fathom \
        -configuration Release \
        -destination 'generic/platform=iOS' \
        -archivePath "$ARCHIVE" \
        DEVELOPMENT_TEAM="$IOS_TEAM_ID" \
        CODE_SIGN_STYLE=Manual \
        CODE_SIGN_IDENTITY="Apple Distribution" \
        PROVISIONING_PROFILE_SPECIFIER="$IOS_PROVISIONING_PROFILE_SPECIFIER"
)

python3 scripts/make-export-options.py \
    --team "$IOS_TEAM_ID" \
    --profile "$IOS_PROVISIONING_PROFILE_SPECIFIER" \
    --output "$OUT/ExportOptions.plist"

xcodebuild -exportArchive \
    -archivePath "$ARCHIVE" \
    -exportPath "$EXPORT" \
    -exportOptionsPlist "$OUT/ExportOptions.plist"

shopt -s nullglob
ipas=("$EXPORT"/*.ipa)
if (( ${#ipas[@]} != 1 )); then
    printf 'error: expected one IPA in %s, found %d\n' "$EXPORT" "${#ipas[@]}" >&2
    exit 1
fi

IPA_OUT="$OUT/Fathom-${VERSION_NAME}-${VERSION_CODE}.ipa"
cp "${ipas[0]}" "$IPA_OUT"

TEMP_PLIST="$OUT/Info.plist"
unzip -p "$IPA_OUT" Payload/Fathom.app/Info.plist > "$TEMP_PLIST"
SHORT_VERSION="$(plutil -extract CFBundleShortVersionString raw -o - "$TEMP_PLIST")"
BUILD_NUMBER="$(plutil -extract CFBundleVersion raw -o - "$TEMP_PLIST")"
rm -f "$TEMP_PLIST"
if [[ "$SHORT_VERSION" != "$VERSION_NAME" || "$BUILD_NUMBER" != "$VERSION_CODE" ]]; then
    printf 'error: IPA has version %s (%s), expected %s (%s)\n' \
        "$SHORT_VERSION" "$BUILD_NUMBER" "$VERSION_NAME" "$VERSION_CODE" >&2
    exit 1
fi

(
    cd "$OUT"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum Fathom-*.ipa > SHA256SUMS
    else
        shasum -a 256 Fathom-*.ipa > SHA256SUMS
    fi
)

printf '\nSigned iOS artifact for %s (%s):\n' "$VERSION_NAME" "$VERSION_CODE"
printf '  %s\n  %s\n' "$IPA_OUT" "$OUT/SHA256SUMS"
