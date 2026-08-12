# AirPlay Audio Receiver for Galaxy Ace 4

Turns a Samsung SM-G357FZ (Galaxy Ace 4 / Ace Style LTE, Android 4.4.4 / API 19)
into an AirPlay speaker over its 3.5mm headphone jack. See [docs/decisions.md](docs/decisions.md)
for the toolchain research and architecture decisions behind this setup.

## Status: Milestone 2 — RTSP handshake / encrypted session

Verified end-to-end against a real iPhone on 2026-08-12: the device advertises
`_raop._tcp` via mDNS, the iPhone selects it, and the app completes the full
legacy RAOP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD), derives the AES
session key from the RSA-OAEP-wrapped key in the ANNOUNCE SDP, and receives a
continuous stream of encrypted RTP audio packets for the duration of playback.

**No audio plays yet** — RTP packets are received and counted, not decrypted
or decoded. That's milestone 3 (ALAC decode + `AudioTrack` playback).

Implementation is pure Kotlin (`app/src/main/java/com/ace4/airplayreceiver/raop/`)
— no native/NDK code. See decisions doc for why that's a deliberate change from
the original "port shairport-sync via JNI" plan for milestone 1.

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
3. Connect the phone to the same WiFi network as your iPhone.
4. Open the app, optionally rename the speaker, tap Start.
5. On the iPhone, open Control Center → AirPlay, select the speaker, and
   start playing audio. No sound will come out yet (milestone 3), but you can
   confirm the handshake is working via `adb logcat -s RaopRtspServer` — look
   for "ANNOUNCE: encrypted session established" followed by a stream of
   "Audio RTP packet #N" lines.

## Project layout

```
/app                            # Android app module (Kotlin)
  /src/main/java/com/ace4/airplayreceiver
    *.kt                        # service, mDNS advertisement, UI
    /raop                       # RTSP server, RSA/AES crypto, wire protocol parsing
/docs                            # protocol notes, decisions, test logs
```

## Toolchain pins

- AGP 8.7.3, Gradle 8.13, Kotlin 2.0.21
- `compileSdk 34`, `minSdk 19`, `targetSdk 19` (targetSdk intentionally pinned to
  match the real device — see decisions doc for why)
- NDK: not wired in. Milestone 2's RTSP/crypto work turned out not to need it
  (see decisions doc) — pure Kotlin + `javax.crypto` handles RSA/AES fine.
  Native code is now expected to resurface in milestone 3 for ALAC decoding
  specifically; when that starts, pin **NDK r25c** — r26+ dropped API 19
  support, and r27 (currently installed locally) will not compile native code
  against `minSdk 19`.
