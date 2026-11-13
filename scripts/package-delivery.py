#!/usr/bin/env python3
"""Collect verified build outputs without bundling caches, local SDKs, or signing keys."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "output"
OUT.mkdir(exist_ok=True)
apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
if not apk.is_file():
    raise SystemExit("Build :app:assembleDebug before packaging")
shutil.copy2(apk, OUT / "LIMIT.apk")

report_sources = {
    "unit-tests": ROOT / "app/build/reports/tests/testDebugUnitTest",
    "unit-test-xml": ROOT / "app/build/test-results/testDebugUnitTest",
    "instrumentation": ROOT / "app/build/reports/androidTests/connected",
    "instrumentation-xml": ROOT / "app/build/outputs/androidTest-results/connected",
}
for name, source in report_sources.items():
    target = OUT / "test-results" / name
    if source.is_dir():
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(source, target)
for name in ("lint-results-debug.html", "lint-results-debug.xml", "lint-results-debug.txt"):
    source = ROOT / "app/build/reports" / name
    if source.exists():
        shutil.copy2(source, OUT / "test-results" / name)

def tests_under(directory):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for file in sorted(directory.rglob("TEST-*.xml")) if directory.exists() else []:
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, "0"))
    return totals

excluded = {".git", ".gradle", ".gradle-user-home", ".toolchains", ".kotlin", ".idea",
            "build", "output", "__pycache__"}
archive = OUT / "LIMIT-source.zip"
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for file in sorted(ROOT.rglob("*")):
        relative = file.relative_to(ROOT)
        if not file.is_file() or any(part in excluded for part in relative.parts):
            continue
        if file.name == "local.properties" or file.suffix in {".keystore", ".jks", ".log", ".pyc"}:
            continue
        bundle.write(file, Path("LIMIT") / relative)
    install = OUT / "INSTALL.md"
    if install.exists():
        bundle.write(install, "LIMIT/INSTALL.md")
        bundle.write(install, "LIMIT/output/INSTALL.md")
    results = OUT / "test-results/RESULTS.md"
    if results.exists():
        bundle.write(results, "LIMIT/output/test-results/RESULTS.md")

def sha256(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()

test_archive = OUT / "LIMIT-test-results.zip"
with zipfile.ZipFile(test_archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for file in sorted((OUT / "test-results").rglob("*")):
        if file.is_file() and "__pycache__" not in file.parts and file.suffix != ".pyc":
            bundle.write(file, file.relative_to(OUT))
artifacts = (OUT / "LIMIT.apk", archive, test_archive)
manifest = {
    "applicationId": "dev.limit.app",
    "version": "1.0.6",
    "versionCode": 7,
    "variant": "debug",
    "minSdk": 26,
    "targetSdk": 36,
    "unitTests": tests_under(OUT / "test-results/unit-test-xml"),
    "instrumentationTests": tests_under(OUT / "test-results/instrumentation-xml"),
    "instrumentationStatus": "executed" if tests_under(OUT / "test-results/instrumentation-xml")["tests"] else "not_executed",
    "artifacts": {file.name: {"bytes": file.stat().st_size, "sha256": sha256(file)}
                  for file in artifacts},
}
soak_report = OUT / "test-results/v104-runtime-soak.json"
if soak_report.is_file():
    soak = json.loads(soak_report.read_text())
    manifest["runtimeSoak"] = {key: soak[key] for key in (
        "duration_target_seconds", "measured_soak_seconds", "elapsed_including_final_artifact_capture_seconds",
        "sample_count", "failures", "physical_device")}
reboot_report = OUT / "test-results/v104-reboot-smoke.json"
if reboot_report.is_file():
    manifest["rebootSmoke"] = json.loads(reboot_report.read_text())
(OUT / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
(OUT / "SHA256SUMS").write_text("".join(f"{sha256(file)}  {file.name}\n"
                                        for file in artifacts))
print(json.dumps(manifest, indent=2))
