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

## Resend/retransmit instead of full NTP timing sync
Followed up on milestone 4's deferred item (b) above. Asked to "attend the
NTP timing sync," but researched the actual protocol first: timing sync is
*receiver-initiated* (we'd send periodic requests to the iPhone's timing
port and process its replies) and its real purpose in shairport-sync is
long-session clock-drift correction between sender and receiver via a
statistical rolling-window gradient estimate — not something our playback
depends on, since `AudioTrack` already paces output from the local device
clock once PCM is written to it. Milestones 2-4 all played correctly with
zero timing-sync code. Given the large, hard-to-verify port this would be
for a benefit that's likely inaudible on a single personal headphone-jack
receiver, decided with the user to implement RTP resend/retransmit instead —
smaller, more testable, and directly reduces audible dropouts from real WiFi
packet loss, which timing sync wouldn't address anyway.

Implementation (`RaopRtspServer.kt`): tracks the expected next sequence
number for regular audio packets (RTP payload type 0x60); on a forward gap
of 1-32 packets, sends an 8-byte resend-request packet (format taken from
shairport-sync's `rtp_request_resend`) via UDP to the client's control port
(captured from `SETUP`'s `Transport: control_port=` field, which was parsed
but previously discarded). Gaps larger than 32 are treated as a stream
restart/reorder rather than loss, and not chased. Resent packets arrive back
on the *audio* socket (not control) as payload type 0x56, wrapped in an
extra 4-byte prefix before the usual 12-byte RTP header, and are decoded and
played as soon as they arrive - there's no reorder/jitter buffer, so a very
late resend can play slightly out of order. That's judged a minor, rare
artifact next to the alternative (a permanent silent gap for every lost
packet), consistent with this project's playback pipeline being "decode
whatever arrives, in arrival order" rather than a proper timestamp-scheduled
buffer.

**Verified on real hardware 2026-08-12** during otherwise-normal playback:
logs showed real gaps being detected and resend requests sent (e.g.
"Requested resend of 2 packet(s) from seqno 33701") with no errors, audio
continuing to play normally through the recovered gaps.

## Now Playing metadata + artwork notification
Continuation of the same request, staged with the user into: metadata/UI
first (this section), then a DACP remote-control client for real transport
controls, then DACP volume sync last (the most protocol-uncertain piece) -
DACP research (see below) showed real complexity worth de-risking
incrementally rather than building all four stages at once.

iOS pushes track metadata via `SET_PARAMETER`, distinguished by
`Content-Type`: `application/x-dmap-tagged` (title/artist/album, DMAP-tagged
binary) or an image type (`image/jpeg`/`image/png`, raw artwork bytes) -
previously ignored entirely (200 OK, body never read). Checked
shairport-sync's `handle_set_parameter_metadata`: despite DMAP/DAAP
supporting arbitrary nested containers, RAOP only ever sends one flat
(4-byte tag, 4-byte length, value) sequence after an 8-byte header, so
`DmapParser.kt` mirrors exactly that rather than a full recursive DMAP
parser - just extracts `minm`/`asar`/`asal` (title/artist/album). Artwork is
decoded directly via `BitmapFactory.decodeByteArray`, no parsing needed.
Results are pushed up through a callback (`RaopRtspServer` constructor ->
`AirplayAdvertiseService`) that rebuilds the foreground notification with
`setContentTitle`/`setContentText`/`setLargeIcon`. No `Notification.MediaStyle`
(API 21+, and this project avoids AndroidX/support libraries) - a plain
`Notification.Builder` with a large-icon artwork is a reasonable substitute
for a receive-only speaker showing "what's playing," informational only
(no transport buttons yet).

**Bug found during testing**: first real-device test (YT Music as source)
showed title/artist/album parsing correctly, but no artwork ever appeared in
the notification - despite logs proving the artwork *did* decode
successfully (`Artwork decoded: 512x512`, from a 180KB `image/jpeg` body).
Root cause: the code assumed text metadata always arrives before artwork per
track, and unconditionally wiped `artwork` back to `null` whenever a new
text-metadata block arrived, "to avoid showing a stale cover." In this real
session, YT Music sent the image *before* the text metadata (opposite order
from that assumption), so the freshly-decoded artwork got discarded 170ms
after being set, by our own code, every time. Fixed by preserving existing
artwork across text-metadata updates instead of clearing it - accepting the
minor tradeoff that a brand new track's text can very briefly show the
previous track's cover until its own artwork arrives, which is far less bad
than guaranteed-never-showing artwork at all. Retested and confirmed working.

**DACP research (for the next two stages)**: real remote-control commands
(play/pause/next/prev, and especially volume) go over HTTP to a service the
iPhone exposes, discovered via mDNS-*browsing* (not advertising) for
`_dacp._tcp`, matching a service instance named `iTunes_Ctrl_<DACP-ID>`
where `DACP-ID` is a header value the client sends in `SETUP` (currently not
captured - `Active-Remote`, the header value required on every DACP request,
isn't captured either). Commands are simple
(`GET /ctrl-int/1/<command> HTTP/1.1` with an `Active-Remote:` header) for
play/pause/next/prev, but shairport-sync's volume handling is materially
more complex: it's not "set volume to X" but a multi-room-aware protocol
that queries a whole connected-speaker list, matches this device's own
entry by a machine-number derived from its MAC/deviceid, and computes a
relative-volume conversion - designed for scenarios with several AirPlay
receivers active at once. Whether a simpler direct path exists for a
single-speaker session is still unconfirmed; that uncertainty is exactly why
volume sync was sequenced last.

## Transport controls + volume sync: two dead ends, one working path
Continuation of the Now Playing work - the user wanted hardware volume
buttons synced with the iPhone's AirPlay volume (two-way), plus real
play/pause/next/prev controls in the notification. Three things were tried;
two turned out to be real walls, not bugs to fix.

**Notification/lock-screen transport buttons: reverted, not proven possible
on this device.** Tried twice: (1) plain `Notification.Builder.addAction()`
buttons with `BigTextStyle` for a bigger expanded notification - never
rendered, even fully expanded. (2) `RemoteControlClient` (the correct
pre-MediaSession, API<21 API for lock-screen/media controls, with a
manifest-registered `MediaButtonReceiver` for `ACTION_MEDIA_BUTTON`) -
also never rendered. Real device logs mentioned a Samsung TouchWiz "Mini
Controller" widget specifically for media notifications, which neither
approach seems to trigger. Both attempts fully reverted (`MediaButtonReceiver.kt`
removed, manifest receiver removed, RemoteControlClient registration removed) -
no dead code left in place for a feature that doesn't work.

**DACP volume push (Ace4 buttons -> iPhone): reverted, looks genuinely
blocked by iOS, not a bug in our request.** Implemented `DacpClient.kt`
(mDNS-browse `_dacp._tcp`, matching `iTunes_Ctrl_<DACP-ID>`, then
`GET /ctrl-int/1/setproperty?include-speaker-id=<machine>&dmcp.volume=<pct>`
per shairport-sync's `dacp_set_include_speaker_volume` - the single-speaker
case only, per earlier research). Discovery worked (found the DACP port
every time), and the request format matched the reference implementation
exactly (confirmed via full request/response logging), but iOS consistently
returned `400 Bad Request` with an empty body for every single attempt,
regardless of the volume value sent - a blanket rejection, not a
parameter-specific error. DACP is an undocumented, reverse-engineered
protocol (Apple has never published a spec), and there's independent
evidence Apple has been tightening programmatic volume control on iOS over
time (e.g. Spotify Connect losing the ability to intercept iPhone volume
buttons after Apple removed the private API it relied on, iOS 17.3-ish).
Concluded this is a real platform restriction, not something fixable from
the receiver side. `DacpClient.kt` and all its call sites were removed
entirely once both of its only two callers (this and the transport buttons
above) were gone - vs. leaving a client with no working command that could
plausibly be sent.

**iOS volume slider -> Ace4's actual output: works, kept.** This direction
doesn't depend on Apple's cooperation - iOS already sends `SET_PARAMETER`
`text/parameters` with `volume: X` (dB attenuation, 0.0 = max, -144.0 =
mute) during playback; the receiver just wasn't acting on it yet. Fixed in
two passes:
1. Apply the actual audio gain via `AudioTrack.setStereoVolume` (dB -> linear
   gain via the standard `10^(dB/20)` conversion). Confirmed working - audio
   audibly changes - but the Android system's own "media volume" indicator
   stayed stale, since track-level gain is a separate value from the
   `STREAM_MUSIC` index the system slider displays.
2. Mirror the change into `AudioManager.setStreamVolume` so the two stay in
   sync visually. Doing this with the *same* exponential gain value produced
   uneven, sometimes-invisible steps on Android's small integer index range
   (e.g. 0-15) - iOS sends evenly-spaced dB steps per button press, and
   pushing those through an exponential curve onto ~16 discrete slots
   compresses/stretches the step size depending where you are in the range.
   Fixed by mapping the on-screen index *linearly* from dB directly (assuming
   iOS's typical 0 to -30dB range) instead of routing it through the same
   gain curve used for actual audio - keeps each iPhone button press to
   roughly one visible step, "as default and intuitive as possible" per the
   user's ask. A residual rough edge remains: iOS reports dB values with
   small floating-point noise (e.g. `-14.374999` instead of a clean
   `-14.375`), which can occasionally round to the wrong side of a step
   boundary and require an extra press at specific points in the range -
   judged an acceptable, likely-unavoidable quantization artifact from
   mapping one discrete step scale onto a different, coarser one, not worth
   chasing further given the core functionality (correct audio, synced
   indicator, near-1:1 stepping) already works well.

## Build environment used for this scaffold
- JDK: Android Studio's bundled JBR (`Android Studio/jbr`, OpenJDK 21.0.8) —
  used explicitly via `JAVA_HOME`, since the system `java` on PATH resolves to
  a stray JDK 23 install with no matching `JAVA_HOME` set.
- Verified `assembleDebug` succeeds end-to-end on this machine (see
  `app/build/outputs/apk/debug/app-debug.apk`).
- `local.properties`: `sdk.dir` must use forward slashes. A first attempt with
  Windows-style backslashes (`C:\Users\...`) broke the build — Java `.properties`
  file parsing treats `\` as an escape character, not a path separator.
