#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -x /workspace/toolchains/jdk21/bin/javac ]]; then
    export JAVA_HOME=/workspace/toolchains/jdk21
    export PATH="$JAVA_HOME/bin:$PATH"
fi
limit_default_sdk="$PWD/.toolchains/android-sdk"
limit_default_gradle="$PWD/.gradle-user-home"
[[ -d /workspace/toolchains/android-sdk ]] && limit_default_sdk=/workspace/toolchains/android-sdk
[[ -d /workspace/gradle-home ]] && limit_default_gradle=/workspace/gradle-home
export ANDROID_HOME="${ANDROID_HOME:-$limit_default_sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$limit_default_gradle}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$GRADLE_USER_HOME/android-user}"
mkdir -p "$ANDROID_USER_HOME"
if [[ -f /etc/ssl/certs/java/cacerts ]]; then
    export GRADLE_OPTS="${GRADLE_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    mapfile -t limit_proxy < <(python3 - <<'PY'
import os, urllib.parse
p = urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
print(p.hostname or '')
print(p.port or 8080)
PY
)
    export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttps.proxyHost=${limit_proxy[0]} -Dhttps.proxyPort=${limit_proxy[1]} -Dhttp.proxyHost=${limit_proxy[0]} -Dhttp.proxyPort=${limit_proxy[1]}"
fi
if [[ $# == 0 ]]; then
    set -- :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
fi
exec ./gradlew --no-daemon "$@"
