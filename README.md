# Low-Latency UDP Audio Streamer (Unified Android App)

A minimal, high-performance, single-module Android application written in Kotlin to stream raw system audio over local Wi-Fi UDP between devices (e.g. sender phone to receiver DAP). Supports both **Transmitter** (audio source capture via `MediaProjection`) and **Receiver** (audio sink playback via `AudioTrack`) modes in a single unified APK.

---

## Project Structure

```
/home/kollthor/Code/
├── build.gradle.kts                            # Root Gradle build script
├── settings.gradle.kts                         # Single module configuration (:app)
├── gradle.properties                           # JVM & AndroidX settings
├── gradle/
│   ├── libs.versions.toml                      # Versions: AGP 8.7.3, Kotlin 2.0.21, AndroidX
│   └── wrapper/                                # Gradle 8.10.2 wrapper
└── app/                                        # Unified Application Module
    ├── build.gradle.kts                        # compileSdk 35, minSdk 29, targetSdk 35
    └── src/main/
        ├── AndroidManifest.xml                 # Unified permissions & services
        ├── kotlin/com/example/audiostreamer/
        │   ├── AudioConfig.kt                  # Shared singleton audio configuration
        │   ├── AudioCaptureService.kt          # System audio capture & UDP streaming service
        │   ├── AudioSinkService.kt             # UDP reception & low-latency AudioTrack service
        │   └── MainActivity.kt                 # Mode toggle (Transmitter vs Receiver) & UI
        └── res/
            ├── layout/activity_main.xml        # Single XML layout with Mode Selector & inputs
            └── values/
```

---

## Audio Pipeline & Packet Format

Shared via singleton [`AudioConfig`](app/src/main/kotlin/com/example/audiostreamer/AudioConfig.kt):
- **Sample Rate**: 48,000 Hz
- **Channels**: 2 (Stereo)
- **Encoding**: 16-bit PCM (`AudioFormat.ENCODING_PCM_16BIT`)
- **Frame Duration**: 10 ms
- **Packet Sizing**: $48{,}000 \times 4\text{ bytes/s} \times 0.010\text{ s} = \mathbf{1{,}920}\text{ bytes}$ per packet (480 frames)
- **Default UDP Port**: `50005`
- **Zero-Allocation Tight Loop**: Pre-allocated byte buffers and `DatagramPacket` objects avoid garbage collector pauses.
- **Broadcast Support**: UDP sockets enable `broadcast = true` so transmission can target individual IPs (e.g. `192.168.43.100`) or subnet broadcasts (e.g. `192.168.43.255`).

---

## Architecture & Features

### 1. Mode Selector
- **Transmitter (Send)**:
  - Displays Target IP (pre-filled with `192.168.43.255`) and Port (`50005`).
  - Requests `RECORD_AUDIO` and `POST_NOTIFICATIONS` permissions.
  - Launches `MediaProjectionManager.createScreenCaptureIntent()`.
  - Starts [`AudioCaptureService`](app/src/main/kotlin/com/example/audiostreamer/AudioCaptureService.kt) with foreground service type `mediaProjection`.
  - Automatically stops `AudioSinkService` if it was active.
- **Receiver (Listen)**:
  - Hides IP input, exposes Port field (`50005`).
  - Starts [`AudioSinkService`](app/src/main/kotlin/com/example/audiostreamer/AudioSinkService.kt) with foreground service type `mediaPlayback`.
  - Acquires `PowerManager.PARTIAL_WAKE_LOCK` and `WifiManager.WIFI_MODE_FULL_HIGH_PERF` to maintain low-latency playback with the screen off.
  - Automatically stops `AudioCaptureService` if it was active.

---

## Building the Project

### Debug Build

Compile the debug APK using Gradle:

```bash
./gradlew assembleDebug
```

Output APK:
- `app/build/outputs/apk/debug/app-debug.apk`

### Release Build & Signing Configuration

By default, running `./gradlew assembleRelease` without signing credentials produces an unsigned release APK (`app/build/outputs/apk/release/app-release-unsigned.apk`).

To sign release builds, configure signing credentials through one of two methods (never committed to Git):

#### Option A: `keystore.properties` (Recommended for local development)

1. Copy `keystore.properties.example` to `keystore.properties` in the project root:
   ```bash
   cp keystore.properties.example keystore.properties
   ```
2. Fill in your release keystore details:
   ```properties
   releaseKeystorePath=/path/to/your/release.keystore
   releaseKeystorePassword=your_keystore_password
   releaseKeyAlias=your_key_alias
   releaseKeyPassword=your_key_password
   ```

Note: `keystore.properties` and all `*.keystore` / `*.jks` files are ignored in `.gitignore`.

#### Option B: Environment Variables (Recommended for CI/CD)

Export the following environment variables prior to running the build:

```bash
export RELEASE_KEYSTORE_PATH="/path/to/your/release.keystore"
export RELEASE_KEYSTORE_PASSWORD="your_keystore_password"
export RELEASE_KEY_ALIAS="your_key_alias"
export RELEASE_KEY_PASSWORD="your_key_password"

./gradlew assembleRelease
```

Output APK:
- `app/build/outputs/apk/release/app-release.apk`

---

## Security Notice

### Compromised Historical Signing Key Notice (v1.6.3 - v1.8.5)

In releases from v1.6.3 through v1.8.5, an Android debug keystore was committed to the repository at `keystore/release.keystore` with publicly visible credentials (`android` / `androiddebugkey`).

Because this signing key was committed to a public repository:
- The signing key used for APK releases v1.6.3 through v1.8.5 is considered compromised.
- Any APK signed with that historical key should not be trusted if obtained from untrusted or third-party sources.
- The committed keystore and hardcoded credentials have been purged from repository tracking.
- Moving forward, all official releases must be signed with an independent private release key maintained outside source control.
- If upgrading from versions v1.6.3 - v1.8.5 to a future release signed with a new private key, Android will require uninstalling the old version first due to the signature mismatch.

For detailed vulnerability disclosure information, see [`SECURITY.md`](SECURITY.md).

---

## Permissions Audit

The application declares the following permissions in `AndroidManifest.xml`, all of which are strictly required for its streaming operations:

- `RECORD_AUDIO`: Required by Android system to capture audio playback via `AudioPlaybackCaptureConfiguration` in `AudioCaptureService`.
- `INTERNET`: Required for local UDP audio packet transmission and reception across devices.
- `ACCESS_NETWORK_STATE` & `ACCESS_WIFI_STATE`: Required to inspect local network interfaces and determine local IP addresses.
- `WAKE_LOCK`: Required to acquire a partial CPU wake lock, ensuring audio streaming continues uninterrupted when the device screen turns off.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: Required on Android 10+ (and enforced on Android 14+) to run persistent background capture and playback services.
- `POST_NOTIFICATIONS`: Required on Android 13+ (API 33+) to display ongoing service status notifications for foreground services.
- `CHANGE_WIFI_MULTICAST_STATE`: Required on the receiver to acquire a multicast lock for local device discovery packets.
- `CHANGE_WIFI_STATE`: Required for Wi-Fi Direct (P2P) group creation and peer negotiation.
- `ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION`: Required by the Android OS framework on Android 10-12 (API 29-32) and vendor OS skins (e.g. Xiaomi MIUI/HyperOS) for Wi-Fi Direct peer discovery.
- `NEARBY_WIFI_DEVICES`: Required on Android 13+ (API 33+) for Wi-Fi Direct peer discovery.
- `REQUEST_INSTALL_PACKAGES`: Required for the in-app updater feature in Settings to launch the system package installer when an update is downloaded from GitHub Releases.
