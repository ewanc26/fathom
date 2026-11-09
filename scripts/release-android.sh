#!/usr/bin/env bash
# Build the Play upload bundle and a side-loadable APK with a release key.
#
# Signing material is never read from the repository: set these four variables,
# or use Android/keystore.properties with storeFile/storePassword/keyAlias/
# keyPassword:
#   FATHOM_KEYSTORE_FILE
#   FATHOM_KEYSTORE_PASSWORD
#   FATHOM_KEY_ALIAS
#   FATHOM_KEY_PASSWORD
#
# Artifacts are written to build/release/android/.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# The Gradle build also reads Android/keystore.properties. Mirror it into the
# environment for the preflight checks below, without printing any value.
KEYSTORE_PROPERTIES="$ROOT/Android/keystore.properties"
if [[ -f "$KEYSTORE_PROPERTIES" ]]; then
    if [[ -z "${FATHOM_KEYSTORE_FILE:-}" ]]; then
        store_file="$(sed -n 's/^storeFile=//p' "$KEYSTORE_PROPERTIES" | head -n 1)"
        if [[ -n "$store_file" && "$store_file" != /* ]]; then
            store_file="$ROOT/Android/$store_file"
        fi
        export FATHOM_KEYSTORE_FILE="$store_file"
    fi
    export FATHOM_KEYSTORE_PASSWORD="${FATHOM_KEYSTORE_PASSWORD:-$(sed -n 's/^storePassword=//p' "$KEYSTORE_PROPERTIES" | head -n 1)}"
    export FATHOM_KEY_ALIAS="${FATHOM_KEY_ALIAS:-$(sed -n 's/^keyAlias=//p' "$KEYSTORE_PROPERTIES" | head -n 1)}"
    export FATHOM_KEY_PASSWORD="${FATHOM_KEY_PASSWORD:-$(sed -n 's/^keyPassword=//p' "$KEYSTORE_PROPERTIES" | head -n 1)}"
fi

for name in FATHOM_KEYSTORE_FILE FATHOM_KEYSTORE_PASSWORD FATHOM_KEY_ALIAS FATHOM_KEY_PASSWORD; do
    if [[ -z "${!name:-}" ]]; then
        printf 'error: %s is not set\n' "$name" >&2
        exit 1
    fi
done
if [[ ! -f "$FATHOM_KEYSTORE_FILE" ]]; then
    printf 'error: keystore not found: %s\n' "$FATHOM_KEYSTORE_FILE" >&2
    exit 1
fi

python3 scripts/sync-version.py --check

VERSION_NAME="$(awk -F= '$1 == "versionName" { print $2; exit }' version.properties)"
VERSION_CODE="$(awk -F= '$1 == "versionCode" { print $2; exit }' version.properties)"
if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
    echo "error: could not read the version from version.properties" >&2
    exit 1
fi

OUT="$ROOT/build/release/android"
rm -rf "$OUT"
mkdir -p "$OUT"

(
    cd Android
    ./gradlew \
        :shared:jvmTest \
        :app:testDebugUnitTest \
        :app:bundleRelease \
        :app:assembleRelease \
        --console=plain
)

AAB="$ROOT/Android/app/build/outputs/bundle/release/app-release.aab"
APK="$ROOT/Android/app/build/outputs/apk/release/app-release.apk"
for artifact in "$AAB" "$APK"; do
    if [[ ! -s "$artifact" ]]; then
        printf 'error: expected artifact is missing: %s\n' "$artifact" >&2
        exit 1
    fi
done

AAB_OUT="$OUT/Fathom-${VERSION_NAME}-${VERSION_CODE}.aab"
APK_OUT="$OUT/Fathom-${VERSION_NAME}-${VERSION_CODE}.apk"
cp "$AAB" "$AAB_OUT"
cp "$APK" "$APK_OUT"

jarsigner -verify "$AAB_OUT" >/dev/null

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f "$ROOT/Android/local.properties" ]]; then
    SDK_ROOT="$(sed -n 's/^sdk.dir=//p' "$ROOT/Android/local.properties")"
fi
if [[ -z "$SDK_ROOT" ]]; then
    echo "error: set ANDROID_SDK_ROOT or ANDROID_HOME to verify the APK signature" >&2
    exit 1
fi
APKSIGNER="$(find "$SDK_ROOT/build-tools" -type f -name apksigner -print 2>/dev/null | sort | tail -n 1)"
if [[ -z "$APKSIGNER" ]]; then
    echo "error: apksigner not found under $SDK_ROOT/build-tools" >&2
    exit 1
fi
"$APKSIGNER" verify --print-certs "$APK_OUT"

(
    cd "$OUT"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum Fathom-* > SHA256SUMS
    else
        shasum -a 256 Fathom-* > SHA256SUMS
    fi
)

printf '\nSigned Android artifacts for %s (%s):\n' "$VERSION_NAME" "$VERSION_CODE"
printf '  %s\n' "$AAB_OUT" "$APK_OUT" "$OUT/SHA256SUMS"
