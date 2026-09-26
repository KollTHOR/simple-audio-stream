# Simple Audio Stream

[**Download the latest stable release**](https://github.com/KollTHOR/simple-audio-stream/releases/latest) · [Browse all releases, including nightlies](https://github.com/KollTHOR/simple-audio-stream/releases)

Simple Audio Stream sends audio playing on one Android device to another device over your home Wi-Fi, a phone hotspot, or Wi-Fi Direct. Use one device as the **Transmitter** and the other as the **Receiver**.

## What it does

- Streams eligible system playback from Android 10 or later. Android shows a system screen/audio-sharing confirmation before capture starts; the app captures playback audio, not ambient microphone sound.
- Finds nearby receivers on the local network. You can also connect manually by IP address or use Wi-Fi Direct when there is no router or hotspot.
- Offers adaptive, music/reliable, and low-latency video profiles. Audio quality and sample-rate options depend on the devices and selected profile.
- Supports receiver volume control, an equalizer, saved connection profiles, and playback controls.
- Can show track information and album artwork on the receiver when optional Notification access is enabled.
- Includes an in-app update center for stable and nightly builds.

Bluetooth Low Energy is optional discovery only; Wi-Fi Direct is the router-free audio connection. NFC is an optional tap-to-bootstrap feature on supported devices and does not carry the audio stream.

## Install

1. Open [the releases page](https://github.com/KollTHOR/simple-audio-stream/releases) on your Android device.
2. Choose a stable release or nightly release, then download its `.apk` file.
3. Open the downloaded APK and follow Android's installation prompt. If Android asks, allow your browser or file manager to install unknown apps. You can turn that setting off again after installation.
4. Install the app on both Android devices.

**Signing-key transition:** Android may require users coming from an older build to uninstall it before installing the current release. Uninstalling removes that app installation and may erase its saved settings. Once installed on the current signing key, later updates should install normally.

## Quick start

### Over Wi-Fi or a hotspot

1. Connect both devices to the same Wi-Fi network or phone hotspot.
2. On the receiving device, select **Receive** and tap **Start Listening**.
3. On the transmitting device, select **Broadcast**, scan for the receiver, and select it. If it does not appear, use **Manual Connection** and enter the receiver's IP address.
4. Tap **Start Streaming** and approve Android's system audio-sharing prompt.

### With Wi-Fi Direct

1. On the receiving device, select **Receive**. If it is not connected to a local Wi-Fi network, the app starts a Wi-Fi Direct group automatically.
2. Grant the nearby Wi-Fi/location permissions Android requests. Some devices also require Location to be turned on in Android settings for Wi-Fi Direct discovery.
3. On the transmitting device, tap **Scan**. The receiver should appear in the device list; select it and accept Android's Wi-Fi Direct connection prompt.
4. Tap **Start Streaming** and approve Android's system audio-sharing prompt.

Tap **Stop Streaming** or **Stop Listening** to end a session.

## Permissions

Android requests permissions when you use the feature that needs them. You do not need to enable every optional feature to stream over a regular Wi-Fi network.

### Requested while using the app

| Permission | When it is needed | What it does |
|---|---|---|
| **Microphone** (`RECORD_AUDIO`) | When transmitting | Android requires this permission for playback capture. The app captures eligible audio playing on the device—not sound from the room. Android separately asks you to approve system audio sharing when you start a stream. |
| **Notifications** (`POST_NOTIFICATIONS`, Android 13+) | When starting a transmitter or receiver | Allows Android to show the ongoing streaming/playback notification and its controls. On earlier Android versions, the system notification appears without this runtime prompt. |
| **Nearby Wi-Fi devices** (`NEARBY_WIFI_DEVICES`, Android 13+) | When using Wi-Fi Direct | Allows Android's nearby Wi-Fi discovery and connection features. |
| **Location** (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | When discovering with Wi-Fi Direct; also used for Bluetooth discovery on older Android versions | Android requires location permission for some nearby-device discovery APIs. The app uses it for discovery, not to include location in the audio stream. Some Android devices also require the system Location setting to be on for Wi-Fi Direct discovery. |
| **Bluetooth scan, advertise, and connect** (Android 12+) | When using Bluetooth Low Energy discovery | Lets the app find and announce nearby Simple Audio Stream devices. On Android 11 and earlier, the app uses the older Bluetooth permissions and Location permission instead. Bluetooth is for discovery, not audio transport. |

### Optional access in Android Settings

| Setting | What it enables |
|---|---|
| **Notification access** | Reads active media-session information so the app can send track title, artist, album artwork, and playback state to the other device. It also allows play/pause/next/previous commands to be relayed. The app's listener uses the media-session feature. |
| **Accessibility service** | Optional hardware-volume-button control while transmitting. Volume Up/Down adjusts the remote receiver volume. The service filters hardware key presses only; it does not inspect screen content or accessibility events. |
| **Install unknown apps** | Optional permission for the in-app updater to open the Android installer directly. It is not needed for audio streaming or for downloading an APK to install manually. |

**Android 13+ and sideloaded APKs:** Android may block the Notification access switch until you allow restricted settings for the app. Open **Settings → Apps → Audio Streamer → ⋮ → Allow restricted settings**, confirm the prompt, then return to **Settings → Special app access → Notification access → Audio Streamer** and enable access. Menu names can differ by device. This is an Android security confirmation, not an app permission; it cannot be enabled or requested by adding a manifest entry. Android may apply the same restriction to Accessibility access.

### Other permissions Android grants for app features

These permissions do not normally show a runtime prompt:

| Permission | What it does |
|---|---|
| `INTERNET` | Sends and receives audio/control packets on the local network and connects to GitHub for the update center. |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Checks network availability and Wi-Fi state so devices can discover and connect to each other. |
| `CHANGE_WIFI_STATE` | Supports Wi-Fi Direct connection setup. |
| `CHANGE_WIFI_MULTICAST_STATE` | Lets the receiver listen for local-network discovery broadcasts. |
| `WAKE_LOCK` | Keeps the CPU awake during an active stream, including with the screen off. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Let Android keep audio capture and playback running as visible foreground services. |
| `NFC` | Supports optional NFC tap-to-bootstrap on compatible devices. NFC is not used to transmit audio. |

## Updating

Use **Settings → App Updates** to check the stable or nightly channel and browse releases. The updater checks the APK checksum and verifies that its signing certificate matches the installed app before opening Android's installer. You can also install an APK manually from the [releases page](https://github.com/KollTHOR/simple-audio-stream/releases).

## Help

If devices do not appear, confirm they are on the same Wi-Fi/hotspot, allow the relevant nearby Wi-Fi or Bluetooth permissions for the connection method you chose, and retry the scan. For Wi-Fi Direct, also check that Location is enabled if your Android device requires it.
