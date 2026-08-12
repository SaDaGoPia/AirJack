# AirPlay Audio Receiver for Galaxy Ace 4

Turns a Samsung SM-G357FZ (Galaxy Ace 4 / Ace Style LTE, Android 4.4.4 / API 19)
into an AirPlay speaker over its 3.5mm headphone jack. See [docs/decisions.md](docs/decisions.md)
for the toolchain research and architecture decisions behind this setup.

## Status: Milestone 3 — audio plays

Verified end-to-end against a real iPhone on 2026-08-12: select the speaker in
Control Center, hit play, and audio comes out of the Galaxy Ace 4's headphone
jack. The full pipeline is live - mDNS discovery, RTSP/RSA/AES handshake,
per-packet AES-CBC decrypt, ALAC decode, `AudioTrack` playback - all pure
Kotlin/Java, no native/NDK code anywhere in the app.

Not yet implemented: NTP timing sync and retransmit ("resend") request
handling, so playback can glitch or drift under packet loss or a rough WiFi
link - fine on a solid local connection, not yet robust. That, plus a
foreground-service reconnect story, is milestone 4.

## Build

Requires:
- JDK 17+ (Android Studio's bundled JBR works: `Android Studio/jbr`)
- Android SDK with platform 34 + build-tools 34.0.0 installed

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Install & run

1. Enable "Unknown sources" on the Galaxy Ace 4 (Settings → Security).
2. Copy `app-debug.apk` to the device and install it, or:
   `adb install app/build/outputs/apk/debug/app-debug.apk`
3. Connect the phone to the same WiFi network as your iPhone, plug headphones
   or a speaker into its 3.5mm jack.
4. Open the app, optionally rename the speaker, tap Start.
5. On the iPhone, open Control Center → AirPlay, select the speaker, and
   start playing audio - it should come out of the jack. To watch what's
   happening: `adb logcat -s RaopRtspServer` shows the handshake
   (`ANNOUNCE: encrypted session established`) followed by a stream of
   `Audio RTP packet #N` lines as audio decodes and plays.

## Project layout

```
/app                            # Android app module (Kotlin)
  /src/main/java/com/ace4/airplayreceiver
    *.kt                        # service, mDNS advertisement, UI
    /raop                       # RTSP server, RSA/AES crypto, ALAC decoder wrapper
  /src/main/java/com/beatofthedrum/alacdecoder
    *.java                      # vendored pure-Java ALAC decoder (third-party, BSD-style license)
/docs                            # protocol notes, decisions, test logs
```

## Toolchain pins

- AGP 8.7.3, Gradle 8.13, Kotlin 2.0.21
- `compileSdk 34`, `minSdk 19`, `targetSdk 19` (targetSdk intentionally pinned to
  match the real device — see decisions doc for why)
- No NDK. Both the RTSP/crypto handshake (milestone 2) and ALAC decode
  (milestone 3) turned out not to need it — `javax.crypto` handles RSA/AES,
  and a vendored pure-Java ALAC decoder (`com.beatofthedrum.alacdecoder`,
  ported from soiaf/Java-Apple-Lossless-decoder) handles decode, fast enough
  in real-time on the actual Snapdragon 410 hardware. See decisions doc for
  the full reasoning and the one bug hit while wiring it up.
