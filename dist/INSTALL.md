# Install LIMIT

## Kurzinstallation — Deutsch

1. `LIMIT-1.0.6.apk` als Update installieren. Die vorhandene App **nicht deinstallieren**, damit Einstellungen und Ausnahmen erhalten bleiben. Version 1.0.6 ist mit demselben Zertifikat signiert wie 1.0.5.
2. Falls Android bei „LIMIT app blocking“ bereits **Fehler** anzeigt: unter Einstellungen → Bedienungshilfen → Heruntergeladene Apps den LIMIT-Dienst einmal aus- und wieder einschalten. Danach LIMIT öffnen und den aktiven Überwachungsstatus prüfen. Nutzungszugriff muss ebenfalls aktiviert sein.
3. In LIMIT → Settings → **When limit is reached** wählen: **Offer extra time** hält die Verlängerungsauswahl offen; **Go home immediately** führt direkt zum Startbildschirm. Standard ist die Verlängerungsauswahl. Die zwei täglichen Ausnahmen bleiben gleich.
4. Auf HyperOS bei Bedarf **Hintergrund-Autostart** erlauben und die Akku-Einstellung von LIMIT auf **Keine Beschränkungen** setzen.

Version 1.0.6 ist ein Diagnose-Update: Der erneute Dienstausfall ist noch nicht als behoben nachgewiesen. Der Abschnitt **Service diagnostics** ist jetzt immer sichtbar. Android 11+ kann zusätzlich gespeicherte Prozess-Endgründe liefern; ein fehlender Eintrag schließt einen Ausfall nicht aus.

Sollte der Dienst erneut ausfallen: LIMIT → Settings → **Service diagnostics** → **Copy service diagnostics**. Ein durch HyperOS beendeter Prozess hinterlässt unter Umständen keinen App-Fehlereintrag. Die Kameraausrichtung und Hintergrundstabilität auf dem tatsächlichen Redmi sind noch zu prüfen.

## Full installation and technical notes

Requires Android 8.0 or newer. The APK is a signed debug build intended for direct installation.

## Installation

1. Copy `LIMIT-1.0.6.apk` to your Android device and open it with your file manager.
2. If Android asks, temporarily allow that file manager to install unknown apps, then install LIMIT. You may turn that permission off afterward.
3. Open LIMIT. It works without registration or Internet access.

With Android Platform Tools and USB debugging enabled, installation can instead be performed with:

```sh
adb install -r LIMIT-1.0.6.apk
```

An update must have the same signing identity as the installed copy. If Android reports an incompatible signature, uninstalling the old copy permits installation but erases its local settings and usage ledger.

## Required setup

LIMIT's Settings/setup controls open the relevant Android screens. Names differ between Android versions and manufacturers.

1. **Usage access:** choose LIMIT in Android's Usage access settings and enable usage access. This supplies foreground application usage events.
2. **Accessibility:** open Accessibility → Downloaded/Installed apps → LIMIT app blocking and enable it. LIMIT observes application window changes and displays its blocking screen. It does not read screen text, inspect UI contents, type, or perform gestures.
3. Return to LIMIT and confirm that monitoring is active. If a permission is disabled or usage data cannot be read, the app shows an inactive status rather than assuming zero usage.

On some Android versions, an APK installed from a file cannot immediately enable accessibility. **Only if Android displays this restriction**, open Settings → Apps → LIMIT → the overflow menu → **Allow restricted settings**, then return to Accessibility and enable LIMIT. Do not enable unrelated permissions.

No separate "Display over other apps" permission, device-administrator access, notifications, or Internet access is required.

## Xiaomi HyperOS

If HyperOS stops monitoring in the background, check the settings available on your model:

- Enable LIMIT under **Background autostart**.
- Set LIMIT's app battery behavior to **No restrictions**.
- If available, lock LIMIT in Recents so the manufacturer's cleanup does not remove it.

These manufacturer settings vary by device and do not guarantee that Android will keep the service alive. Reopen LIMIT after a restart and check its monitoring status. Physical Xiaomi/HyperOS reliability must be verified on your device.

## Use

In Apps, select an app and set its daily allowance. Zero minutes blocks it immediately; new selections default to 30 minutes. Essential system apps remain unrestricted so you can reach Settings, Home, and emergency/call handling.

In Settings → **When limit is reached**, choose **Offer extra time** (default) to keep the choice screen open, or **Go home immediately** to return Home without showing a popup. This selection is global, persists locally and does not spend or refund any daily exception. Returning Home does not force-stop the other app or its background audio. In the extra-time mode, the blocker offers **Use 5 minutes** if an exception remains, or **Go home**. The allowance is two grants shared across every restricted app each local day. Each grant supplies five minutes of foreground use to that app; switching away or locking pauses the credit. Unused credit expires at midnight. Editing limits or restarting does not return a used grant.

The Today dashboard shows usage and remaining allowances. Light, Dark, and System themes are available in Settings.

### Camera capsule (unchanged since 1.0.5)

After the same Usage access and accessibility setup, LIMIT automatically shows a compact translucent glass-style pill containing only the minutes at status-bar height, normally to the left of the camera hole in ordinary foreground apps, including apps without a configured limit. It shows today's **used** whole minutes, for example `23 Min.`; it is not a countdown or a session-only timer. Less than one minute displays `0 Min.`. Touches pass through it. It hides on Home, in LIMIT, on protected system surfaces, while locked, and while the blocking screen is shown.

This is LIMIT's own camera capsule, not Xiaomi's native HyperIsland, and needs no additional permission. It does not integrate into Spotify's own display. Version 1.0.6 uses the same signing certificate as earlier versions, so install it as an update without uninstalling to keep your settings. The pill no longer expands to the OEM cutout safety rectangle or mirrors an empty area across the camera. Actual Redmi placement still requires on-device validation.

## Limitations and troubleshooting

- Force-stop, uninstalling LIMIT, disabling accessibility/Usage access, safe mode, and manufacturer background termination can disable blocking. LIMIT is a voluntary self-control tool.
- Android usage events can arrive late. Brief switching or enforcement delays are possible. Split-screen and picture-in-picture behavior depends on Android's activity lifecycle; simultaneously resumed apps may both accrue usage.
- Settings, launcher, permission controls, emergency/call handling, and LIMIT remain reachable.
- Midnight follows the device's local date and timezone. Previously visited dates keep their spent exception count, but the app cannot authenticate an offline device's clock against deliberate time changes.
- A blocked app is sent Home; its background playback or already-running work may continue according to that app's behavior.
- A fresh install or clearing LIMIT's storage erases its settings and ledger. Android's retained usage history may still show earlier usage today.
- If Android says monitoring is inactive, revisit both required settings and return to LIMIT. Never rely on a stale dashboard as proof that blocking is currently operating.

The accompanying test results distinguish automated checks from emulator/device checks. Do not infer physical HyperOS validation from a successful compilation or a JVM test pass.

## If monitoring stops again

Open LIMIT → Settings → **Service diagnostics** → **Copy service diagnostics**. The app keeps at most three sanitized local failure entries (16 KiB maximum) with phase, time, app/Android versions and safe stack frames. It does not record screen contents, selected-app usage, exception messages or send anything automatically. If you choose to share the copied diagnostic, it can help distinguish an app exception from an OEM service termination. Do not uninstall to troubleshoot unless you intend to erase settings.

Temporary recoverable service errors receive at most three timed retries (1, 5 and 15 seconds). Permission revocation or an Android-disabled service still requires manual permission/service activation. A long successful emulator run does not certify all HyperOS background policies.

Since 1.0.6, Service diagnostics is always visible. Copying includes fresh permission/connection state and up to three own-process exit summaries supplied by Android 11+. No trace streams or OS descriptions are read. An absent or unknown exit reason does not identify an OEM cause. This diagnostic update does not claim to fix the recurring service failure.
