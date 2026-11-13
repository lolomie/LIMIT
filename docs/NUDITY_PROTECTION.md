# LIMIT: anatomically focused offline nudity detection (experimental)

## What changed
Replaced GantMan's general five-class NSFW MobileNet classifier with NudeNet
YOLOv8n, which estimates bounding-box detections for 18 anatomical/covered
categories. LIMIT considers only **exposed** female breast, genitalia, anus,
and buttocks. Covered genitalia, covered breasts, male chest, faces and ordinary
exposed skin are explicitly ignored. Image frames never leave memory, no
screenshots/telemetry/uploads are stored, and the APK has no Internet permission.

The on-device engine uses ONNX Runtime Android 1.24.3; the model is fetched
and validated at build time. The APK carries model and upstream license,
so runtime requires no downloads.

## Model provenance and license
- Source https://github.com/amarchandan/Nudenet at pinned commit
  a91f67daba2762e19da01e1278aa06515a322677
- Model nudenet/320n.onnx, 12,150,158 bytes, Git blob
  68eedc3934ac160fce68e2bd4336cfe17eef140b.
- The checked-in upstream LICENSE file is **AGPL-3.0**, Git blob
  0ad25db4bd1d86c452db3f9602ccdbe172438f52.
  Upstream README says MIT, conflicting with the license file. Treat the
  stricter **AGPL-3.0** terms as controlling until clarified by rightsholder.
  Maintain full source availability, attribution, notices, and license text.
  Verify third-party model weights provenance before non-experimental
  commercial distribution.
- Run `sh scripts/fetch-nudity-model.sh` before building, then
  `./gradlew testDebugUnitTest lintDebug assemblePreview`.

## Android & limitations
For each capture session the user must grant MediaProjection consent.
The foreground capture service is visible with a Stop control.
No screenshots or frames are persisted, logged, or transmitted.
A full-screen Accessibility overlay covers detected content only if LIMIT's
Accessibility service is still connected. Normal app limits/time windows
retain precedence, and opt-in fallback app blocking remains separate.
Protected surfaces / FLAG_SECURE, app-only capture, system overlays, delayed
frame sampling, rotation and foreground restrictions limit coverage.

Detection confidence and its thresholds have **not been calibrated** with
representative lawful test sets. Tests with synthetic output ensure only
exposed categories are considered, not accuracy on photographs.
Automated CI verifies builds, lint, pre-existing Android UI tests and API36
preview installation; it does NOT prove clinical-grade or perfect detection.
Physical Redmi/HyperOS, battery use, precision/recall/false-positive rate and
real MediaProjection/live-image behavior remain unverified until tested.
Never market this as 100% or as guaranteed content blocking.
