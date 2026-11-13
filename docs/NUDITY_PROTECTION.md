# LIMIT: opt-in offline screen nudity protection (experimental)

This feature is an **experimental** imperfect whole-screen blocker, not a precise nudity detector.
The model predicts five NSFW classes; LIMIT only considers the 'porn' output. It
does not identify exposed body parts, and may miss non-pornographic nudity or
misclassify benign images. No measured real-world precision or recall exists
yet; do not claim reliable nudity detection or guaranteed coverage.

## Model provenance and build

- GantMan/nsfw_model MobileNetV2 140/224, MIT, 17,355,548 bytes.
- Redistributed with attribution in getknit/knit at pinned commit
  d3b8210629316247198371cc8deba46af13ce4af, Git blob
  ac07d99acc93217a77d5b4749760042173b8a3f0.
- The binary is not checked into Git. Run `sh scripts/fetch-nudity-model.sh`
  from the repository root *before* building. The script validates the exact
  upstream Git blob. GitHub Actions executes it before Gradle.
- Inference is via TensorFlow Lite locally. The APK includes the model and
  MIT notice, but no INTERNET permission, screenshot files or uploads.
- Input 224×224 float32 RGB [0,1]. Output drawings, hentai, neutral, porn, sexy.
  Whole-screen and three vertical crop scores; two consecutive high-scoring
  observations trigger a full-screen accessibility protection overlay.
- Default threshold 0.85 for porn, with strict 0.95 / sensitive 0.70.
  These values are *not calibrated on representative test data*.

## Android capture contract

Explicit system consent is mandatory for each new MediaProjection session.
The mediaProjection foreground service starts only after consent, declares
FOREGROUND_SERVICE_MEDIA_PROJECTION and runs with a visible Stop action. A
session ends on system revocation, rotation, captured-content resize, service
termination, app process death, device reboot or manual Stop; the user must
start again. All images are transient and discarded. No attempt is made to
bypass FLAG_SECURE, work-profile boundaries, protected apps, system dialogs
or background-start restrictions.

Protected SystemUI/permission prompts cannot be universally excluded from
MediaProjection captures; the overlay is never raised on known critical
packages. Captured app-only content choices offered by the system may reduce
coverage. Rapid video clips between samples can be missed, and a detected
frame may remain visible before the next classification. There is no guarantee
against showing nudity even when the feature is on.

## Scope and fallback

On-device detection requires Accessibility connected for the blocking overlay.
If it disconnects, no content should be advertised as protected. Fallback
blocking is independent and explicitly user-selected; disabling it never
blocks any app. Existing daily budgets, wall-clock limits and exception
counters remain authoritative. No automatic update to existing main LIMIT
can be produced without the private matching signing key; use the separate
`preview` build for trials without touching production data.

## Validation still required

API 36 emulator: manual consent acceptance/denial, start/stop, rotation,
protected content, real-time frames, overlay precedence, and 16KiB compatibility.
Physical HyperOS: service interruptions, battery and thermals, installation.
Model: lawful, representative test set including nudity, non-sexual nudity,
sports, beaches, skin-tone objects, dark and small images, videos; record
precision, recall, false positives, and class-specific limits. Until these
checks pass, the feature is **not production-ready**.
