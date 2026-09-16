# Simple Audio Stream: Release & Update Infrastructure

This document describes the dual-channel release model, deterministic versioning scheme, automated GitHub Actions release workflows, in-app Update Center architecture, checksum verification, and rollback mechanics for Simple Audio Stream.

---

## 1. Release Channel Model

Simple Audio Stream provides two distinct release tracks to balance development velocity and operational stability:

| Channel | Target Audience | Source Branch | GitHub Release Type | Naming Format |
|---|---|---|---|---|
| **STABLE** | Production / daily listening | `stable` | Official Release (non-prerelease) | `1.8.13` (Tag: `v1.8.13`) |
| **NIGHTLY** | Development & experimental builds | `nightly` | Pre-release | `1.8.13-nightly.YYYYMMDD+sha` (Tag: `nightly-YYYYMMDD-sha`) |

### Branch Roles
- **`stable`**: Contains only production-ready, thoroughly validated code. Promoted versions receive annotated semantic tags (`vX.Y.Z`).
- **`nightly`**: Active development integration branch. Every push automatically builds and publishes a pre-release on GitHub.
- **`main`**: Central development trunk containing shared integration work and documentation.

---

## 2. Deterministic Versioning Scheme

Android requires a monotonically increasing 32-bit integer `versionCode` for package updates. Simple Audio Stream computes this automatically:

### Version Code (`versionCode`)
- Derived from total Git commit history: `git rev-list --count HEAD`.
- Clamped to a minimum base (`baseVersionCode = 67`) to guarantee it never drops below historical published releases.
- Guarantees strictly monotonic progression across all branches and builds without manual code bumping.

### Version Name (`versionName`)
- **Stable Builds**: Defined by semantic versioning:
  ```
  1.8.13
  ```
- **Nightly Builds**: Includes base semantic version, channel identifier, UTC date stamp, and 7-character Git commit hash:
  ```
  1.8.13-nightly.20260916+3cd11fe
  ```

### Embedded Build Identifiers (`BuildConfig`)
The build script generates the following metadata in `BuildConfig`:
- `BuildConfig.BUILD_CHANNEL`: `"stable"` or `"nightly"`
- `BuildConfig.GIT_COMMIT_SHA`: 7-character short commit hash
- `BuildConfig.BUILD_TIMESTAMP`: `YYYYMMDD` UTC date string
- `BuildConfig.BASE_VERSION_NAME`: `1.8.13`

---

## 3. GitHub Actions Release Workflows

Two workflows in `.github/workflows/` automate packaging and distribution:

### Stable Release Workflow (`release-stable.yml`)
- **Trigger**: Tag push matching `v*.*.*` (e.g. `v1.8.14`).
- **Build Step**: Executes `./gradlew assembleRelease` with `BUILD_CHANNEL=stable`.
- **Integrity**: Calculates cryptographic SHA-256 checksum and saves to `app-release.apk.sha256`.
- **Publishing**: Creates an official GitHub Release (marked as standard production release, not prerelease) with the APK and `.sha256` checksum attached.

### Nightly Build Workflow (`release-nightly.yml`)
- **Trigger**: Push to the `nightly` branch.
- **Build Step**: Executes `./gradlew assembleRelease -Pchannel=nightly`.
- **Integrity**: Calculates SHA-256 checksum and outputs `app-nightly.apk.sha256`.
- **Publishing**: Creates a GitHub Release marked as **Prerelease** tagged `nightly-YYYYMMDD-<sha>`.

---

## 4. In-App Update Center Architecture

The settings screen features a modern, comprehensive 4-card **Update Center**:

```
┌─────────────────────────────────────────────────────────┐
│ 1. Current Installation                                 │
│    • Version 1.8.13 (Build 109)                         │
│    • Channel: Stable • Commit: 3cd11fe • Date: 2026-09-16│
│    [Check for Updates Button]                           │
├─────────────────────────────────────────────────────────┤
│ 2. Release Channel Selector                             │
│    (o) Stable (Official production releases)            │
│    ( ) Nightly (Automated development builds)           │
├─────────────────────────────────────────────────────────┤
│ 3. Latest Available Candidate                           │
│    • Status: "Up to date" / "New version available"     │
│    • Streaming download progress bar (0 - 100%)         │
│    [Update Now / Check Updates / View Details]          │
├─────────────────────────────────────────────────────────┤
│ 4. Release History & Pagination                         │
│    Filters: [All]  [Stable]  [Nightly]                  │
│    • List of published releases with status badges:     │
│      - CURRENT / NEWER / OLDER                          │
│      - Tag, date, commit, size                          │
│      - Action buttons: [Details] [Install / Rollback]   │
│    [Load More Releases Button (Paginated API)]          │
└─────────────────────────────────────────────────────────┘
```

### Components (`com.example.audiostreamer.update`)
1. **`BuildInfo`**: Models the currently executing app binary (version, code, channel, commit, build timestamp).
2. **`ReleaseModel` (`GithubRelease`, `ReleaseAsset`)**: Parses GitHub REST API JSON, categorizes channels, identifies APK and checksum assets, and formats release metadata.
3. **`VersionComparator`**: Deterministic ordering logic comparing version codes first, falling back to semantic version analysis with nightly date support.
4. **`ChecksumVerifier`**: Computes SHA-256 hashes of downloaded files and matches against `.sha256` manifests.
5. **`UpdateRepository`**: Manages paginated GitHub API queries, local SharedPreferences caching (30-minute freshness window, offline fallback), and candidate selection.
6. **`UpdateDownloader`**: Streams APK downloads with redirect tracking, validates package name (`com.example.audiostreamer`), and extracts archive metadata.
7. **`UpdateInstaller`**: Pre-flight inspection for installation permissions, downgrade detection, and FileProvider intent launching.

---

## 5. Security & Verification

1. **SHA-256 Integrity Verification**:
   - For every release, the updater attempts to download both the `.apk` and the `.apk.sha256` asset.
   - The file's SHA-256 hash is computed in streaming chunks.
   - If a checksum asset is present, the downloaded binary MUST match before installation is allowed.
2. **Package Identity Validation**:
   - The downloader inspects the downloaded APK via Android's `PackageManager.getPackageArchiveInfo()`.
   - The package name must match `com.example.audiostreamer`.
   - Any mismatched or corrupt archive is rejected immediately before triggering system install intents.

---

## 6. Rollback Detection & Android OS Constraints

### Why Android Blocks Rollbacks
Android OS security rules (`PackageManagerService`) strictly enforce that an installed application cannot be replaced by an APK with a lower `versionCode` (`INSTALL_FAILED_VERSION_DOWNGRADE`). A regular application without root or platform system signatures cannot bypass this restriction.

### Transparent Rollback UX
Rather than pretending a downgrade is possible or failing silently:
1. When a user selects an older release (where `targetVersionCode < installedVersionCode`), the app displays a **Downgrade Warning Dialog**.
2. The dialog explicitly informs the user:
   - An older version is selected (e.g. attempting to downgrade to `v1.8.13`).
   - Android will reject in-place downgrades.
3. The user is presented with two clear options:
   - **Download APK Only**: Saves the APK to device storage. The user can manually uninstall the current app and install the older version (with a clear notice that app settings/preferences will be reset).
   - **Developer Rollback (ADB)**: For developers and testers who need to retain app data during rollback, the exact ADB command is provided:
     ```bash
     adb install -d -r /path/to/app-release.apk
     ```

---

## 7. Operator Guide: Release Workflows

### How to Create a Nightly Build
1. Commit changes to your feature branch or directly to `nightly`.
2. Push to the `nightly` branch:
   ```bash
   git checkout nightly
   git merge feature-branch
   git push origin nightly
   ```
3. GitHub Actions automatically packages, checksums, and publishes a new prerelease on GitHub.

### How to Promote a Nightly Build to Stable
1. Test and validate the nightly build.
2. Merge the validated changes into `stable`:
   ```bash
   git checkout stable
   git merge nightly
   git push origin stable
   ```
3. Tag the release with the new semantic version:
   ```bash
   git tag v1.8.14
   git push origin v1.8.14
   ```
4. GitHub Actions automatically packages the official Stable release and attaches the signed APK and SHA-256 checksum.
