# LIMIT 1.0.7 — test build

This build uses a newly generated debug signing identity. It is not a compatible in-place update to the distributed 1.0.6 APK. Do not uninstall or clear the data of your personal installation to install it. The original signing key is required to produce a compatible upgrade. The key must stay outside Git.

Use this APK only on a separate test device or disposable emulator with no personal LIMIT installation. Enable Usage access and the LIMIT accessibility service through Android Settings, return to LIMIT once to start Background protection, and allow protection notifications if you want the drawer status. On Redmi/HyperOS, review Background autostart and the app's battery setting (No restrictions); wording varies.

After this setup, ordinary task removal should preserve monitoring and the status companion. A permission being enabled does not alone prove service operation. A full process termination, force-stop, disabled permissions, notification blocking/dismissal or manufacturer intervention can interrupt protection or hide status. No app can promise a fresh warning after its entire process has been killed.

After a reboot, Android controls accessibility reconnection; reopen LIMIT to start the companion. Physical Redmi/HyperOS behavior and overnight battery impact remain unverified. API29 emulator task removal is distinct from the phone's own cleaner.

Limits and the two daily five-minute exception grants retain their existing storage and accounting behavior. No personal device data was touched during validation.
