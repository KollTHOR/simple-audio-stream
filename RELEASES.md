# Simple Audio Stream: Release, Branching, and Versioning System

This document describes the branch topology, tagging policy, automated nightly and stable release pipelines, Android versioning mechanics, and rollback procedures for **Simple Audio Stream**.

---

## 1. Branch Strategy

The repository follows a single primary branch development model:

* **`main`**: The primary branch and source of truth for all active development.
  * Receives all feature work and bug fixes (directly or via temporary feature/fix branches).
  * Both nightly builds and stable releases originate from commits on `main`.
* **Temporary branches** (deleted after merging into `main`):
  * `feature/<feature-name>`
  * `fix/<bug-name>`
* **Deprecated branches**:
  * The historical permanent `nightly` and `stable` branches are retired. No new builds or releases target them.

```
       main (active development)
        |
        +---- commit A  --->  Automated Nightly Pre-release
        |
        +---- commit B  --->  Automated Nightly Pre-release
        |
        +---- commit C (tagged v1.8.15)  --->  Stable GitHub Release
        |
        +---- commit D  --->  Automated Nightly Pre-release
```

---

## 2. Release Identifiers & Naming

### Stable Releases

* **Trigger**: Pushing a semantic version tag to GitHub: `vX.Y.Z` (e.g., `git push origin v1.8.15`).
* **Tag Format**: `vX.Y.Z` (must point to an exact commit on `main`).
* **Release Type**: Normal GitHub Release (`prerelease: false`).
* **Version Name**: `X.Y.Z` (e.g., `1.8.15`).
* **Version Code**: Monotonically increasing CI-assigned integer strictly higher than any previous build.
* **Artifact Filename**: `SimpleAudioStream-X.Y.Z.apk` (and corresponding `.sha256`).

### Nightly Builds

* **Trigger**: Automatic on every push to `main` (or manual trigger via `workflow_dispatch`).
* **Tag Format**: `nightly-YYYYMMDD-SHORT_SHA` (e.g., `nightly-20260919-3c2b2cc`).
  * Immutable: tags point to the exact commit and are never moved or overwritten.
* **Release Type**: GitHub Pre-release (`prerelease: true`).
* **Version Name**: `<baseVersion>-nightly.YYYYMMDD+<short_sha>` (e.g., `1.8.15-nightly.20260919+3c2b2cc`).
* **Version Code**: Monotonically increasing CI-assigned integer strictly higher than any previous build.
* **Artifact Filename**: `SimpleAudioStream-<versionName>.apk` (and corresponding `.sha256`).

---

## 3. Version Code Strategy (Internal Monotonic Build Counter)

Android enforces that an app cannot be updated if the incoming APK has a lower `versionCode` than the currently installed build.

To guarantee that users can seamlessly upgrade from nightlies to stable releases and from nightlies to newer nightlies without false rollbacks:

1. **Monotonically Increasing**: `versionCode` increases strictly on **every build across both nightly and stable**.
2. **CI-Controlled**: The CI pipeline dynamically determines the next build number using:
   $$\text{versionCode} = \max(\text{Baseline} + 1, \text{Highest Published Release Code} + 1, \text{Baseline} + \text{RUN\_NUMBER})$$
   where $\text{Baseline} = 118$ (the highest historical published code).
3. **Never Decreases**: When a stable release is created, its `versionCode` is assigned higher than any preceding nightly.
4. **Local Builds**: When building locally outside CI (`./gradlew assembleDebug`), Gradle falls back to `118` or `git rev-list --count HEAD`, ensuring offline builds work without polluting release numbers.

---

## 4. How to Create a Stable Release

Stable releases are created intentionally by tagging an exact verified commit on `main`:

```bash
# 1. Ensure your local main branch is up to date and clean
git checkout main
git pull origin main

# 2. Run test verification locally
./gradlew test

# 3. Create the signed semantic version tag pointing to HEAD of main
git tag -a v1.8.15 -m "Release v1.8.15"

# 4. Push the tag to GitHub
git push origin v1.8.15
```

### What CI does automatically:
1. Verifies that the tag format matches `vX.Y.Z`.
2. Verifies that the tagged commit exists on `main` (rejects tags pointing to non-main commits).
3. Verifies that the tag is not already published (protects against overwrites).
4. Assigns the next unique monotonic `versionCode`.
5. Runs the full unit test suite (`./gradlew test`).
6. Builds the signed APK: `SimpleAudioStream-1.8.15.apk`.
7. Computes SHA-256 checksum: `SimpleAudioStream-1.8.15.apk.sha256`.
8. Creates the official GitHub Release with release notes and attaches the APK and checksum.

---

## 5. How Nightly Builds are Generated

Nightly builds require zero manual intervention:

1. Push your commit or merge a pull request to `main`:
   ```bash
   git checkout main
   git push origin main
   ```
2. The GitHub Actions workflow (`release-nightly.yml`):
   - Runs full unit tests (`./gradlew test`).
   - Obtains commit SHA and UTC build date.
   - Calculates the next monotonic `versionCode`.
   - Generates the human-readable `versionName` (e.g. `1.8.15-nightly.20260919+3c2b2cc`).
   - Builds the signed APK: `SimpleAudioStream-1.8.15-nightly.20260919+3c2b2cc.apk`.
   - Tags the exact commit: `nightly-20260919-3c2b2cc`.
   - Publishes a GitHub Pre-release with attached APK, checksum, and embedded metadata.

---

## 6. How to Identify the Exact Source Commit of an APK

Every APK produced by the release pipeline is traceable to its exact Git commit:

1. **From the Filename**:
   * Nightly filenames contain the 7-character short SHA:
     `SimpleAudioStream-1.8.15-nightly.20260919+3c2b2cc.apk` $\rightarrow$ commit `3c2b2cc`.
   * Stable filenames contain the semantic version tag:
     `SimpleAudioStream-1.8.15.apk` $\rightarrow$ tag `v1.8.15`.
2. **From Inside the Running App**:
   * Open **Settings $\rightarrow$ In-App Update Center**.
   * The footer displays:
     `Version 1.8.15-nightly.20260919+3c2b2cc (Build 119) • Commit: 3c2b2cc • Channel: Nightly`.
3. **From GitHub Release Metadata**:
   * Every release contains machine-readable metadata in its body:
     `<!-- metadata: channel=... commit=3c2b2cca7999... versionCode=119 versionName=... -->`

---

## 7. In-App Update Center & Channel Behavior

The app includes an integrated Update Center with two channels:

| Channel | Default For | Behavior |
| :--- | :--- | :--- |
| **STABLE** | Stable installations | Only stable releases are surfaced. Nightly builds are never shown. |
| **NIGHTLY** | Nightly installations | Both nightly builds and stable releases are surfaced. When a new stable version is published, it is recognized as a valid release transition and offered as an upgrade. |

### Version Comparison Model:
* Compares `versionCode` first: higher `versionCode` $\rightarrow$ `NEWER`.
* Safeguard semantic comparison prevents false rollbacks if an older tag is checked.
* Stable releases of the same line (`1.8.15`) are recognized as newer than prereleases (`1.8.15-nightly`).
* Older stable builds (e.g. `v1.8.12`) are correctly recognized as `OLDER` (Rollback) when viewed from a newer nightly build (`1.8.15-nightly`), preventing accidental downgrades.

---

## 8. Rollback Model & Policy

Android's package installer blocks downgrading an app to an APK with a lower `versionCode` to prevent data corruption and security regressions.

### The Correct Rollback Procedure:
1. **Source Rollback (Code Revert)**:
   Revert the faulty commit on `main`:
   ```bash
   git revert <bad-commit-sha>
   git push origin main
   ```
2. **Release Rollback (Forward-Fix)**:
   Do **NOT** try to rollback by lowering `versionCode` or re-releasing an old APK with a lower number. Instead, publish a **new release** containing the restored code with a **higher `versionCode`**.
   Android will install the new release seamlessly without data loss.

3. **Manual APK Downgrade (Device-Side)**:
   If an individual device needs to install an older APK immediately:
   * **Option A (Preserves data via ADB)**:
     ```bash
     adb install -d -r SimpleAudioStream-v1.8.12.apk
     ```
   * **Option B (Clean reinstall)**:
     Uninstall the existing app from device Settings (warning: clears local app settings), then install the older APK.

---

## 9. CI Safeguards

The release pipeline incorporates safeguards to prevent common release failures:

* **No Overwrites**: Stable tags cannot overwrite an existing published release.
* **No Moving Tags**: Nightly tags are unique per date and commit (`nightly-YYYYMMDD-SHORT_SHA`); re-tagging fails explicitly.
* **Main Branch Enforcement**: Stable release tags must point to a commit that is merged into `main`.
* **Pre-Release Test Gate**: If unit tests fail, the workflow immediately terminates before any APK is signed or published.
* **Concurrency Lock**: All release workflows share `concurrency: group: release-pipeline`, preventing race conditions or duplicate version codes.
