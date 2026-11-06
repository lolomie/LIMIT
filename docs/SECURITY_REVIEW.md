# Security and performance review

This review describes source inspection, not a penetration test or a physical-device reliability result. Final artifact checks and automated outcomes belong in `output/test-results/`.

## Privacy and permission findings

- The source manifest requests Usage access and registers a system-bound accessibility service protected by `BIND_ACCESSIBILITY_SERVICE`. Only the launcher activity is intentionally available for ordinary launch; the exported service requires Android's binding permission.
- The accessibility configuration disables window-content retrieval and gesture capability. The implementation observes package ownership changes and uses Home navigation plus an accessibility overlay; it does not inspect accessibility node trees, screen text, URLs, or screenshots.
- Scoped MAIN/LAUNCHER package visibility replaces broad installed-package access. Essential system applications are excluded from restriction.
- No Internet permission, analytics SDK, advertisements, remote logging, registration, or remote account is present. DataStore stores settings and accounting in private application storage; backup and transfer rules exclude the data.
- No device-admin access, root, foreground service, wake lock, exact alarm, separate application-overlay permission, or public data provider is required.
- Android's debug signing/build configuration permits development debugging. The delivered debug APK is intended for direct installation and validation, not store distribution under a production signing key.

## Accounting and resilience findings

- Grant validation and mutation occur inside a serialized DataStore transaction. A second concurrent redemption must observe the first grant's credit and cannot mint another grant while that app still has allowance.
- Each local date keeps its global expenditure; replaying OS usage snapshots does not add their totals a second time. Newly observed excess foreground usage consumes existing exception credit; historical overage cannot consume a newly minted grant retroactively.
- Blocked intervals are persisted and excluded from usage reconstruction. Sending the exhausted app Home also prevents overlay dwell from looking like ordinary resumed foreground time.
- Unreadable state must pause monitoring instead of starting an empty exception ledger. Cleanup and failed writes require explicit error handling; automated persistence tests exercise state recreation and invalid stored state.
- Android permissions remain under the user's control. Service availability, actual Usage access, and usable usage history are separate conditions for showing an active state.

## Battery and resource findings

The design is event driven. Window changes are debounced; usage queries run off the main thread; allowance deadlines and sparse active-session reconciliation replace permanent high-frequency polling. Screen-off/locked states cancel active monitoring work. Midnight updates use an in-process timer or the next evaluation, with no wakeup alarm.

Settings use AndroidX DataStore rather than a database service. Core accounting has no Android dependency. UI refresh work is limited to the resumed activity. OS-event replay remains proportional to the available daily history and should be profiled on hardware with unusually large histories before claiming a measured battery improvement.

## Validation boundaries

Static review cannot prove OEM background survival, correct Android event delivery, gesture-navigation behavior, accessibility-service restart timing, or resistance to force-stop. Xiaomi HyperOS and real foreground blocking require device tests. No absolute memory, CPU, latency, or battery measurement is claimed by this source review.
