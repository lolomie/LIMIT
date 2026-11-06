#!/usr/bin/env bash
# Linux SDK bootstrap. A full JDK 17+ and Python 3 are prerequisites.
set -euo pipefail
cd "$(dirname "$0")"
limit_default_sdk="$PWD/.toolchains/android-sdk"
[[ -d /workspace/toolchains/android-sdk ]] && limit_default_sdk=/workspace/toolchains/android-sdk
export ANDROID_HOME="${ANDROID_HOME:-$limit_default_sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ANDROID_HOME/user-home}"
mkdir -p "$ANDROID_HOME/cmdline-tools" "$ANDROID_USER_HOME"
if [[ ! -x "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" ]]; then
    limit_download="$(mktemp -d)"
    trap 'rm -rf "$limit_download"' EXIT
    curl --fail --location --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$limit_download/tools.zip"
    python3 - "$limit_download/tools.zip" <<'PY'
import hashlib,sys
with open(sys.argv[1], 'rb') as f:
    digest = hashlib.file_digest(f, 'sha1').hexdigest()
if digest != '5fdcc763663eefb86a5b8879697aa6088b041e70':
    raise SystemExit('Android command-line tools checksum mismatch')
PY
    unzip -q "$limit_download/tools.zip" -d "$limit_download/unpacked"
    mv "$limit_download/unpacked/cmdline-tools" "$ANDROID_HOME/cmdline-tools/19.0"
fi
python3 - <<'PY'
import os,subprocess,urllib.parse
sdk = os.environ['ANDROID_HOME']
args = [sdk + '/cmdline-tools/19.0/bin/sdkmanager', '--sdk_root=' + sdk]
proxy = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    p = urllib.parse.urlparse(proxy)
    args += ['--proxy=http', '--proxy_host=' + p.hostname, '--proxy_port=' + str(p.port or 8080)]
subprocess.run(args + ['platforms;android-36', 'build-tools;35.0.0', 'platform-tools'], input='y\n'*100, text=True, check=True)
with open('local.properties', 'w') as f:
    f.write('sdk.dir=' + sdk + '\n')
PY
