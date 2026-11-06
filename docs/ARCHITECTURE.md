# Architecture and privacy

## Components

LIMIT has one application module. The Compose activity provides Today, Apps, and Settings screens. A pure Kotlin core calculates per-app allowances and reduces Android usage events. A transactional DataStore repository owns local rules, daily ledgers, theme preferences, and blocker exclusions. A system-bound accessibility service coordinates usage reconciliation, deadlines, and the blocking overlay.

UsageStatsManager events are the duration source. Accessibility window events trigger reconciliation but do not independently add time. The reducer clips intervals to the local day, unions resumed activities within a package, pauses for noninteractive/locked intervals, and closes sessions on shutdown. Separate concurrently resumed packages may accrue time at the same moment.

The service debounces window transitions, reconciles off the main thread, and maintains a deadline for allowance exhaustion. Bounded reconciliation is used during restricted foreground usage or while a camera capsule is displayed. Work stops on lock/idle; midnight does not wake a sleeping device. On exhaustion the selected global policy either returns Home directly or navigates Home and displays a touchable accessibility overlay. Popup sessions ignore their own Home/SystemUI transitions and require confirmed navigation or an explicit choice before dismissal. Persisted blocker exclusions prevent its dwell time from consuming exception credit.

Version 1.0.1 adds an independent passive camera-capsule controller. Its pure model chooses a confirmed ordinary foreground package and formats today's consumed whole minutes. A monotonic timestamp paired with each usage snapshot supports display-only minute-boundary updates; projected time is never written into the ledger. The capsule uses its own non-touchable, non-focusable accessibility window laid out into the status-bar/cutout area with camera-reserved geometry. Since 1.0.5 the glass wraps only the text and sits to the left of a centred camera; an edge camera may use the other side. Tall OEM safety rectangles anchor the horizontal position but never inflate the pill. It hides before blocking and on protected surfaces, lock, permission loss or service cleanup. Rendering failures do not escape into enforcement. No additional permissions, dependencies, notification service, or Xiaomi native integration are used.

## State and transactions

Rules contain a daily minute limit and enabled status. Each local date has a ledger with a global grant count and per-app observed usage plus remaining exception credit. OS snapshots replace/reconcile observations without adding replayed totals twice; observed daily totals do not decrease after they have been accepted.

Redemption requires a restricted app with no remaining base or exception allowance, available verified usage, and fewer than two grants for that date. One persisted transaction consumes a global slot and creates 300,000 milliseconds of app-specific credit before the app is reopened. Usage already over a newly lowered limit does not consume a newly created grant retroactively. Settings edits do not refund grants.

Rollover uses `LocalDate`/`ZoneId`, including daylight-saving transitions, rather than a fixed 24-hour day. Ledgers remain keyed by date so revisiting a date restores its existing expenditure. No exact alarm or network clock is involved.

Unavailable usage history, missing permission, or storage failure is represented as unavailable/inactive. It must not become a verified zero-usage snapshot or a silently replenished exception pool.

## Data and permissions

The app declares Usage access and an accessibility service protected by Android's `BIND_ACCESSIBILITY_SERVICE` binding permission. A scoped launcher intent query discovers launchable packages. Accessibility is explicitly configured without window-content retrieval or gesture capabilities.

LIMIT has no Internet permission, analytics, advertisements, account, remote crash reporting, contacts access, location access, broad package query permission, shared-storage permission, device-admin requirement, foreground service, wake lock, exact alarm, or separate overlay permission. The accessibility overlay uses the system's accessibility window type.

Package identifiers, configured allowances, usage totals, exception expenditure, and blocker intervals remain in app-private storage. Cloud backup and device-transfer backup of application data are disabled. Android can still observe activity through its own operating-system facilities. A debug APK is debuggable by authorized development tools and should not be mistaken for a hardened production-store build.

## Limitations

- Ordinary permissions cannot enforce an unbreakable lock. The user can disable permissions, force-stop or uninstall LIMIT, clear its data, or use safe mode.
- OEM process cleanup can interrupt service operation; Xiaomi HyperOS needs physical-device validation and may require optional background settings.
- Android usage history has limited retention, may arrive late, and is unavailable before first unlock on relevant versions. Recovery depends on evidence supplied by Android.
- Public usage events identify package/class, not every activity instance. Identical-class multi-instance windows and some PiP/split-screen transitions are ambiguous.
- The system's definition of resumed/foreground activity can differ from human attention. Background media is outside the budget definition; LIMIT does not cancel other apps' jobs or playback.
- Device date and timezone are user-controlled. Date-keyed ledgers prevent a simple reset on revisiting a date but do not authenticate time or prevent deliberate clock manipulation.
- No work-profile, other-user, root, device-owner, website, or in-app content restrictions are implemented.
- English is the initial interface language. Platform settings labels vary by Android version and device.

## Service stability in 1.0.4

Initialization is idempotent and publishes connection only after setup. Callback/worker failures are recorded locally and recoverable failures schedule at most three retries at 1/5/15 seconds; queued events do not bypass retry backoff. Unbinding/destruction prevents late snapshot results from resurrecting windows or active status. Display failures are isolated. Fatal failures are recorded and delegated to Android, not hidden as healthy operation. Optional dialer metadata and permission queries cannot propagate ordinary exceptions into the service.

`LimitState.limitReachedAction` defaults to `OFFER_EXTENSION`; schema1 JSON reads a missing/unknown value using that default. `RETURN_HOME` changes enforcement UI only. Repository grant transactions reject stale extra-time actions while auto-return is selected. Diagnostic storage is separate from allowances and keeps only three sanitized records within16KiB.


## Process diagnostics in 1.0.6

The always-visible diagnostics section copies fresh state. Background IO queries ActivityManager.getHistoricalProcessExitReasons for the current package only on API30+, limits output to three timestamp/reason/status summaries, and omits descriptions and trace streams. OS history can be unavailable or describe an earlier installed version. No error record is not evidence of a healthy service. UI and permissions remain unchanged beyond the diagnostic section; this patch does not fix an identified termination cause.
