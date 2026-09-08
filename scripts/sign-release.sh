#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${ANDROID_HOME:?Android SDK is required}"
: "${DICTATE_KEYSTORE_BASE64:?Release keystore is required}"
: "${DICTATE_STORE_PASSWORD:?Release password is required}"
signing_dir=$(mktemp -d)
trap 'rm -f "$signing_dir/release.p12"; rmdir "$signing_dir"' EXIT
printf '%s' "$DICTATE_KEYSTORE_BASE64" | base64 --decode > "$signing_dir/release.p12"
mkdir -p build/release
for module in app sample-client; do
  name=dictate
  if [[ "$module" == sample-client ]]; then name=dictate-sample; fi
  "$ANDROID_HOME/build-tools/36.0.0/apksigner" sign \
    --ks "$signing_dir/release.p12" --ks-key-alias dictate \
    --ks-pass env:DICTATE_STORE_PASSWORD --key-pass env:DICTATE_STORE_PASSWORD \
    --out "build/release/$name.apk" "$module/build/outputs/apk/release/$module-release-unsigned.apk"
  "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "build/release/$name.apk"
done
(cd build/release && shasum -a 256 dictate.apk dictate-sample.apk > SHA256SUMS)
