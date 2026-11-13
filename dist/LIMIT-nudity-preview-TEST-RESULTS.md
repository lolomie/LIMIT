# LIMIT experimental nudity preview verification

Package: dev.limit.app.preview. Separate install, existing LIMIT data untouched.
Build passed JVM tests, lint, API29 instrumentation and API36 co-install/launch smoke.
MediaProjection consent/rotation, sensitive image classification, and real Redmi/HyperOS behavior are NOT certified.
NSFW model predicts porn, NOT anatomical nudity. No measured precision, recall or false-alarm rate.
No INTERNET permission; in-app inference runs locally. Model license: GantMan MIT.
The test APK uses an ephemeral CI debug certificate: later updates may not install over it.
Source commit: 40ae943cbf25a54bf4a59e5c5dde01984270787e
Workflow: https://github.com/lolomie/LIMIT/actions/runs/43391231498
