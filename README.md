# AirPlay Audio Receiver for Galaxy Ace 4

Turns a Samsung SM-G357FZ (Galaxy Ace 4 / Ace Style LTE, Android 4.4.4 / API 19)
into an AirPlay speaker over its 3.5mm headphone jack. See [docs/decisions.md](docs/decisions.md)
for the toolchain research and architecture decisions behind this setup.

## Status: Milestone 1 — mDNS advertisement

The app advertises `_raop._tcp` and `_airplay._tcp` services via mDNS so the
device shows up in iOS's AirPlay picker. **No RTSP/RTP server exists yet** —
selecting the speaker on the iPhone will not yet play audio. That's milestone 2.

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
5. On the iPhone, open Control Center → AirPlay — the device name should
   appear in the list (it won't play audio successfully yet).

## Project layout

```
/app                # Android app module (Kotlin)
  /src/main/java     # service, mDNS advertisement, AudioTrack playback (later), UI
/native              # cross-compiled shairport-sync subset (added in milestone 2)
/docs                # protocol notes, decisions, test logs
```

## Toolchain pins

- AGP 8.7.3, Gradle 8.13, Kotlin 2.0.21
- `compileSdk 34`, `minSdk 19`, `targetSdk 19` (targetSdk intentionally pinned to
  match the real device — see decisions doc for why)
- NDK: not wired in yet. When milestone 2 (shairport-sync JNI port) starts, pin
  **NDK r25c** — r26+ dropped API 19 support. r27 (currently installed locally)
  will not compile native code against `minSdk 19`.
