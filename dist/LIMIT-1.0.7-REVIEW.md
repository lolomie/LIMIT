# LIMIT 1.0.7 repair validation

Source functionality: codex/service-reliability, code as of eddd68adae4393dbda960fc77e4ce5c525b7a510. The final push workflow https://github.com/lolomie/LIMIT/actions/runs/42532211919 also passed all checks and committed the exact verified distribution snapshot. Later documentation/workflow-filter changes do not modify app sources.

- Local JDK21/Gradle8.13 build: successful. App, Android test and test-target APKs compiled.
- JVM XML results: 102 tests, zero failures/errors/skips.
- Independent direct Kotlin/JUnit recovery and protection-status run: 7 tests passed (included in the 102, not additional coverage).
- Lint: zero errors/fatals; 16 warnings. Includes existing translation/resource/dependency warnings and a static reference warning for the service instance, which is explicitly cleared on unbind/destruction.
- Local APK: apksigner verification and zipalign -c -P16 -4 successful.
- Signing comparison: certificate differs from 1.0.6; in-place upgrade blocked without the original signing key.
- GitHub Actions https://github.com/lolomie/LIMIT/actions/runs/42531901144: successful build/unit/lint and 8 API29 Android tests, zero skips/failures. The existing cross-app blocking test now removes LIMIT's task before checking both services and the blocker.
- Local software emulator booted but lost ADB connectivity before instrumentation. No local Android test success is claimed.
- Physical Redmi/HyperOS, overnight battery impact and API34+ runtime behavior are unverified. No personal device was connected.

The actual physical-device failure cause remains unproven; the exhausted-retry failure path is fixed and task-removal behavior is validated on API29. No force-stop bypass, hardware reliability guarantee or compatible personal-device upgrade is claimed.
