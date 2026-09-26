# Simple Audio Stream — Application Assessment

**Review date:** 2026-09-26
**Review type:** Static repository review, release-system checks, and unit-test verification

## Executive summary

Simple Audio Stream is a feature-rich Android app for transmitting system audio over Wi-Fi/Wi-Fi Direct and receiving it with low-latency playback. Its implementation includes adaptive buffering, packet-loss recovery, multiple discovery transports, diagnostics, saved devices, and an in-app update center. The repository also has a substantial suite of JVM tests.

The main release concern is that a keystore is tracked in the repository and selected as a release-signing fallback. Both GitHub workflows pass signing secrets to Gradle, and configured environment values take precedence, but the workflow files alone do not show whether those repository secrets are configured. The updater's former checksum fail-open behavior has been corrected: it now requires a checksum and checks the APK signer against the installed app before installation.

This app is intentionally designed for local-network audio streaming without user accounts, pairing, authentication, or encryption. That design choice is not treated as a defect in this assessment.

## Findings

### High priority

1. **A private signing key is present in source control.**
   - `keystore/release.keystore` is tracked by Git (`git ls-files` confirms it); `.gitignore` explicitly exempts it from the keystore ignore rule.
   - `app/build.gradle.kts` selects this file by default for release signing and supplies `android` / `androiddebugkey` credentials. Anyone with repository access can extract the key and sign an APK with the same certificate.
   - The stable and nightly workflows pass `RELEASE_KEYSTORE_*` GitHub secrets to Gradle. When those secrets are configured, Gradle uses them in preference to the tracked fallback. This checkout cannot establish whether the secrets exist or which certificate was used for published APKs.
   - Commit `8268319` reintroduced the tracked keystore for deterministic signing, which conflicts with `SECURITY.md`'s claim that the exposed key was permanently removed and invalidated. Any distributed APK signed with this fallback key can be impersonated by another APK signed with the same key. Verify the published APK certificate and CI secret configuration; avoid using the public fallback key for distributed releases.

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

## Suggested action order

1. Verify the certificate used by published builds and ensure CI signing secrets are configured; do not distribute APKs signed by the repository fallback key.
2. Add device-level smoke tests and reconcile documentation.
