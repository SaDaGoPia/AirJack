# Decisions log

## RAOP implementation approach
Original decision (milestone 1 planning): port shairport-sync via NDK + JNI
(not a pure-Kotlin RAOP stack, not an existing Android AirPlay-receiver
library).

- Checked `lujnan/shairport-sync` (the one fork claiming "Support For Android"
  on GitHub) — its README has no Android content beyond a stray `Android.mk` /
  `droid_conf.mk`, no JNI layer, no app, no maintenance signal. Not usable as-is.
- No other actively-maintained Android AirPlay-receiver library was found.
- shairport-sync (mikebrady/shairport-sync) remains the reference implementation
  to read/port from for protocol details.

**Revised for milestone 2**: the RTSP handshake and RSA/AES key exchange are
pure Kotlin (`app/src/main/java/com/ace4/airplayreceiver/raop/`), no NDK/JNI.
This scope (milestone 2 is specifically "RTSP handshake completes, encrypted
session established," not audio decode) doesn't need C at all — `javax.crypto`
handles RSA-OAEP/AES-CBC natively, and RTSP is a small text protocol easy to
parse directly. Cross-compiling shairport-sync's autotools build just for this
would have meant fighting a WSL + NDK toolchain for logic that's actually
simpler in Kotlin. Native code is still expected for milestone 3's ALAC
decoder specifically (a small, self-contained piece, not the whole
shairport-sync daemon) — that's the part that actually benefits from C.

The well-known RAOP RSA private key (`RaopCrypto.kt`) was copied verbatim from
shairport-sync's `common.c` (`super_secret_key`) rather than reconstructed
from memory, since a single wrong bit silently breaks all crypto. It's the
same fixed 1024-bit keypair every RAOP receiver has shared for 15+ years —
every AirPlay sender encrypts the session key against its matching public
half, so there's no receiver-side alternative.

## mDNS / TXT records: JmDNS, not NsdManager
Android's built-in `NsdManager`/`NsdServiceInfo.setAttribute()` (needed to set
DNS-SD TXT records, which `_raop._tcp`/`_airplay._tcp` require for iOS to
recognize the service) was only added in **API 21**. Our target device is
API 19, so the platform API can't be used. Using JmDNS (pure Java, mature)
instead — see `app/src/main/java/com/ace4/airplayreceiver/RaopAdvertiser.kt`.

## targetSdk pinned to 19, not a modern value
Considered targeting a modern SDK (e.g. 34) while keeping `minSdk 19`, which is
the more common pattern. Rejected for this project: this app has exactly one
target device, permanently on API 19. Targeting a modern SDK would pull in
API 26+ notification channel requirements, API 29+ foreground service type
requirements, and API 33+ runtime notification permission — none of which the
real device will ever exercise, and all of which add code paths that can only
be tested on an emulator, never on the actual hardware. `targetSdk 19` keeps
legacy platform behavior active, which is what's actually installed.

## Toolchain versions pinned (as of 2026-08-12)
- **AGP 8.7.3 / Gradle 8.13 / Kotlin 2.0.21** — current stable AGP 8.x line;
  Gradle 8.13 was already cached locally (Android Studio) so no wrapper
  distribution needed downloading.
- **compileSdk 34** — platform 34 + build-tools 34.0.0 installed locally via
  `sdkmanager` for this project (previously only platforms 18 and 36 were
  present).
- **minSdk 19 is still buildable** with current AGP: no AGP-side floor exists.
  The real constraints are (a) Google Play requires minSdk ≥ 21 to publish —
  irrelevant here, this app is sideloaded — and (b) AndroidX libraries that
  assume minSdk 21+ (e.g. Jetpack Compose). Avoided entirely by not using
  AndroidX/AppCompat — `MainActivity` extends plain `android.app.Activity`
  with `@android:style/Theme.Holo.Light`, which is available since API 11.
- **NDK: r25c is the one to use when milestone 2 starts.** NDK r25 and earlier
  support API 19 (KitKat). **r26 dropped KitKat support** (floor raised to
  API 21) and this carries forward to later releases, including r27, which is
  what's currently installed locally (`27.0.12077973`) — it will not compile
  native code against `minSdk 19`. Will need
  `sdkmanager "ndk;25.2.9519653"` before wiring up the JNI module.

## Bug: advertising `_airplay._tcp` broke the handshake (found 2026-08-12)
First milestone-2 test against a real iPhone: the RTSP server never received
an ANNOUNCE. Logs showed the iPhone repeatedly calling `GET /info`,
`POST /pair-setup`, `POST /pair-verify` (HomeKit-style AirPlay 2 pairing
endpoints), then retrying `OPTIONS` in a loop, forever, across many
reconnects — it never once attempted the classic RTSP flow.

Root cause: `RaopAdvertiser` was registering a second mDNS service,
`_airplay._tcp`, alongside `_raop._tcp` (with `features`/`deviceid`/`flags`
TXT keys). Checked shairport-sync's `bonjour_strings.c`/`rtsp.c`: it only
builds and registers that second service (`secondary_txt_records`, `t2`) when
compiled with `CONFIG_AIRPLAY_2` *and* `config.service_type == APST_airplay2`.
A classic/legacy-only build never registers `_airplay._tcp` at all — only
`_raop._tcp`. Advertising `_airplay._tcp` from a receiver that doesn't
implement AirPlay 2 pairing told iOS this speaker supports HomeKit-style
pairing, and iOS got stuck trying to complete that pairing instead of falling
back to classic RAOP.

Fix: removed the `_airplay._tcp` registration entirely; `RaopAdvertiser` now
advertises only `_raop._tcp`, with TXT keys matching shairport-sync's classic
(`else` branch, non-AirPlay2) defaults exactly (`txtvers`, `ch`, `cn`, `ek`,
`et`, `md`, `pw`, `sr`, `ss`, `sv`, `da`, `tp=TCP,UDP`, `vn`, `vs`, `fv`, `am`,
`sf`). Retested immediately after: full OPTIONS → ANNOUNCE → SETUP → RECORD
handshake completed, AES key derived, ~4100 RTP packets received over one
real playback session.

**How to apply:** if AirPlay 2 support is ever considered later (out of scope
per the original project brief — audio-only legacy AirPlay is the target),
re-adding `_airplay._tcp` requires implementing the actual HAP pairing
protocol (Ed25519/Curve25519 SRP, `/pair-setup`, `/pair-verify`) to back it —
don't advertise the service without the protocol behind it.

## Build environment used for this scaffold
- JDK: Android Studio's bundled JBR (`Android Studio/jbr`, OpenJDK 21.0.8) —
  used explicitly via `JAVA_HOME`, since the system `java` on PATH resolves to
  a stray JDK 23 install with no matching `JAVA_HOME` set.
- Verified `assembleDebug` succeeds end-to-end on this machine (see
  `app/build/outputs/apk/debug/app-debug.apk`).
- `local.properties`: `sdk.dir` must use forward slashes. A first attempt with
  Windows-style backslashes (`C:\Users\...`) broke the build — Java `.properties`
  file parsing treats `\` as an escape character, not a path separator.
