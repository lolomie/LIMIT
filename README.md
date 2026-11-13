# LIMIT

**Independent installation test (LIMIT 1.0.8 Preview):** A separate `dev.limit.app.preview` build avoids the old debug-signature conflict without touching the installed `dev.limit.app` data. It is **not** a compatible upgrade, and cannot bypass Google Play Protect. [Preview installation and limitations](dist/LIMIT-1.0.8-preview-INSTALL.md). The preview APK is linked from that guide once its CI checks pass.

LIMIT is a minimalist Android app for setting daily app allowances. It runs offline, has no advertisements or account, and stores its settings on the device.

## Current status

**1.0.8 feature branch (CI-verified, Redmi unverified):** Adds optional per-app allowed hours, including local overnight windows. Use **Anytime**, **From a time** (until midnight), or **From–until** alongside the existing daily budget. Neither elapsed usage nor exception slots reset on edits. [Verified 1.0.8 APK](dist/LIMIT-1.0.8.apk) · [Verification report](dist/LIMIT-1.0.8-REVIEW.md) · [SHA-256](dist/LIMIT-1.0.8-SHA256SUMS) · [feature documentation](docs/ALLOWED_HOURS.md). **The signing certificate differs from 1.0.7; do not uninstall or clear data to install this APK.** The recurring Redmi Accessibility interruption is separate and unchanged.


**1.0.7 adds background protection status and recovery after exhausted short retries. Physical Redmi/HyperOS reliability remains unverified.** The repair passed 102 JVM tests, lint with no errors, and eight API29 Android tests including task removal. The APK uses a different debug signing certificate from 1.0.6; read the [1.0.7 installation guide](dist/LIMIT-1.0.7-INSTALL.md) before using it. Return to LIMIT after enabling accessibility to start the foreground companion. See [service reliability](docs/SERVICE_RELIABILITY.md) for startup, notification and force-stop limits. The 1.0.6 results below are historical and do not certify 1.0.7.

[1.0.7 test APK](dist/LIMIT-1.0.7.apk) · [Fresh results](dist/LIMIT-1.0.7-TEST-RESULTS.md) · [Repair review](dist/LIMIT-1.0.7-REVIEW.md)


**1.0.6 is a diagnostic build. The recurring Redmi/HyperOS accessibility-service failure remains unresolved.** The proposed foreground-service background protection has not been implemented. A successful emulator run does not certify Xiaomi background reliability.

The archived verification for this exact APK reports 95 JVM tests and 8 Android tests passing; lint has no errors. Earlier failed test attempts and their environment findings remain documented in the reports. This repository import makes no application-code changes and does not claim a new device test run.

## Install

Download [LIMIT 1.0.6 APK](dist/LIMIT-1.0.6.apk), then follow [installation and permissions](dist/INSTALL.md). Install as an update without uninstalling to preserve local settings. Android 8.0/API 26 or later is required.

[Verification summary](dist/TEST-RESULTS.md) · [Complete test reports](dist/LIMIT-1.0.6-test-results.zip) · [SHA-256 checksums](dist/SHA256SUMS)

Local development packaging still writes to ignored `output/`. The checked-in `dist/` directory contains the verified distribution snapshot.

## Daily behavior

- Choose launchable applications and give each a daily allowance from 0 to 1,440 minutes. New selections default to 30 minutes; zero blocks immediately.
- Today's foreground usage counts toward an allowance, including usage recorded before the app was selected. Background audio and locked-screen time do not count.
- At exhaustion, Settings lets you choose an immediate return Home or a persistent screen offering extra time (default).
- There are two exception grants globally per local date. Each grants one exhausted app five additional minutes of foreground usage. Both may be used on the same app sequentially.
- Switching apps, locking the screen, or restarting pauses rather than expires unused exception credit. Credit expires at local midnight. Limit edits do not refund grants.
- Daily allowances reset at local midnight. A sleeping device applies the reset when it next wakes. Returning to an already visited date does not replenish that date's exceptions.
- Choose the system theme, light theme, or dark theme in Settings.

LIMIT relies on Android Usage access and a user-enabled accessibility service. It cannot provide an unbreakable system-level lock. See [technical limitations](docs/ARCHITECTURE.md#limitations).

## Build and test

Version 1.0.5 uses a compact, minutes-only camera capsule (no app name or icon) with a translucent glass-style background for every foreground ordinary launchable app, including apps without a restriction. It shows today's **used** whole minutes, for example `23 Min.`, rather than remaining allowance. The capsule uses the existing accessibility permission, lets touches through, and hides on protected system surfaces, while locked, and while the blocker is visible. It is LIMIT's own overlay: a small text-only glass pill at camera height, normally to the left of the hole so right-hand network icons remain clear. Edge cameras use the other side when needed. It is not Xiaomi's native HyperIsland. Limits and exception rules are unchanged. See [the implementation masterprompt](docs/CAPSULE_MASTERPROMPT.md).

Open this directory in Android Studio or use the Gradle wrapper. Install Android SDK platform 36, Build Tools 35.0.0, and Platform Tools; set `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir`.

The pinned toolchain is Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin/Compose compiler 2.3.10, and Compose BOM 2025.12.01. The development runtime is Java 21; compilation generates JVM 17 bytecode. The app targets API 36 and supports API 26 onward.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest  # requires an online emulator or device
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. JVM reports appear in `app/build/reports/tests/testDebugUnitTest/`; lint reports appear in `app/build/reports/`. A debug build is installable and uses Android's debug signing identity; it is not a store release.

`build-limit.sh` is a convenience launcher for this development environment. Standard Android Studio and wrapper commands remain the portable build entry points.

See [architecture and privacy](docs/ARCHITECTURE.md), [test coverage and validation procedure](docs/TESTING.md), and [research references](docs/RESEARCH.md). Actual delivered verification results are recorded with the output artifacts; documentation describing a test does not imply that it passed.

Version 1.0.4 also hardens service callbacks and transient recovery. Settings contains a bounded, local, copyable service diagnostic (three sanitized entries, no usage or screen contents). The exact cause of a previously reported physical HyperOS failure remains unconfirmed without its device trace.

Version 1.0.6 makes Service diagnostics always visible, including when the error log is empty. Copying rebuilds current permission/service status and, on Android 11+, summaries of Android-reported process exits for LIMIT only. No descriptions, trace streams or extra permissions are used. This is a diagnostic update; the reported recurring device-specific failure remains unresolved pending its report.
