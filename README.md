# AirPlay Audio Receiver for Galaxy Ace 4

Turns a Samsung SM-G357FZ (Galaxy Ace 4 / Ace Style LTE, Android 4.4.4 / API 19)
into an AirPlay speaker over its 3.5mm headphone jack. See [docs/decisions.md](docs/decisions.md)
for the toolchain research and architecture decisions behind this setup.

## Status: volume sync (one direction) + Now Playing metadata/artwork + status-dial UI

Verified end-to-end against a real iPhone on 2026-08-12: the notification
shows the current track's title/artist/album and cover art, and the
iPhone's own AirPlay volume slider/buttons now control the Ace4's actual
output volume, with the Ace4's on-screen "media volume" indicator staying
in sync too. The Now Playing view is dark-themed and minimal (no title bar),
built around one large circular status dial that's color-coded per state
(teal = advertising, amber = reconnecting, red = error) instead of a plain
system button, and the app/notification icons are a small generated glyph
reused throughout, including as the idle artwork placeholder. Reopening the
app while the service is still running now shows the correct state
immediately, the keyboard no longer opens automatically on launch, and the
speaker-name field locks (with an explanatory label) while advertising.

Not working, and not expected to start working without a change on Apple's
side: pushing the Ace4's volume-button presses back to the iPhone (DACP
requests are consistently rejected with 400 Bad Request - looks like a real
iOS-side restriction, not a bug here), and transport/volume controls in the
notification or lock screen (tried two different Android APIs for this;
neither rendered on this Samsung TouchWiz build). Both attempts were
reverted rather than left half-working - see decisions doc for the full
investigation.

Also done: WiFi drop/reconnect handling (mDNS re-advertises automatically
once WiFi returns, dead connections get cleaned up on a timeout) and RTP
resend/retransmit for lost packets (gaps in the audio sequence trigger a
recovery request to the iPhone). Both verified on real hardware. NTP-style
clock sync was deliberately skipped in favor of resend/retransmit - see
decisions doc for why.

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
