# AirPlay Audio Receiver for Galaxy Ace 4

Turns a Samsung SM-G357FZ (Galaxy Ace 4 / Ace Style LTE, Android 4.4.4 / API 19)
into an AirPlay speaker over its 3.5mm headphone jack. See [docs/decisions.md](docs/decisions.md)
for the toolchain research and architecture decisions behind this setup.

## Status: reconnect handling + packet-loss recovery

Verified end-to-end against a real iPhone on 2026-08-12, including an actual
WiFi drop mid-playback: the RTSP connection dies without a clean TEARDOWN
(caught and cleaned up), the mDNS advertisement pauses, and once WiFi comes
back it re-advertises against the new IP automatically - no app restart
needed, no manual replay of the mDNS registration by the user. The speaker
was selectable and played again immediately after reconnecting.

Also implemented: RTP resend/retransmit for lost packets - gaps in the audio
sequence stream trigger a resend request to the iPhone's control port, and
the recovered packet plays as soon as it arrives. Verified on real hardware
mid-playback (logged real gaps being detected and recovered). NTP-style
clock sync was deliberately skipped - see decisions doc for why.

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
    *.kt                        # service (incl. WiFi reconnect handling), mDNS advertisement, UI
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
