#!/usr/bin/env bash
# check-apk-signer.sh — refuse APKs signed with the committed DEBUG keystore.
#
# Release builds must be signed with the owner's release keystore (from GitHub
# Actions secrets, see docs/RELEASE.md). This guard compares the APK's signer
# certificate SHA-256 against the committed debug.keystore certificate and
# fails loudly when they match, so a misconfigured pipeline can never ship a
# "release" that Android would treat as a debug build (and that could never be
# updated by the real release key).
#
# Usage (from the repository root):
#   scripts/check-apk-signer.sh <apk>
# Exits 0 when the APK is NOT debug-signed, 1 when it is.
#
# The printed signer SHA-256 is also what the owner records in their password
# manager after the first real release (see docs/RELEASE.md).

set -euo pipefail

APK="${1:?usage: scripts/check-apk-signer.sh <apk>}"
if [ ! -f "$APK" ]; then
  echo "ERROR: APK not found: $APK" >&2
  exit 2
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  echo "ERROR: ANDROID_HOME is not set (need build-tools/apksigner)" >&2
  exit 2
fi

APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner | sort -V | tail -n 1)"
if [ -z "$APKSIGNER" ]; then
  echo "ERROR: apksigner not found under $ANDROID_HOME/build-tools" >&2
  exit 2
fi

# First signer's certificate SHA-256, lowercase hex without colons.
APK_CERT="$("$APKSIGNER" verify --print-certs "$APK" \
  | grep -oP 'certificate SHA-256 digest: \K[0-9a-fA-F]+' | head -n 1 | tr 'A-F' 'a-f')"
if [ -z "$APK_CERT" ]; then
  echo "ERROR: could not read signer certificate from $APK" >&2
  exit 2
fi

# Certificate SHA-256 of the committed debug keystore (repo root).
if [ ! -f "debug.keystore" ]; then
  echo "ERROR: debug.keystore not found at repository root" >&2
  exit 2
fi
DEBUG_CERT="$(keytool -list -v -keystore debug.keystore -storepass byokai-debug \
  -alias byokai-debug 2>/dev/null \
  | grep -oP 'SHA256: \K[0-9A-F:]+' | tr -d ':' | tr 'A-F' 'a-f')"
if [ -z "$DEBUG_CERT" ]; then
  echo "ERROR: could not read certificate from debug.keystore" >&2
  exit 2
fi

echo "APK signer SHA-256:  $APK_CERT"
echo "Debug key SHA-256:   $DEBUG_CERT"
if [ "$APK_CERT" = "$DEBUG_CERT" ]; then
  echo "ERROR: $APK is signed with the committed DEBUG keystore." >&2
  echo "Releases must be signed with the owner's release keystore from GitHub" >&2
  echo "Secrets (docs/RELEASE.md). Refusing to treat this as a release." >&2
  exit 1
fi
echo "OK: APK is not signed with the debug key."
