#!/bin/sh
set -eu
# Pinned upstream Git blob, verified before this file can be used in an APK.
# GantMan nsfw_model MobileNetV2, MIT; redistributed by getknit/knit.
source_commit=d3b8210629316247198371cc8deba46af13ce4af
expected_blob=ac07d99acc93217a77d5b4749760042173b8a3f0
dest=app/src/main/assets/nsfw.tflite
mkdir -p app/src/main/assets
tmp="${dest}.tmp"
trap 'rm -f "$tmp"' EXIT
curl --fail --location --retry 3 --silent --show-error \
  "https://raw.githubusercontent.com/getknit/knit/$source_commit/app/src/main/assets/moderation/nsfw.tflite" \
  -o "$tmp"
actual="$(git hash-object "$tmp")"
[ "$actual" = "$expected_blob" ] || { echo "Model blob integrity check failed" >&2; exit 1; }
mv "$tmp" "$dest"
echo "Verified MIT model at $dest (git blob $actual)"
