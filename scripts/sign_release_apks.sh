#!/usr/bin/env bash
set -euo pipefail

APK_DIR="${APK_DIR:-app/build/outputs/apk/foss/release}"
KEYSTORE_FILE="${KEYSTORE_FILE:?KEYSTORE_FILE is required}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is required}"
KEY_ALIAS="${KEY_ALIAS:?KEY_ALIAS is required}"
KEY_PASSWORD="${KEY_PASSWORD:?KEY_PASSWORD is required}"
APKSIGNER="${APKSIGNER:-${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/36.0.0/apksigner}"

if [[ ! -f "$KEYSTORE_FILE" ]]; then
    echo "Keystore file not found: $KEYSTORE_FILE" >&2
    exit 1
fi

if [[ ! -x "$APKSIGNER" ]]; then
    echo "apksigner not found or not executable: $APKSIGNER" >&2
    exit 1
fi

found_apk=false
for unsigned_apk in "$APK_DIR"/*-unsigned.apk; do
    [[ -f "$unsigned_apk" ]] || continue
    found_apk=true
    signed_apk="${unsigned_apk/-unsigned/}"
    cp "$unsigned_apk" "$signed_apk"
    "$APKSIGNER" sign \
        --ks "$KEYSTORE_FILE" \
        --ks-key-alias "$KEY_ALIAS" \
        --ks-pass "env:KEYSTORE_PASSWORD" \
        --key-pass "env:KEY_PASSWORD" \
        "$signed_apk"
    "$APKSIGNER" verify --verbose "$signed_apk"
done

if [[ "$found_apk" != true ]]; then
    echo "No unsigned release APKs found in $APK_DIR" >&2
    exit 1
fi

echo "All release APKs signed and verified."
