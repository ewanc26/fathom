#!/usr/bin/env bash
# Generate Fathom's two independent release keys on this machine.
#
#   Android/release-upload.jks   signs the APK users install
#   Android/fdroid-repo/keystore.p12   signs the F-Droid index
#
# Every generated file is gitignored. The script refuses to overwrite an
# existing key, configuration or properties file. Back up all four files after
# it finishes; neither key can be recovered from GitHub.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FDROID_DIR="$ROOT/Android/fdroid-repo"
APP_KEY="$ROOT/Android/release-upload.jks"
APP_PROPS="$ROOT/Android/keystore.properties"
REPO_KEY="$FDROID_DIR/keystore.p12"
REPO_CONFIG="$FDROID_DIR/config.yml"

for file in "$APP_KEY" "$APP_PROPS" "$REPO_KEY" "$REPO_CONFIG"; do
    if [[ -e "$file" ]]; then
        printf 'error: %s already exists; refusing to overwrite signing material\n' \
            "${file#"$ROOT"/}" >&2
        exit 1
    fi
done

for tool in keytool openssl; do
    command -v "$tool" >/dev/null 2>&1 || {
        printf 'error: %s is required\n' "$tool" >&2
        exit 1
    }
done

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f "$ROOT/Android/local.properties" ]]; then
    SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "$ROOT/Android/local.properties")"
fi
if [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT" ]]; then
    printf 'error: set ANDROID_SDK_ROOT/ANDROID_HOME or Android/local.properties sdk.dir\n' >&2
    exit 1
fi

random_password() {
    openssl rand -hex 24
}

fingerprint() {
    keytool -list -v \
        -keystore "$1" \
        -storepass "$2" \
        -alias "$3" 2>/dev/null \
        | sed -n 's/^[[:space:]]*SHA256: //p' \
        | head -n 1 \
        | tr -d ':' \
        | tr '[:lower:]' '[:upper:]'
}

APP_PASSWORD="$(random_password)"
REPO_PASSWORD="$(random_password)"

umask 077
mkdir -p "$FDROID_DIR"

keytool -genkeypair \
    -keystore "$APP_KEY" \
    -storetype PKCS12 \
    -storepass "$APP_PASSWORD" \
    -keypass "$APP_PASSWORD" \
    -alias fathom \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=Fathom, OU=Android, O=Ewan Croft, C=GB" \
    >/dev/null

keytool -genkeypair \
    -keystore "$REPO_KEY" \
    -storetype PKCS12 \
    -storepass "$REPO_PASSWORD" \
    -keypass "$REPO_PASSWORD" \
    -alias fathom-repo \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=Fathom F-Droid Repository, OU=F-Droid, O=Ewan Croft, C=GB" \
    >/dev/null

cat > "$APP_PROPS" <<EOF
storeFile=release-upload.jks
storePassword=$APP_PASSWORD
keyAlias=fathom
keyPassword=$APP_PASSWORD
EOF

cat > "$REPO_CONFIG" <<EOF
repo_url: https://fathom.ewancroft.uk/fdroid/repo
repo_name: Fathom
repo_description: A read-only teal.fm dashboard for Android.
archive_older: 10
repo_icon: fathom-icon.png
archive_icon: fathom-icon.png
sdk_path: $SDK_ROOT
keystore: keystore.p12
keystorepass: $REPO_PASSWORD
keypass: $REPO_PASSWORD
repo_keyalias: fathom-repo
keydname: CN=Fathom F-Droid Repository, OU=F-Droid, O=Ewan Croft, C=GB
EOF

chmod 600 "$APP_KEY" "$APP_PROPS" "$REPO_KEY" "$REPO_CONFIG"

printf '\nFathom release keys created. Public fingerprints:\n'
printf '  APK signing key:       %s\n' "$(fingerprint "$APP_KEY" "$APP_PASSWORD" fathom)"
printf '  F-Droid repo index key: %s\n' "$(fingerprint "$REPO_KEY" "$REPO_PASSWORD" fathom-repo)"
printf '\nSecret files (gitignored):\n'
printf '  %s\n  %s\n  %s\n  %s\n' \
    "${APP_KEY#"$ROOT"/}" "${APP_PROPS#"$ROOT"/}" \
    "${REPO_KEY#"$ROOT"/}" "${REPO_CONFIG#"$ROOT"/}"
printf '\nBack them up now. Losing the APK key prevents upgrades; losing the\n'
printf 'repo key prevents index updates.\n'
