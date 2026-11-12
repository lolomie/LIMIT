# LIMIT 1.0.8 Preview — Android installation test

This build is for the specific signature conflict seen with previous LIMIT debug builds. It does not pretend to remove Google's or Xiaomi's install safeguards.

## What this APK changes

- Application ID: dev.limit.app.preview (original: dev.limit.app).
- Android treats this as a distinct app, so it does not overwrite, uninstall, migrate or clear the original LIMIT app or its data.
- The application otherwise contains the allowed-hours feature from 1.0.8.
- The launcher and Accessibility service are labeled LIMIT Preview.
- Verified workflow: JVM + lint + API29 instrumentation + API36 emulator install, coexistence and launch.
- Not a replacement for stable signing: the preview DEBUG key is generated per CI runner. Future preview APK updates may not install over this preview. The original installed package still requires its original signing key for a data-preserving update.

## Install

1. Keep the existing LIMIT app installed. Download dist/LIMIT-1.0.8-preview.apk from the tested branch.
2. Only install the APK if Android allows it normally after any ordinary installer-source permission prompt. Do not switch off Play Protect or other security checks.
3. You will have two apps: LIMIT and LIMIT Preview, with separate settings and usage ledgers.
4. While testing, turn off the original LIMIT accessibility service in Android settings and enable LIMIT Preview app blocking, if the platform permits it. Running both simultaneously can produce conflicting blockers.
5. Restore the original service when finished. Do not uninstall the original app just to enable the preview.

## Limitations / next step

This solves Android package-signature conflicts only for the preview by using a different package ID. It cannot solve Play Protect's block of Internet-sideloaded accessibility apps; changing signatures or hiding Accessibility declarations would not provide a trustworthy fix. Google recommends proper developer distribution / appeal if a legitimate app is flagged (https://developers.google.com/android/play-protect/warning-dev-guidance).

If Android still says "blocked for security", capture the entire installer alert including which service (Google Play Protect, HyperOS package installer, or Android) reports it. Do not bypass the warning. The production fix needs a trusted distribution channel and a stable original signing key; neither can be recovered from existing APKs. Physical Redmi/HyperOS runtime and background-service reliability are unverified.
