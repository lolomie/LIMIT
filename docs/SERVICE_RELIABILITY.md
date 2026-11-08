# Service reliability in 1.0.7

The Android accessibility service still owns enforcement independently of MainActivity. A visible companion now starts from MainActivity.onResume after accessibility is enabled. After changing accessibility settings, return to LIMIT once to start it. Its specialUse foreground-service declaration documents ongoing, user-visible time-limit protection and monitoring status. This is a sideload implementation; Google Play policy approval has not been obtained or implied.

The companion uses START_STICKY and stopWithTask=false. It does not restart from onTaskRemoved, enable accessibility, acquire a wake lock, use exact alarms, access the network, or bypass force-stop. Android may restart a killed started service according to its own policy; neither sticky restart nor a foreground notification guarantees HyperOS reliability. No boot receiver is installed: after reboot the system controls accessibility reconnection; reopen LIMIT to start the companion.

Three recovery retries use 1/5/15 second delays. After they are spent, ordinary events can reopen recovery after a 60 second monotonic cooldown. The companion checks at 60 second intervals while scheduled by Android and requests cooldown recovery only while the screen is usable. It performs no routine usage-history scans. There are no repeated process restart attempts.

Connected is published after successful reconciliation rather than before the first snapshot. Missing storage readiness or usage permission clears it. A pending usage snapshot older than 90 seconds is reported as unresponsive by the companion. Both services share the main thread/process: a completely frozen main thread or a terminated process cannot reliably issue a fresh notification. No claim of out-of-process monitoring is made.

Notification states distinguish setup, unavailable storage, recovery, disconnection, screen rest, and active monitoring. Notification actions open LIMIT for existing permission and diagnostic controls. Screen broadcasts update resting status without waking the phone. Denied notifications do not disable Android's foreground service, but can hide its drawer status. The Settings section reports this limitation and provides a permission action. Channel disabling and manual notification dismissal may also hide the indicator; absence is never proof of protection.

Allowances, exception expenditure, usage reduction, capsule geometry and datastore schema are unchanged. No private keys or physical-device traces are versioned.

## Required acceptance checks

- RecoveryPolicy tests: bounded short retries, cooldown boundaries and successful reset.
- ProtectionStatus tests: permission enabled/disconnected cannot imply active, storage and failure precedence, resting and active states.
- Existing blocking instrumentation now removes LIMIT's own task through ActivityManager before asserting that both services survive and a restricted target is blocked.
- Test API 34+ foreground type/start rules and denied notification permission separately from API29 instrumentation.
- Test Recents swipe, vendor cleanup, reboot, overnight behavior and battery impact on a physical Redmi. Task removal on an emulator is not a HyperOS certification.

## References

- https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/ui/views/notifications/notification-permission
