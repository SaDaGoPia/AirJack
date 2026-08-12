# Decisions log

## RAOP implementation approach
Decision: port shairport-sync via NDK + JNI (not a pure-Kotlin RAOP stack, not
an existing Android AirPlay-receiver library).

- Checked `lujnan/shairport-sync` (the one fork claiming "Support For Android"
  on GitHub) — its README has no Android content beyond a stray `Android.mk` /
  `droid_conf.mk`, no JNI layer, no app, no maintenance signal. Not usable as-is.
- No other actively-maintained Android AirPlay-receiver library was found.
- shairport-sync (mikebrady/shairport-sync) remains the reference implementation
  to cross-compile from when milestone 2 starts.

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

## Build environment used for this scaffold
- JDK: Android Studio's bundled JBR (`Android Studio/jbr`, OpenJDK 21.0.8) —
  used explicitly via `JAVA_HOME`, since the system `java` on PATH resolves to
  a stray JDK 23 install with no matching `JAVA_HOME` set.
- Verified `assembleDebug` succeeds end-to-end on this machine (see
  `app/build/outputs/apk/debug/app-debug.apk`).
- `local.properties`: `sdk.dir` must use forward slashes. A first attempt with
  Windows-style backslashes (`C:\Users\...`) broke the build — Java `.properties`
  file parsing treats `\` as an escape character, not a path separator.
