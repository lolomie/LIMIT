# LIMIT project workflow

The user requests automatic GitHub backup of completed work for this project.

- After each completed, validated milestone, commit the relevant project changes and push the working branch to origin without asking routine permission again.
- Open or update a pull request against the default branch. Do not force-push, automatically merge, or overwrite unrelated work.
- Preserve versioned Android source, Gradle wrapper, tests, build scripts and documentation.
- Publish verified deliverables in dist/ when an APK milestone is completed; update checksums and verification notes together. Distinguish archived test results from checks actually rerun.
- Never commit credentials, private signing keys, SDKs, caches, local.properties, or private physical-device diagnostic reports.
- The 1.0.7 branch implements a foreground protection companion and cooldown recovery. Physical Redmi/HyperOS reliability remains unverified; never claim that foreground protection prevents force-stop.
- Keep usage accounting, daily exception rules and existing capsule design unchanged unless the task authorizes changes.
