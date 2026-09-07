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

Compile the unified APK using Gradle:

```bash
./gradlew assembleDebug
```

Output APK:
- `app/build/outputs/apk/debug/app-debug.apk`
