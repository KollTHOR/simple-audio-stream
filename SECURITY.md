# Security Policy

## Reporting Security Issues

If you discover a security vulnerability in Simple Audio Stream, please report it privately by opening a Security Advisory on GitHub or contacting the project maintainers directly. Do not report security vulnerabilities in public issue trackers.

---

## Security Audit & Vulnerability Disclosures

### Disclosure: Exposed Release Signing Keystore

#### Summary
In commit `2287b3b3` (version v1.6.3), an Android keystore and its credentials (`storePassword = "android"`, `keyAlias = "androiddebugkey"`, `keyPassword = "android"`) were committed into the git repository to address in-app update signature mismatches. The keystore remained tracked through v1.8.5. Although it was later removed from the working tree, commit `8268319` added a release keystore back to the repository for deterministic CI signing. The release workflows therefore had access to a signing key from the repository checkout even when no external signing secrets were configured.

#### Risk Assessment & Impact
Because release signing material was exposed in repository history and a keystore was reintroduced into the tracked tree:
- Anyone with access to the repository could extract the private key and sign arbitrary modified APKs with the identical signature.
- An attacker could create a malicious APK that Android would treat as a valid upgrade to installations signed by the exposed key.

#### Remediation
1. **Tracked Keystore Removed**: The current source tree no longer contains `keystore/release.keystore`. The exposed binary remains in Git history and must be treated as compromised; deleting it from the latest revision does not make it secret again.
2. **No Release Fallback**: Gradle no longer falls back to a repository keystore or the Android debug key for release builds. Release packaging fails when signing credentials are absent.
3. **Protected CI Signing**: Stable and nightly workflows require `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` Actions secrets. The workflows decode the keystore only into the runner's temporary directory. These secrets must be configured with a newly generated keystore before another release can be published.
4. **Local Signing**: Developers may use a private keystore configured in gitignored `keystore.properties` or environment variables. Keystore files and signing properties are excluded from Git.
5. **User Migration**: A new, unrelated signing certificate cannot update installations signed by the exposed key in place. Users on those builds will need to export any needed settings, uninstall, and install a release signed by the replacement key. Do not distribute another APK signed by the exposed key as the long-term fix.

History rewriting can remove the keystore blob from future clones, but cannot revoke copies already obtained; key replacement is still required.

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
