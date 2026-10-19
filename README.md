# Simple Audio Stream

Play the sound from one Android device through another one's speakers — over your own Wi-Fi, with no computer, no account, and nothing sent to the cloud.

**Download the latest release →** [github.com/KollTHOR/simple-audio-stream/releases/latest](https://github.com/KollTHOR/simple-audio-stream/releases/latest)

---

## What it does

Android can't send the audio one app is playing to a *different* Android device's speakers. Bluetooth pairs a phone to a headset, not a phone to a phone. Casting needs a Chromecast or a computer.

Simple Audio Stream fills that gap. Install it on two devices, pick a role on each, and the audio playing on one comes out of the other:

- **Broadcast** — the device that's playing the audio (a phone or tablet).
- **Receive** — the device with the speakers (another phone, a tablet, or an old handset plugged into a hi-fi).

Useful for:

- Hearing a movie or game from your phone through a tablet across the room.
- Turning an old phone into a network speaker for a music app running elsewhere.
- Sending audio to a device with better speakers than the one playing it.

You can also send to **several receivers at once**, each with its own volume.

Everything stays on your local network or a direct device-to-device link. No sign-in, no server, no cloud.

## Requirements

- **Two** Android devices running **Android 10 or newer**.
- Both on the **same Wi-Fi network** — or, if you have no router, a direct **Wi-Fi Direct** link between them.
- The app installed on both.

## How to use

### On the receiving device

1. Open the app and choose **Receive**.
2. Tap **Start Listening**.
3. The first time, allow the permissions it asks for (notifications, and nearby-device access for discovery).

### On the broadcasting device

1. Open the app and choose **Broadcast**.
2. Tap **Scan** and pick the other device from the list.
   - If it doesn't appear, tap **Manual Connection** and type the receiver's IP address (shown on the receiver).
3. Tap **Start Streaming** and accept Android's audio-sharing prompt.

That's it — the sound now comes out of the receiver. Tap **Stop Streaming** / **Stop Listening** on either device to end it.

### If you have no Wi-Fi router

1. On the receiver, choose **Receive**. With no network it starts a direct (Wi-Fi Direct) group by itself.
2. Grant the nearby-device permissions when asked; some devices also need **Location** switched on for Wi-Fi Direct discovery.
3. On the broadcaster, tap **Scan**, pick the receiver, and accept the Wi-Fi Direct prompt.

## Quality profiles

| Profile | Use it for |
|---|---|
| **Auto** | Set-and-forget; adapts to the network. Start here. |
| **High quality** | Music, on a good connection — up to 192 kHz / 24-bit. |
| **Low latency** | Games and video, where lip-sync matters. |

If audio stutters, try **Low latency** (especially on a busy 2.4 GHz network) or move the devices closer. If you want the best quality and the link is solid, try **High quality**.

## Limitations

- **Protected (DRM) audio can't be captured.** Apps like Netflix, Disney+ and Spotify block system-audio capture, so the receiver stays silent while they play. This is an Android restriction, not something the app can work around.
- **Some apps opt out of capture.** A few apps declare their audio as non-recordable; those also play silently on the receiver.
- **No encryption.** It's built for your own home network or a direct link between your devices. Don't use it on public or shared Wi-Fi.
- **Not zero-latency.** Perfectly fine for music and casual video; competitive gaming may notice the delay.
- **Battery use.** The broadcasting device captures and encodes continuously, so it uses noticeably more battery while streaming.
- **Devices differ.** Behaviour can vary between manufacturers, especially around battery optimisation and Wi-Fi Direct.

## Permissions

Permissions are only requested when the feature that needs them is used. Streaming over ordinary Wi-Fi needs just notifications and (on the broadcaster) the audio-capture prompt.

| Permission | When | Why |
|---|---|---|
| Microphone (`RECORD_AUDIO`) | Broadcasting | Android requires it for system-audio capture. The app captures what's *playing*, not the room, and Android shows its own audio-sharing prompt. |
| Notifications (`POST_NOTIFICATIONS`, Android 13+) | Streaming / listening | The ongoing notification and its controls. |
| Nearby Wi-Fi (`NEARBY_WIFI_DEVICES`, Android 13+) | Wi-Fi Direct | Discovering and connecting to the other device. |
| Location (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`) | Wi-Fi Direct discovery (and Bluetooth on older Android) | Required by Android for nearby-device APIs. Used for discovery only, never sent anywhere. |
| Bluetooth (Android 12+) | Optional setup handshake | Exchanges Wi-Fi Direct details only. Never carries audio. |

**Optional, off by default** (enable in Android Settings if you want them):

| Access | Enables |
|---|---|
| Notification access | Shows the track/artist/artwork on the receiver and relays play/pause/skip back. |
| Accessibility service | Control the receiver's volume with your hardware volume keys while broadcasting. |
| Install unknown apps | Lets the in-app updater open the installer directly. Not needed for normal use. |

> On Android 13+, sideloaded apps may hide the Notification-access toggle until you allow **restricted settings** for the app (Settings → Apps → Simple Audio Stream → ⋮ → *Allow restricted settings*).

## If something doesn't work

- **The other device doesn't appear** — check both are on the same network, grant the nearby-device permissions, and re-scan. For Wi-Fi Direct, make sure Location is enabled if your device requires it. Still nothing? Use **Manual Connection** with the receiver's IP.
- **Audio is silent** — the app you're playing is almost certainly protected (DRM) or has opted out of capture. Try a different app (e.g. a local music player) to confirm.
- **Stutter or dropouts** — switch to the **Low latency** profile, move the devices closer, or use a 5 GHz network.
- **See what's happening** — open **Settings → Diagnostics** for connection state and a live log. If you're reporting a bug, use **Share logs** there to send a report.

## Updating

The app updates itself: **Settings → App Updates** checks for new releases, verifies them, and hands off to Android's installer. Manual installs from the [releases page](https://github.com/KollTHOR/simple-audio-stream/releases) always work too.

## Privacy

No accounts, no analytics, no servers. Audio never leaves your local network. The only network request the app makes on its own is checking GitHub for a new release.

## Building from source

Requires JDK 17 and the Android SDK (compile/target 35).

```
./gradlew assembleDebug     # app/build/outputs/apk/debug/
./gradlew test              # JVM unit tests
```

A signed release needs a keystore; see `keystore.properties.example` for the expected values. A debug APK is fine for testing.
