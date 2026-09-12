# Security Policy

## Reporting Security Issues

If you discover a security vulnerability in Simple Audio Stream, please report it privately by opening a Security Advisory on GitHub or contacting the project maintainers directly. Do not report security vulnerabilities in public issue trackers.

---

## Security Audit & Vulnerability Disclosures

### Disclosure: Compromised Historical Release Signing Key (v1.6.3 - v1.8.5)

#### Summary
In commit `2287b3b3` (version v1.6.3), an Android keystore file (`keystore/release.keystore`) and its credentials (`storePassword = "android"`, `keyAlias = "androiddebugkey"`, `keyPassword = "android"`) were committed into the git repository to address in-app update signature mismatches. This keystore remained tracked in source control through version v1.8.5.

#### Risk Assessment & Impact
Because the private signing key and passwords were exposed in a public Git repository:
- Anyone with access to the repository could extract the private key and sign arbitrary modified APKs with the identical signature.
- An attacker could create a malicious APK that Android would treat as a valid upgrade to any existing installation of Simple Audio Stream (v1.6.3 through v1.8.5).

#### Remediation
1. **Keystore Removed from Source Control**: The file `keystore/release.keystore` was untracked and permanently removed from repository tracking.
2. **Git Ignore Updated**: All keystores (`*.keystore`, `*.jks`, `*.p12`, `*.bks`) and local properties files (`keystore.properties`, `signing.properties`) are strictly excluded in `.gitignore`.
3. **Decoupled Signing Pipeline**: `app/build.gradle.kts` no longer contains hardcoded signing credentials. It reads signing keys from local gitignored files (`keystore.properties` / `local.properties`) or secure environment variables (`RELEASE_KEYSTORE_PATH`, etc.).
4. **Graceful Build Fallback**: Debug builds continue to function without any special configuration. Release builds without configured credentials build unsigned APKs cleanly without failing.
5. **Key Invalidation & Future Releases**: The historical signing key is permanently invalidated. All subsequent releases must be signed with a fresh, private release key. Users upgrading from v1.6.3 - v1.8.5 must uninstall the existing app before installing new releases.

---

### Sensitive Logging Remediation

#### Finding
Wi-Fi Direct (P2P) autonomous group passphrases were logged in plain text to logcat and `AppLogger` during group creation and connection handshakes.

#### Remediation
All log statements in `MainActivity.kt` and `WifiDirectManager.kt` that previously output Wi-Fi Direct passphrases now mask them with `******`. Exported diagnostic reports and logcat dumps no longer contain plain-text Wi-Fi credentials.

---

### Permissions Review

All permissions declared in `AndroidManifest.xml` were audited against actual usage:
- No unused permissions were identified.
- Runtime permissions (`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `NEARBY_WIFI_DEVICES`) are strictly tied to real-time audio capture, foreground service operation, and cross-version Wi-Fi Direct discovery.
- `REQUEST_INSTALL_PACKAGES` is restricted to the opt-in in-app updater feature that opens the Android package installer for downloaded GitHub releases.
