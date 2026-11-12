# LIMIT 1.0.8 preview verification

Application ID: dev.limit.app.preview (separate from dev.limit.app).
This signed DEBUG test APK can install beside existing LIMIT without a signing-certificate conflict.
This does NOT update or migrate the existing installation.
JVM tests, Android lint, API29 UI tests, and the API36 coexistence/install/launch smoke test succeeded.
Google Play Protect and Xiaomi/HyperOS can still block sideloaded Accessibility apps. No bypass is implemented or advised.
The signing key is CI-generated and NOT stable for later updates. Physical Redmi install remains unverified.
Tested source commit: d5c56167e235e4432734aed40d212a626854994a
CI run: https://github.com/lolomie/LIMIT/actions/runs/43239218906
