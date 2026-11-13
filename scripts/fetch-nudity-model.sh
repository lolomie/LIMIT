#!/bin/sh
set -eu
# NudeNet YOLOv8n model and AGPL-3.0 license, pinned to one upstream commit.
# The README says MIT but the checked-in license is AGPL-3.0; apply the stricter license.
commit=a91f67daba2762e19da01e1278aa06515a322677
root="https://raw.githubusercontent.com/amarchandan/Nudenet/$commit"
mkdir -p app/src/main/assets
fetch_checked() {
    url="$1"
    dest="$2"
    sha="$3"
    tmp="${dest}.tmp"
    trap 'rm -f "$tmp"' EXIT
    curl --fail --location --retry 3 --silent --show-error "$url" -o "$tmp"
    actual="$(git hash-object "$tmp")"
    if [ "$actual" != "$sha" ]; then
        echo "Integrity mismatch for $dest: $actual" >&2
        exit 1
    fi
    mv "$tmp" "$dest"
    trap - EXIT
}
fetch_checked "$root/nudenet/320n.onnx" "app/src/main/assets/nudenet-320n.onnx" "68eedc3934ac160fce68e2bd4336cfe17eef140b"
fetch_checked "$root/LICENSE" "app/src/main/assets/nudenet-LICENSE.txt" "0ad25db4bd1d86c452db3f9602ccdbe172438f52"
echo "Pinned NudeNet YOLOv8n model and AGPL-3.0 license verified."
