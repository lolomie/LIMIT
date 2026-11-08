# Research and design decisions

Research used public product documentation, official Android APIs, and publicly available implementation descriptions. LIMIT's code and graphics were created for this project; no proprietary application code, protected assets, or protection-bypass techniques were used.

## Product behavior references

| Reference | Publicly described behavior considered | LIMIT decision |
| --- | --- | --- |
| [AppBlock](https://appblock.app/android/) and its [phone-brand guidance](https://appblock.app/specific-phone-brands/) | App blocking, daily usage limits, and OEM background/accessibility setup. | Keep a daily allowance and a minimal blocker; document OEM reliability limits. Omit strict-mode, website blocking, and uninstall protection. |
| [ScreenZen](https://screenzen.co/) and its [privacy documentation](https://screenzen.co/policies/privacy-policy) | App selection, limited access, mindful interruption, Android accessibility-based blocking. | Keep a short interruption and explicit exception action. Omit social, hardware, website, and feed-blocking features. |
| [StayFree](https://stayfreeapps.com/) and [daily reset documentation](https://userguide.stayfreeapps.com/settings/daily-reset-time) | Daily app limits and usage visibility. | Provide a compact today-only dashboard and fixed local-midnight reset. Omit cross-device accounts, categories, and history analytics. |
| [Android Digital Wellbeing](https://support.google.com/android/answer/9346420) | Per-app timers, usage overview, timers resetting at midnight. | Match the simple daily mental model, with two explicit five-minute global exceptions. LIMIT cannot inherit privileged system app suspension. |

These references are behavioral benchmarks, not a claim that their internals were inspected or their APKs were executed in this workspace.

## Android API sources

- [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager): event access, query range behavior, retention, and unlocked-user limitations.
- [UsageEvents.Event](https://developer.android.com/reference/android/app/usage/UsageEvents.Event): activity lifecycle, screen, keyguard, and shutdown event semantics.
- [Multi-window lifecycle](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode): concurrently resumed activities and lifecycle caveats.
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService): user-enabled service lifecycle, Home action, and overlay support.
- [AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo): capabilities are explicit; content retrieval is not needed for LIMIT's package-level trigger.
- [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams): the accessibility overlay window type.
- [Scoped package visibility queries](https://developer.android.com/guide/topics/manifest/queries-element): launcher intent discovery without querying every installed package.
- [Restricted settings](https://support.google.com/android/answer/12623953): Android's user-controlled sideload accessibility setup.
- [Xiaomi background autostart](https://www.mi.com/uk/support/faq/details/KA-507608/): manufacturer instructions for Settings → Apps → Permissions → Background autostart on a documented HyperOS device; other models may differ.
- [AGP compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes) and [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html): pinned build-tool compatibility.

## Open-source behavior references

[Nudge](https://github.com/astraedus/nudge) and [Detoxwall](https://github.com/IsmailAbdulkareem/Detoxwall) provide public examples of Android usage/blocking concepts. Nudge publishes under GPL-3.0. They informed feasibility review only. Their broader feature sets, permissions, assets, and polling approaches are not inherited by LIMIT. No source from these projects was copied into this application.

## Selected approach

Use system usage events for duration accounting and accessibility window changes for timely enforcement. Prefer a system-supported accessibility overlay over an unsolicited activity launch. Navigate the exhausted app Home and exclude blocker dwell time. Persist grant expenditure atomically before reopening an app. Use local storage and device-calendar boundaries, with no network dependency or claim of tamper resistance.
