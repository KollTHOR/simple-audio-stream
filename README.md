# Low-Latency UDP Audio Streamer (Simple Audio Stream)

A high-performance, low-latency, single-module Android application written in Kotlin to stream raw system audio over local Wi-Fi or Wi-Fi Direct between devices (e.g. phone/tablet to dedicated audio player, DAP, or secondary phone). Supports both **Transmitter** (audio capture via `MediaProjection` or microphone) and **Receiver** (low-latency playback via `AudioTrack`) modes in a single unified APK.

---

## Core Features

- **High-Resolution & Lossless Audio**:
  - **Sample Rates**: 44.1 kHz, 48 kHz, 88.2 kHz, 96 kHz, 176.4 kHz, and 192 kHz (Auto / Manual selectable in Settings).
  - **Bit Depths**: 16-bit integer PCM and 24-bit packed PCM (144 dB dynamic range).
- **Latency & Streaming Profiles**:
  - **Auto (Balanced / Adaptive)**: Dynamic floating-watermark jitter buffer (35ms - 400ms) adapting to Wi-Fi jitter in real time.
  - **Music (Reliable / Lossless)**: Uncapped studio-master PCM playback with extended buffering cushion (~2.5s headroom) to absorb burst interference.
  - **Video (Low Latency)**: Compressed Opus audio (~40ms cushion, 80-85% bandwidth reduction) optimized for gaming and video lipsync.
- **Robust Transport & Reliability**:
  - **HAT Protocol (`HatPacket`)**: Custom high-efficiency binary datagram format with 32-bit generation tracking, sequence numbers, presentation timestamps, and codec signaling.
  - **Forward Error Correction (FEC)**: 1 XOR parity packet per 4 audio packets (25% overhead) recovering lost packets without retransmission delays.
  - **Silence Suppression**: Battery and airtime saver automatically entering a 2 packet/sec heartbeat mode during sustained silence.
  - **Transactional Generation Synchronization**: Zero-drop profile transitions synchronized with receiver acknowledgement.
- **Connectivity Options**:
  - **Local Subnet (Wi-Fi / LAN)**: Unicast IP or subnet broadcast (`x.x.x.255`).
  - **Autonomous Wi-Fi Direct (P2P)**: Direct device-to-device streaming without an external router or access point.
  - **Automatic Discovery**: UDP broadcast discovery on port `50006`.
- **Integrated Diagnostics & Testing**:
  - **In-App Update Center**: Dual-channel release model (Stable & Nightly), historical release browsing, SHA-256 verification, and downgrade/rollback protection. See [Release Model Guide](docs/RELEASE_MODEL.md).
  - **Runtime Diagnostics (`HatDiagnostics`)**: Central event ring buffer, periodic telemetry snapshots, and jitter metrics.

---

## Project Structure

```
.
├── build.gradle.kts                            # Root Gradle build script
├── settings.gradle.kts                         # Single module configuration (:app)
├── gradle.properties                           # JVM & AndroidX settings
├── gradle/
│   ├── libs.versions.toml                      # Versions: AGP 8.7.3, Kotlin 2.0.21, AndroidX
│   └── wrapper/                                # Gradle 8.10.2 wrapper
└── app/                                        # Unified Application Module
    ├── build.gradle.kts                        # compileSdk 35, minSdk 29, targetSdk 35
    └── src/main/
        ├── AndroidManifest.xml                 # Permissions, foreground services, package queries
        ├── kotlin/com/example/audiostreamer/
        │   ├── MainActivity.kt                 # Mode toggle (Transmitter vs Receiver), P2P UI
        │   ├── SettingsActivity.kt             # Audio preferences, in-app updater
        │   ├── AudioConfig.kt                  # Audio pipeline constants & profile definitions
        │   ├── AudioCaptureService.kt          # MediaProjection capture, encoding & UDP streaming
        │   ├── AudioSinkService.kt             # UDP reception, JitterBuffer & AudioTrack playback
        │   ├── HatPacket.kt                    # HAT packet wire protocol format & builder
        │   ├── HatPacketParser.kt              # Zero-allocation packet parser
        │   ├── JitterBuffer.kt                 # Adaptive jitter buffer, RFC 3550 drift estimation
        │   ├── FecCodec.kt                     # XOR Forward Error Correction encoder & decoder
        │   ├── AudioResampler.kt               # Linear interpolation audio resampler
        │   ├── WifiDirectManager.kt            # Autonomous P2P Wi-Fi Direct group manager
        │   ├── HatDiagnostics.kt               # Diagnostic snapshots, event ring buffer & timings
        │   └── diagnostics/                    # Runtime diagnostics subsystem
        │       ├── ReceiverDiagnosticsState.kt # Playout latency and receiver health data models
        │       ├── LatencyHistory.kt           # Zero-allocation 60-second rolling latency ring buffer
        │       ├── LatencyGraphView.kt         # Custom realtime canvas graph for receiver playout latency
        │       ├── ReceiverDiagnosticsRepository.kt # Observational diagnostics repository
        │       └── DiagnosticsViewModel.kt     # Lifecycle-aware ViewModel driving diagnostics UI
        └── res/
            ├── layout/                         # UI layouts (activity_main, activity_settings)
            └── values/                         # Colors, strings, themes
```

---

## Audio Pipeline & Wire Protocol

### HAT Packet Format
Audio datagrams follow the custom binary HAT (`HT`) format:
- **Magic Bytes**: `0x48 0x54` (`"HT"`). Non-HAT packets (such as JSON control messages starting with `{`) are rejected instantly with zero allocations.
- **Flags**: Profile signaling (`AUTO`, `MUSIC`, `LOW_LATENCY`), bit depth (`16` vs `24`), silence indicator, and FEC parity flag.
- **Header Fields**:
  - `Stream ID` (16-bit): Identifies active stream instance.
  - `Generation` (32-bit): Monotonically increments on every profile or pipeline reconfiguration.
  - `Sequence Number` (32-bit): Detects lost, duplicate, or out-of-order packets.
  - `Timestamp` (32-bit): Presentation timestamp for jitter calculation.
  - `Payload Length` (16-bit): Payload size in bytes.
- **Default UDP Ports**:
  - Streaming audio: `50005`
  - Peer discovery: `50006`

---

## Streaming Modes

### 1. Transmitter (Audio Source)
- Captures internal device audio via Android 10+ `MediaProjection` (`AudioPlaybackCaptureConfiguration`).
- Target address can be an individual device IP (`192.168.1.100`) or subnet broadcast (`192.168.1.255`).
- Runs as a foreground service (`mediaProjection`) with a persistent status notification.
- Supports live runtime profile and format changes without restarting the application.

### 2. Receiver (Audio Sink)
- Listens on UDP port `50005` for incoming audio packets.
- Automatically handles sample rate switching, 16/24-bit decoding, and Opus decompression.
- Reconstructs audio using a jitter buffer and outputs to `AudioTrack` with `PERFORMANCE_MODE_LOW_LATENCY`.
- Acquires `WIFI_MODE_FULL_HIGH_PERF` and partial wake lock for glitch-free playback with the screen off.

### 3. Autonomous Wi-Fi Direct (P2P)
- Creates an autonomous Wi-Fi Direct group on the Receiver.
- Transmitter connects directly to the Receiver's hotspot using auto-generated credentials.
- Bypasses home Wi-Fi routers entirely for minimal latency and zero network congestion.

---

## Building the Project

### Prerequisites
- JDK 17 (`JAVA_HOME` pointing to OpenJDK 17)
- Android SDK with Platform 35 and Build-Tools 35.0.0

### Debug Build
```bash
./gradlew assembleDebug
```
Output APK:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Release Build
```bash
./gradlew assembleRelease
```
Output APK:
```
app/build/outputs/apk/release/app-release.apk
```

> [!NOTE]
> When private release keystore properties (`keystore.properties`) are not present in the workspace, Gradle automatically signs the release build with the standard debug key. This produces a valid, installable signed APK (`app-release.apk`) that updates seamlessly over existing debug installations without signature conflict.

#### Optional: Custom Release Signing Key
To sign with a custom private release key, copy `keystore.properties.example` to `keystore.properties` (gitignored) or set environment variables:
```bash
export RELEASE_KEYSTORE_PATH="/path/to/your/release.keystore"
export RELEASE_KEYSTORE_PASSWORD="your_keystore_password"
export RELEASE_KEY_ALIAS="your_key_alias"
export RELEASE_KEY_PASSWORD="your_key_password"
./gradlew assembleRelease
```

### Running Unit Tests
```bash
./gradlew testDebugUnitTest
```

---

## Permissions Audit

The application declares the following permissions in `AndroidManifest.xml`, strictly required for streaming and diagnostic operations:

- `RECORD_AUDIO`: Required to capture internal audio playback via `AudioPlaybackCaptureConfiguration` in `AudioCaptureService`.
- `INTERNET`: Required for UDP packet streaming and control telemetry.
- `ACCESS_NETWORK_STATE` & `ACCESS_WIFI_STATE`: Required to inspect network interfaces and retrieve local IP addresses.
- `WAKE_LOCK`: Required for CPU wake locks to keep audio streaming with the screen off.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: Required for persistent background capture and playback.
- `POST_NOTIFICATIONS`: Required on Android 13+ (API 33+) to show foreground service status notifications.
- `CHANGE_WIFI_MULTICAST_STATE`: Required on the receiver for UDP device discovery packets.
- `CHANGE_WIFI_STATE`: Required for Wi-Fi Direct (P2P) autonomous group creation.
- `ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION`: Required by the Android framework on API 29-32 for Wi-Fi Direct peer discovery.
- `NEARBY_WIFI_DEVICES`: Required on Android 13+ (API 33+) for Wi-Fi Direct peer discovery without location permissions.
- `REQUEST_INSTALL_PACKAGES`: Required for the in-app updater in Settings to launch the system package installer when an update is downloaded.
