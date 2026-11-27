#!/usr/bin/env bash
# Builds the signed PKOS release on the OWNER'S OWN COMPUTER. The keystore and passwords never enter the repository.
# Usage:
#   export PKOS_RELEASE_STORE_FILE=/secure/path/pkos-release.jks
#   export PKOS_RELEASE_KEY_ALIAS=pkos
#   read -rs PKOS_RELEASE_STORE_PASSWORD; export PKOS_RELEASE_STORE_PASSWORD   # typed, not saved in shell history
#   ./scripts/build-signed-release.sh
set -euo pipefail
cd "$(dirname "$0")/.."
for v in PKOS_RELEASE_STORE_FILE PKOS_RELEASE_STORE_PASSWORD PKOS_RELEASE_KEY_ALIAS; do
  if [ -z "${!v:-}" ]; then echo "Missing $v (see docs/release-signing-and-oauth.md)"; exit 1; fi
done
case "$(realpath "$PKOS_RELEASE_STORE_FILE")" in
  "$(pwd)"/*) echo "Refusing: the keystore must live OUTSIDE the repository folder."; exit 1;;
esac
./gradlew --no-daemon clean :app:assembleRelease
./gradlew --no-daemon :app:bundleRelease
BT=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/* | sort -V | tail -1)
for apk in app/build/outputs/apk/release/*-release.apk; do
  "$BT/apksigner" verify --print-certs "$apk" | grep -E "SHA-256|SHA-1" | head -2
done
sha256sum app/build/outputs/apk/release/*.apk app/build/outputs/bundle/release/*.aab
echo "Keep app/build/outputs/mapping/release/mapping.txt with this release (needed to read crash stack traces)."
