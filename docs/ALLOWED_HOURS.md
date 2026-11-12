# Allowed hours (LIMIT 1.0.8)

## Behavior and persistence

Each optional app rule stores `hours: {mode: ANYTIME|FROM|BETWEEN, from, until}`, in minutes after local midnight. Previous schema-v1 rules with no `hours` decode to ANYTIME; the underlying `state_v1` preference key and all dated usage/grant ledgers remain unchanged.

- **Anytime:** the previous per-app daily budget only.
- **From:** inclusive local start until the next local midnight (e.g. 18:00–00:00).
- **Between:** inclusive start, exclusive end; equal endpoints are rejected. Start later than end denotes a window crossing local midnight (22:00–02:00).

The current local calendar date, not the window start date, selects the daily ledger. At 00:00 the next calendar date's stored budget becomes active; no exception credit transfers. The clock logic is separate from `LimitEngine` and accepts an injected `Clock`. If multiple block reasons apply, hours denial takes precedence. The time-blocker hides all exception controls; `StateRepository.grant` additionally denies transactions outside the current window.

## Scheduler and enforcement

`LimitAccessibilityService` determines the earliest next wall-clock change across enabled restrictions, translates it into a real instant, and schedules one coroutine delay. It cancels/recomputes on refresh, screen off/on, service connect, rule changes, manual clock changes and timezone changes. This is an in-process timer, not a system exact alarm or wake lock. The existing 15-second active-app reconciliation provides a fallback while the process is running; sleeping devices resume enforcement on wake. An already-open app is considered on a boundary refresh without waiting for an app switch.

DST gaps are resolved at their first valid instant; repeated hours are evaluated against actual local wall time in each occurrence. A rollback can produce a second opening or closing transition. For ordinary wall-clock boundaries, start is inclusive and end is exclusive.

## Verification and limits

Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest :test-target:assembleDebug` and disposable-emulator `connectedDebugAndroidTest`. Inspect the new `AllowedHoursPolicyTest`, schema-v1 migration cases and editor instrumentation. The built APK needs independent signature and 16 KiB alignment verification; a debug key generated per CI runner may not match earlier releases.

**LIMIT does not guarantee blocking when Accessibility monitoring is disconnected, force-stopped or terminated by OEM policies.** A foreground companion (1.0.7) does not fix that underlying issue. No physical Redmi clock, data or installed app was modified. A CI emulator is not evidence of flawless HyperOS behavior. Do not delete app data to force-install an APK signed by another key.
