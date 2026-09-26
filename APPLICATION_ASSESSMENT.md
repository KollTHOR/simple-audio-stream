# Simple Audio Stream — Application Assessment

**Review date:** 2026-09-26
**Review type:** Static repository review, release-system checks, and unit-test verification

## Executive summary

Simple Audio Stream is a feature-rich Android app for transmitting system audio over Wi-Fi/Wi-Fi Direct and receiving it with low-latency playback. Its implementation includes adaptive buffering, packet-loss recovery, multiple discovery transports, diagnostics, saved devices, and an in-app update center. The repository also has a substantial suite of JVM tests.

The release-signing fallback has been removed from the current source tree. Both release workflows now require a private keystore supplied through Actions secrets, and Gradle rejects release packaging without signing credentials. The old keystore remains in Git history, so it must be treated as compromised; the new Actions secrets still need to be configured before release workflows can publish. The updater now requires a checksum and checks the APK signer against the installed app before installation.

This app is intentionally designed for local-network audio streaming without user accounts, pairing, authentication, or encryption. That design choice is not treated as a defect in this assessment.

## Findings

### High priority

1. **Release key exposure and migration — remediation configured; secret setup pending.**
   - Commit `8268319` added a keystore to Git and Gradle used it as a release fallback with public default credentials. The binary has now been removed from the current source tree and is ignored, but remains in Git history and should be considered compromised.
   - Both release workflows now require `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` Actions secrets. They decode the keystore into runner temp storage; Gradle fails release packaging when credentials are missing. These secrets must be populated before releases resume.
   - Check a published APK's signing certificate before the key transition. Installations using the exposed certificate cannot update in place to an unrelated replacement key and will need a reinstall migration.

2. **Updater verification fail-open behavior — corrected.**
   - `UpdateDownloader` now fails if a release has no checksum asset, the checksum cannot be fetched, or its value is malformed/mismatched. It also checks the downloaded APK's signing identity against the installed app, allowing only Android-verified forward signing-certificate rotation.
   - Added tests for missing, malformed, mismatched, and valid checksums, plus matching, mismatched, missing, and rotated signing identities.
   - SHA-256 still verifies that the APK matches the checksum published with that release; because both assets come from the same GitHub release, the checksum is not an independent publisher signature.

### Medium priority

3. **Automated checks do not cover Android device behavior.**
   - The project has many JVM unit tests, but the release workflows run Gradle tests only; no instrumentation or end-to-end device tests are present in the reviewed tree.
   - Audio capture/playback, MediaProjection lifecycle, foreground-service restrictions, Wi-Fi Direct, and OEM power management vary by Android version and device. Add a small device smoke-test matrix and document known device/OS limitations.

### Low priority

4. **Project documentation has drifted from the implementation.**
   - `docs/RELEASE_MODEL.md` still describes `stable`/`nightly` branches and a base version code of 67, while `RELEASES.md`, the workflows, and `.github/scripts/resolve_version.py` describe builds from `main` and a baseline of 118. `README.md` also lists several source files that are not present under those names.
   - Consolidate release instructions and update the README’s project map so maintainers and users have one accurate source of truth.

5. **The app still uses a generic Android system icon.**
   - `app/src/main/AndroidManifest.xml` sets both `icon` and `roundIcon` to `@android:drawable/ic_dialog_info`. Provide branded launcher and notification artwork for a more polished product experience.

## Recommended additions

- **Guided first-run setup:** explain transmitter/receiver roles, permissions, and network requirements; show actionable remedies for discovery and connection failures.
- **Session health report:** offer an exportable support bundle with connection type, negotiated codec/sample rate, latency/jitter, packet loss, and relevant diagnostic events, with sensitive network details clearly identified.
- **Compatibility and latency presets:** expose a simple “reliable / balanced / low-latency” choice informed by device capabilities, and document tested Android versions and Wi-Fi hardware.

## Verification performed

- `python3 .github/scripts/test_release_system.py` — **passed (12 tests)**.
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH ./gradlew test` — **passed**. The initial attempt used a Java 21 runtime without a compiler; using the installed JDK 17 resolved the environment issue.
- `./gradlew assembleRelease` without signing values — **failed as intended** with the explicit missing release-signing configuration error.

## Suggested action order

1. Verify the certificate used by published builds and ensure CI signing secrets are configured; do not distribute APKs signed by the repository fallback key.
2. Add device-level smoke tests and reconcile documentation.
