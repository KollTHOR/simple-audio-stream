# Simple Audio Stream

Turn any two Android phones or tablets into a wireless audio system: play sound on one device and have it come out of the other's speakers over your local network — no computer, no cloud, no account, and no Wi-Fi router required.

**Download:** [latest stable release](https://github.com/KollTHOR/simple-audio-stream/releases/latest) · [all releases incl. nightlies](https://github.com/KollTHOR/simple-audio-stream/releases)

---

## The problem it solves

Android has no built-in way to send the audio one app is playing to a *different* Android device's speakers. Bluetooth pairs phone→headset, not phone→phone, and it can't carry high-quality program audio between two handsets. Casting ecosystems (Chromecast, AirPlay) need a specific receiver or a computer.

Simple Audio Stream fills that gap. Point it at another Android device and it captures the playing audio on one handset and reconstructs it in near-real-time on the other, so you can:

- Play a movie or game from a phone and hear it through a tablet on the other side of the room (or a tablet plugged into speakers).
- Use an old phone as a dedicated network speaker for a music app running elsewhere.
- Beam audio to a better-sounding device than the one driving playback.

Everything stays on your local network or a direct device-to-device link. Nothing is uploaded, no sign-in, no third-party server.

## What it does

- **System-audio capture** on Android 10+ via `MediaProjection` — it streams the audio your apps are playing, not the microphone. Android shows its own audio-sharing consent prompt before capture begins.
- **Automatic discovery** of nearby devices over the LAN (mDNS) and, when there is no network, over **Wi-Fi Direct**. Optional **Bluetooth Low Energy** and **NFC** just exchange connection details during setup — neither carries audio.
- **Four audio paths** — raw PCM, a custom bit-exact **lossless** codec, **Opus**, and **AAC** — chosen by profile and by what both devices support.
- **Three streaming profiles**: Auto (adaptive), High-resolution/lossless (up to 192 kHz, 24-bit), and Low-latency (Opus/AAC for gaming and video where lip-sync matters).
- **Resilient transport** — forward error correction and selective retransmission recover lost packets, and a jitter buffer hides Wi-Fi timing variation (details below).
- **One-to-many**: broadcast to several receivers at once, each with its own volume.
- **Metadata & remote control**: when optional Notification access is granted, the receiver shows the track, artist and artwork and can send play/pause/skip back to the source.
- **On-screen diagnostics and an in-app update center** (stable + nightly).

## How it works

### The stack

Pure **Kotlin**, Android `minSdk 29` / `targetSdk 35`. No backend, no broker, no cloud.

| Concern | How it's done |
|---|---|
| Capture | `MediaProjection` + `AudioRecord` (playback capture), not the microphone |
| Encode | Concentus (pure-JVM Opus), Android `MediaCodec` (AAC), and a custom lossless codec (below) |
| Transport | Custom **HAT** protocol over UDP (below) |
| Discovery | DNS-SD/mDNS, Wi-Fi Direct, BLE GATT, NFC |
| UI | Jetpack + Material 3 (Material You theming); dark-only |

### Discovery — finding the other device

Devices announce themselves as a `_hats._tcp` DNS-SD service and reply over a UDP broadcast channel, advertising stable node IDs plus capabilities. When there's no LAN, the receiver hosts a **Wi-Fi Direct group** and the transmitter joins it.

Two optional *bootstrap* helpers exist purely to hand over Wi-Fi Direct credentials (`SSID` / passphrase / group-owner IP / port) so a transmitter can form a Direct link without typing anything:

- **BLE**: the receiver advertises a GATT service carrying those details as small JSON; the transmitter reads it, then connects over Wi-Fi Direct.
- **NFC**: tapping compatible devices exchanges node identity/capability information.

BLE and NFC never carry audio. If discovery fails, the app still lets you connect by entering the receiver's IP address directly.

### The HAT transport

HAT (High-definition Audio Transport) is the app's packet protocol, spoken over **UDP** on port **50005** (discovery on **50006**). Each datagram starts with a compact 24-byte header carrying magic bytes, a protocol version, a packet type, a 16-bit sequence number, a 64-bit audio-frame timestamp, the codec/sample-rate/format, per-packet volume, a FEC block index, and a 32-bit **generation** counter.

Why UDP: on a lossy wireless link, *late* audio is worse than *missing* audio. TCP retransmits with head-of-line blocking — one dropped packet stalls everything behind it and you get freezes. HAT instead ships datagrams best-effort and recovers losses deliberately at the application layer:

- **Adaptive forward error correction (FEC).** Each block of audio packets also carries an XOR parity packet so a single drop self-heals without a round-trip. Because that would otherwise cost ~25% airtime even on a clean link, FEC is **adaptive**: it engages when loss is measured and turns off on a quiet network.
- **Selective retransmission (ARQ).** When FEC can't cover a burst, the receiver notes which sequence numbers are missing and still within their playout deadline and asks the transmitter to resend *exactly those packets* from a short replay buffer. Wi-Fi round-trips are milliseconds, so a late packet usually still makes its deadline.
- **Jitter buffer + playback smoothing.** A receiver-side ring buffer absorbs arrival jitter, estimates network jitter (RFC 3550) to size itself, and applies packet-loss concealment plus gentle clock-drift compensation so gaps stay inaudible.
- **Generation counter.** Every reconfiguration (codec, sample rate, profile) bumps a generation number; mismatched-generation packets are dropped rather than played with the wrong decoder settings.
- **Silence suppression** keeps battery and airtime down during quiet passages without dropping the connection.

### Codecs and profiles

| Profile | Path | Use it for |
|---|---|---|
| Auto | Adaptive codec/format | Set-and-forget; the buffer sizes itself to the network |
| High-resolution | Lossless or raw PCM, up to 192 kHz / 24-bit | Music where quality matters and there's a good link |
| Low-latency | Opus (AAC fallback), 48 kHz | Games and video, where lip-sync matters |

The lossless path uses a small custom codec: reversible mid/side decorrelation + linear-predictive residuals + Golomb-Rice entropy coding, all in integer Kotlin (no native code). It is mathematically bit-exact to the source PCM and automatically falls back to raw PCM if a frame doesn't shrink.

### Multi-device and control

A transmitter keeps a registry of connected receivers and fans one encode out to each, patching that receiver's volume into every packet; receivers answer with periodic heartbeats that act as both keep-alive and capability report, and can push their own volume back. If a receiver drops off, the transmitter prunes it; the app also warns on-screen when a link goes quiet, so failures aren't silent.

### Security posture

Intended for a **trusted local network or a direct Wi-Fi Direct link between your own devices**. Traffic is **not encrypted or authenticated**, so anyone on the same Wi-Fi can, in principle, listen in or inject — that is a deliberate simplicity trade-off for a personal LAN tool, not an oversight. Do not use it on untrusted/public networks. No audio or metadata ever leaves the local network, and the update center only contacts GitHub to check for new releases.

## Getting started

### Install

1. Open [the releases page](https://github.com/KollTHOR/simple-audio-stream/releases) on your Android device.
2. Pick a stable or nightly release and download its `.apk`.
3. Open the download and follow Android's prompt (allow "install unknown apps" for your browser/file manager if asked; you can turn it back off afterward).
4. Install it on **both** devices.

> **Signing-key note:** moving from an older build may require uninstalling it first, which clears that device's saved settings. Later updates then install normally.

### Quick start — over Wi-Fi or a hotspot

1. Connect both devices to the same network.
2. On the receiver: choose **Receive** → **Start Listening**.
3. On the transmitter: choose **Broadcast** → **Scan** → tap the receiver. If it doesn't appear, use **Manual Connection** and type the receiver's IP.
4. Tap **Start Streaming** and accept Android's audio-sharing prompt.

### Quick start — with no router (Wi-Fi Direct)

1. On the receiver: choose **Receive**. With no Wi-Fi network it starts a Wi-Fi Direct group automatically.
2. Grant the nearby-Wi-Fi / location permissions Android asks for; some devices also need Location *enabled* for Wi-Fi Direct discovery.
3. On the transmitter: **Scan** → tap the receiver → accept the Wi-Fi Direct prompt.
4. Tap **Start Streaming** and accept the audio-sharing prompt.

Tap **Stop Streaming** / **Stop Listening** to end a session.

## Permissions

Permissions are requested when the feature that needs them is used; you don't need the optional ones to stream over ordinary Wi-Fi.

### Requested in-app

| Permission | When | Why |
|---|---|---|
| Microphone `RECORD_AUDIO` | Transmitting | Android requires it for playback capture; the app captures playing audio, not the room, and Android separately confirms audio sharing. |
| Notifications `POST_NOTIFICATIONS` (13+) | Streaming/listening | The ongoing foreground-service notification and its controls. |
| Nearby Wi-Fi `NEARBY_WIFI_DEVICES` (13+) | Wi-Fi Direct | Nearby Wi-Fi discovery/connection. |
| Location `ACCESS_FINE/COARSE_LOCATION` | Wi-Fi Direct discovery (and older Android Bluetooth) | Required by the OS for nearby-device APIs; used for discovery only, never put in the stream. |
| Bluetooth scan/advertise/connect (12+) | Optional BLE setup handshake | Exchanges Wi-Fi Direct details only. Older Android uses legacy Bluetooth + Location instead. |

### Optional toggles in Android Settings

| Access | Enables |
|---|---|
| Notification access | Sends track title/artist/artwork/playback state to the receiver and relays play/pause/skip. |
| Accessibility service | Optional hardware-volume-button control of receiver volume while transmitting (hardware keys only). |
| Install unknown apps | Lets the in-app updater open the installer directly; not needed for streaming or manual installs. |

> **Android 13+, sideloaded apps:** the system may hide the Notification-access toggle until you allow *restricted settings* for the app (Settings → Apps → Simple Audio Stream → ⋮ → Allow restricted settings), then enable Notification access. This is an OS safety step, not an in-app permission, and cannot be pre-granted via the manifest. The same can apply to Accessibility.

### Auto-granted normal permissions

`INTERNET` (LAN audio/control + GitHub update checks); `ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE`/`CHANGE_WIFI_MULTICAST_STATE` (network checks + discovery + Wi-Fi Direct); `WAKE_LOCK` (keep streaming with the screen off); the `FOREGROUND_SERVICE*` set (Android keeping capture/playback alive); `NFC` (optional bootstrap only).

## Building from source

Requires JDK 17 and the Android SDK (compile/target 35). No extra setup for a debug build:

```
./gradlew assembleDebug           # app/build/outputs/apk/debug/
./gradlew test                    # JVM unit tests
./gradlew lintVitalRelease
```

A signed release needs a keystore (see `.github/` for the CI signing step); a debug APK installs fine for testing.

## Updating

**Settings → App Updates** checks the stable or nightly channel and lists recent releases. Before installing, the updater verifies the APK checksum and that the signing certificate matches the installed app, then hands off to Android's installer. Manual installs from the releases page always work.

## Troubleshooting

- **Device doesn't appear** — confirm both are on the same network, grant the nearby-Wi-Fi/Bluetooth permissions for the method you're using, and re-scan; for Wi-Fi Direct make sure Location is enabled if the device requires it. Still nothing? Use manual IP entry.
- **Stutter / dropouts** — try a different profile (Low-latency on a busy 2.4 GHz network; a strong 5 GHz or Wi-Fi Direct link helps), and keep devices within range. The transport already recovers loss, but a saturated radio is the usual cause.
- **See what's happening** — **Settings → Diagnostics** and the in-app live log show discovery phases, packet loss/retransmissions, and connection state, so failures are visible instead of silent.
