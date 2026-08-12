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

**Revised again for milestone 3**: rather than reaching for NDK/JNI for ALAC
decode as originally planned, found and vendored an existing pure-Java ALAC
decoder — `soiaf/Java-Apple-Lossless-decoder` (BSD-style license, same
decode lineage as shairport-sync's own C decoder) — under
`app/src/main/java/com/beatofthedrum/alacdecoder/`. Only 4 of its ~18 files
are actually needed (`AlacFile`, `AlacDecodeUtils`, `LeadingZeros`,
`Defines`); the rest is MP4/QuickTime container demuxing machinery that's
irrelevant here, since RAOP delivers raw ALAC frames over RTP with the codec
config coming from the SDP `fmtp` line, not a container. `AlacFile` and
`AlacDecodeUtils` had their default (package-private) class visibility
widened to `public` so `com.ace4.airplayreceiver.raop.AlacDecoder` (the
Kotlin wrapper) can call into them from a different package - the only
source change made to the vendored files themselves. Tested end-to-end on
the real Galaxy Ace 4: real-time decode keeps up fine on the Snapdragon 410,
no native code needed anywhere in the app.

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
- **NDK ended up unused entirely** — both the milestone-2 RTSP/crypto
  handshake and milestone-3 ALAC decode turned out to have pure-JVM options
  that worked (`javax.crypto`, a vendored pure-Java ALAC decoder). Left here
  for the record in case that changes later: NDK r25 and earlier support
  API 19 (KitKat); **r26 dropped KitKat support** (floor raised to API 21),
  carried forward to later releases including r27, which is what's currently
  installed locally (`27.0.12077973`) — it will not compile native code
  against `minSdk 19`. Would need `sdkmanager "ndk;25.2.9519653"` first.

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

## Bug: ALAC decoder crashed on every single frame (found 2026-08-12)
First milestone-3 test against a real iPhone: handshake and RTP packet flow
looked fine in the logs, but no sound came out. Full logcat capture showed
`AlacDecodeUtils.decode_frame()` throwing `ArrayIndexOutOfBoundsException`
inside `readbits_16()` on every single packet (223 times over one ~10s
session) — meaning zero frames ever decoded successfully.

Root cause: `readbits_16()` always speculatively reads 3 bytes ahead of the
current bit position (`ibIdx`, `ibIdx+1`, `ibIdx+2`), even when fewer bits
are actually needed, then shifts out and discards the unused ones — a
standard bit-reader performance trick. Near the end of the input buffer this
overreads past the real frame data. The original C decoder this was ported
from (and the Java port in its normal file-decoding use) always got buffers
with slack past the logical frame end, so the overread silently touched
harmless adjacent memory. Our buffer is exactly RTP-payload-sized with zero
slack, so the same overread hit Java's array bounds check instead.

Fix: `AlacDecoder.decode()` (`app/src/main/java/com/ace4/airplayreceiver/raop/AlacDecoder.kt`)
pads the frame buffer with 8 trailing zero bytes before handing it to
`decode_frame()`. The padding is never part of real decoded output — the
extra bits the speculative read grabs are always shifted out and discarded —
so this is a correct fix, not a workaround that risks corrupting audio.
Retested immediately after: zero exceptions across a full ~36s / ~4500-packet
session, audio audibly playing through the headphone jack.

**How to apply:** if the vendored decoder is ever updated/re-vendored from
upstream, keep this padding in place — it's a property of how the bit reader
works, not something a newer upstream version is likely to have fixed (it's
correct behavior in the file-decoding context the library was built for).

## Milestone 4 scope: reconnect handling only, not timing sync/resend
Milestone 3 left two separate robustness gaps: (a) no handling for WiFi
drops/iPhone disconnects — the original milestone 4 per the project brief,
and (b) no NTP timing sync or RTP retransmit ("resend") handling, so
playback can glitch under packet loss even on a stable link. Decided to
scope milestone 4 to (a) only and leave (b) for a later pass — they're
fairly orthogonal (one is "recover when connectivity comes back," the other
is "handle loss while connectivity is fine"), and combining them risked a
harder-to-test, harder-to-review change.

Two failure modes handled:
1. **WiFi drop/reconnect** (`AirplayAdvertiseService.kt`): a `BroadcastReceiver`
   for the legacy `android.net.conn.CONNECTIVITY_CHANGE` action (not
   `ConnectivityManager.NetworkCallback`, which needs API 21+) is registered
   while advertising. On a network change it compares the phone's current
   WiFi IP against the one last handed to JmDNS. If WiFi dropped entirely,
   the advertiser is torn down (a JmDNS instance bound to a dead address
   can't recover on its own) and status goes to a new `STATUS_RECONNECTING`.
   If the IP changed (reconnected, possibly to a different address), the
   advertiser restarts against the new address automatically. The RTSP
   `ServerSocket` itself needs no restart — it's bound to the wildcard
   address, not a specific IP, so it keeps accepting connections through an
   IP change without help.
2. **Dead RTSP connections**: added a 30s `SO_TIMEOUT` on the accepted RTSP
   socket. Real sessions send RTSP traffic (OPTIONS/SET_PARAMETER keepalives)
   every few seconds during playback per observed logs, so 30s of total
   silence reliably means the client is gone (WiFi loss, app killed, out of
   range) rather than just idle. Without this, a socket read blocks forever
   with no way to detect the client vanished without a clean TEARDOWN.

Also switched `onStartCommand` from `START_NOT_STICKY` to `START_STICKY`:
this is a 1GB RAM device where the foreground service is a real candidate
for being killed under memory pressure. `START_STICKY` only redelivers a
restart Intent after the system kills a still-wanted service — Android
tracks explicit `stopSelf()`/`stopService()` separately and won't restart
after those, so this doesn't fight the user's own Stop button.

**Verified on real hardware 2026-08-12**, and the test caught both failure
modes in one natural sequence without needing to force either separately:
two sessions played and ended with clean `TEARDOWN`, then a third was cut
off mid-stream by an actual WiFi drop (`Connection ended: recvfrom failed:
ETIMEDOUT` — no TEARDOWN at all), which the existing generic `IOException`
catch/cleanup handled with no crash or leaked `AudioTrack`/UDP sockets. The
WiFi-drop detector fired independently ~0.5s later, paused advertising, and
re-advertised cleanly ~5s after WiFi came back. The speaker was selectable
and played again immediately.

**Tooling note**: `adb shell svc wifi disable` does not behave as a clean
WiFi toggle on this device's Samsung KitKat build — it triggered a large,
slow `dumpstate`-style output instead and left WiFi still enabled per
`dumpsys wifi`. Don't rely on it for testing on this hardware; toggle WiFi
manually from Settings instead.

## Build environment used for this scaffold
- JDK: Android Studio's bundled JBR (`Android Studio/jbr`, OpenJDK 21.0.8) —
  used explicitly via `JAVA_HOME`, since the system `java` on PATH resolves to
  a stray JDK 23 install with no matching `JAVA_HOME` set.
- Verified `assembleDebug` succeeds end-to-end on this machine (see
  `app/build/outputs/apk/debug/app-debug.apk`).
- `local.properties`: `sdk.dir` must use forward slashes. A first attempt with
  Windows-style backslashes (`C:\Users\...`) broke the build — Java `.properties`
  file parsing treats `\` as an escape character, not a path separator.
