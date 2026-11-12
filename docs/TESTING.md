# Validation procedure

This document describes coverage and acceptance checks. Actual run outcomes are recorded in [the delivered verification report](../dist/TEST-RESULTS.md), with logs in `output/test-results/`. A listed scenario is not a claim that a device test passed.

The current release results are recorded in the delivered verification report. Version 1.0.5 adds three geometry regressions for tall OEM cutout rectangles and compact left-of-camera placement. Existing Android blocking coverage also checks status-bar containment, compact width, and a minutes-only visible label. Physical-device and HyperOS checks remain separate.

## Version 1.0.8 allowed-hours checks

Pure JVM tests inject fixed `Clock` instances in selected timezones, including exact opening/closing milliseconds, overnight windows, the local-midnight budget reset, unchanged grants and spent credit after a rule edit, and both transitions across European DST changes. Repository tests load schema-v1 JSON without an `hours` field and verify rules, usage, grants and credit after mutation and recreation.

The Android service schedules the earliest enabled time-rule transition via a cancellable coroutine, recomputes after clock/timezone and wake broadcasts, and preserves the existing 15-second reconciliation of restricted active apps. These tests do not simulate Xiaomi process kills; validate boundary enforcement on a disposable device before claiming OEM reliability.

## Automated checks

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

For the complete cross-app suite, use a disposable emulator and install the separate helper first:

```sh
./gradlew :test-target:assembleDebug :app:assembleDebug
adb install -r test-target/build/outputs/apk/debug/test-target-debug.apk
./gradlew :app:connectedDebugAndroidTest
```

The blocking test temporarily enables Usage access and accessibility, restores those settings afterward, and spends one exception in the test installation. It skips on physical devices or when its helper/fresh allowance is unavailable. For a repeat run on a disposable emulator, clear LIMIT's test-installation data first with `adb shell pm clear dev.limit.app`; this erases that installation's settings and ledger. Do not clear a personal installation to run tests.

JVM tests cover base allowance boundaries, zero-minute restrictions, global first/second/third exception behavior, same-app sequential grants, full five-minute credit after historical overage, replayed snapshots, partial-credit preservation, settings changes, and date-ledger revisits. Reducer tests cover repeated activity events, overlapping activities, concurrent resumed packages, locking, screen-off, shutdown, midnight clipping, and overlapping blocker exclusions.

Persistence tests exercise repository recreation, serialized updates, stored rule/theme/ledger recovery, and failure behavior where the Android test environment supports it. Compose tests verify the primary screens and user navigation. These checks do not substitute for service behavior on Android hardware.

## Emulator/device acceptance

Version 1.0.1 adds 17 deterministic camera-capsule tests. The blocking instrumentation test additionally verifies the capsule on an unrestricted app, compares displayed minutes with measured usage, swipes through the status-bar capsule into the real notification shade, checks hiding on Home and under the blocker, and checks restoration after granting an exception. These checks preserve all previous regression assertions.

1. Install and launch the debug APK. Confirm Today, Apps, and Settings render in light/dark mode and with enlarged font size.
2. Grant Usage access and accessibility through Android settings. Verify the active status, then revoke each permission and verify the inactive status and overlay removal.
3. Select an ordinary launchable test app, give it a short allowance, and use it to exhaustion. Verify Home navigation and blocking on reopening it. Check that Settings, launcher, and emergency/call handling remain reachable.
4. Leave the blocker visible, then redeem an exception. Verify five minutes of actual foreground credit; blocked dwell time must not reduce it.
5. Switch apps and lock/unlock during an exception. Confirm its remaining credit pauses and resumes. Exhaust it, grant again on either the same or another exhausted app, and confirm that a third grant is refused globally.
6. Kill/recreate LIMIT's process, reconnect accessibility, and reboot during partial credit. Confirm persisted settings and grant expenditure survive and recoverable usage is reconciled without duplication.
7. Test a session crossing local midnight, sleep across midnight, timezone changes, and revisiting a date with spent grants. Use deterministic JVM clocks for DST edge cases; device changes alone cannot prove all DST transitions.
8. Test app removal, rule edits, unavailable history, rapid repeated grant taps, and transitions through split-screen/PiP. Record Android's observable limitations.
9. Measure enforcement delay and observe idle/locked CPU behavior. Verify there is no permanent high-frequency polling or network traffic from LIMIT.

On Xiaomi HyperOS, repeat background, Recents cleanup, reboot, lock/unlock, and overnight checks with the actual OS version and optional battery/autostart settings recorded.

## APK release checks

Use Android SDK Build Tools to inspect the merged APK manifest, confirm the expected package/version/minimum SDK, verify no Internet permission, check signing with `apksigner verify --verbose`, and check alignment with `zipalign -c -P 16 4`. Calculate SHA-256 for the delivered APK. An install/launch smoke test is distinct from a successful APK signature check.

When no emulator or physical device is available, explicitly mark installation, real blocking, background reliability, and HyperOS behavior as unverified. Attach build/unit/lint logs independently of that limitation.

## Version 1.0.4 acceptance

Run all original tests plus settings migration/persistence, diagnostics sanitization, injected AppOps failures, popup session policy, and camera geometry. Android checks cover a popup lasting over five seconds after Home navigation, a notification-shade swipe through the camera-height window, both enforcement settings and stored selection. Follow the successful final APK run with 900 real seconds of app/Home/Settings/lock transitions while checking PID, service binding, fatal logs and new diagnostic records. Keep physical Xiaomi validation distinct.


## Version 1.0.5 presentation acceptance

Re-run all JVM tests, lint and the seven existing Android tests with the compact capsule assertions. Inspect an actual emulator screenshot. Compare production sources with 1.0.4 to confirm unchanged service, usage and exception behavior; the separate Theme.kt UI-thread guard fixes a regression uncovered by the theme recreation test. Preserve historical 1.0.4 runtime results separately rather than claiming they were repeated for the new APK.


## Version 1.0.6 diagnostics acceptance

Test empty logs, Android 11+ exit summary bounds/order, ANR classification, unknown reasons, unavailable-history privacy, and older-Android fallback. On Android, scroll to and copy diagnostics even with no recorded failure. The complete existing blocking and persistence regression suite remains required. Device-specific termination is unresolved until a relevant device report is available.
