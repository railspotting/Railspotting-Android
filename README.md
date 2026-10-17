# RailSpotting for Android

An Android app that turns a phone into a RailSpotting **mobile receiver**. The owner signs in
with Google, plugs an RTL-SDR dongle in with a USB OTG adapter, and starts listening. The phone then
reports what the dongle hears, and where the phone is, to the RailSpotting headend under API contract
v1.7.

The one dongle works in one of two modes, which the owner switches on the Activity tab:
- **End-of-train (EOT)**: decodes EOT devices on the phone (unit, brake pipe pressure, motion).
  This is the default, as EOT is the fixed receivers' priority.
- **Road channels**: watches the voice channels near the phone, with **live listen**, which plays
  the call on the air.

**Using it:** the app reports to RailSpotting ([railspotting.com](https://railspotting.com)), a
service that is not open source. You need a RailSpotting account (sign in with Google) for the
phone to be a receiver. This repository is the app and its Rust core; the service's API is
described by the contract documents it publishes.

- Package name: `com.railspotting.android`
- Android 10 (API 29) or later, 64-bit (arm64; x86_64 for the emulator)
- Headend: `https://api.railspotting.com/api/v1`

The contract and its server side live in the headend repository (`docs/contract-v1.7.md`,
`docs/android-handoff.md`, `web/api/receiver-openapi.json`).

## Technology: Kotlin, with the receiver's Rust core

The app is two parts:

| Part | Language | Does |
|---|---|---|
| `app/` | Kotlin | Google sign-in (Credential Manager), the Keystore, location (fused provider), the foreground service, USB permission for the dongle, live listen audio, the screens (Jetpack Compose) |
| `core/` | Rust | The receiver: heartbeats, the event and report outbox, GPS fix rules, request signing, and the dongle's processing |
| `native/` | C | The dongle driver: libusb and a patched librtlsdr, built with the NDK (see [native/README.md](native/README.md)) |

`core/` builds on the fixed receiver's own crates, from the `receiver/` submodule
(`railspotting/Railspotting-Receiver`):

- `proto`: request signing and the API client;
- `store`: the SQLite outbox;
- `dsp` and `engine`: the SDR processing (`SdrSource`, `SdrHandle`), event detection, the EOT
  decoder and the voice audio tap.

**Why this split.** The radio side is most of what a receiver does, and it is subtle:
- channel windows and detection;
- event timing and RSSI;
- the EOT decoder;
- stall and reopen handling.

The fixed receivers run this code in production. Sharing it means a phone's events and EOT decodes
are built exactly as a fixed receiver's and pass the same tests, and a fix reaches both. A Kotlin
rewrite would mean two copies to keep in step.

The Android-only parts stay in Kotlin, because they need the platform's own APIs:
- Credential Manager is the only Google sign-in on Android.
- The fused location provider and the Keystore are Android services.
- USB host access must go through `UsbManager`.
- Audio output goes through `AudioTrack`.

The cost of the split is the build: it needs the NDK and `cargo-ndk`, and `core/` and `native/` must
build for each ABI. The Gradle build runs both steps itself.

**The JNI surface is small** (`app/.../core/Native.kt`, `core/src/jni_api.rs`). It passes JSON
text, so no models are duplicated.

**The dongle.** Android lets an app list no USB devices. It gets a file descriptor for a dongle
the owner allowed. Kotlin asks for permission, opens the dongle and passes the descriptor to the
core. The receiver's `engine::rtl` then opens it through `rtlsdr_open_sys_dev`, a small patch to
librtlsdr ([native/README.md](native/README.md)).

### Dependencies

Kept few, all from Google or JetBrains:

- AndroidX: `core-ktx`, `activity-compose`, `lifecycle-runtime-compose`, Compose UI and Material 3
  (through the Compose BOM);
- `androidx.credentials` and `credentials-play-services-auth`, plus Google's `googleid` library,
  for sign-in;
- `play-services-location` for the fused location provider;
- `androidx.webkit`, to open the live map on the phone's position (a script that runs before the
  page's own).

There is no JSON or HTTP library on the Kotlin side: Android's `org.json` is enough, and the core
makes every headend call. The Rust side adds only `jni` to what the receiver already uses. The
dongle driver, libusb and librtlsdr, is built from pinned source in `native/`.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/com/railspotting/android/` | the app: `signin/`, `service/ListenService.kt`, `usb/`, `location/`, `store/` (Keystore, settings), `ui/` |
| `app/src/test/` | Kotlin unit tests (JVM) |
| `core/src/` | the Rust core: `agent.rs` (the receiver loop), `radio.rs` (the dongle's modes and live listen), `location.rs` (fix rules), `link.rs` (hello, link, clock), `jni_api.rs` |
| `core/tests/` | the core end to end against a fake mobile headend and a simulated dongle (the receiver's radio simulator); the headend's signing vectors |
| `native/` | the dongle driver: libusb and librtlsdr (submodules), the librtlsdr patch, `build.sh` |
| `receiver/` | git submodule: the RailSpotting receiver (Rust workspace) |

## Building

You need:

- JDK 17;
- the Android SDK with platform 37 (the app compiles against 37 and targets 36) and NDK `29.0.14206865`;
- Rust (1.85 or later) with the Android targets;
- `cargo-ndk`.

On a Mac with Homebrew:

    brew install openjdk@17
    brew install --cask android-commandlinetools
    sdkmanager --sdk_root="$HOME/Library/Android/sdk" --licenses
    sdkmanager --sdk_root="$HOME/Library/Android/sdk" "platform-tools" "platforms;android-36" "ndk;29.0.14206865"
    rustup target add aarch64-linux-android x86_64-linux-android
    cargo install cargo-ndk --locked

Clone with the submodule, and point Gradle at the SDK:

    git clone --recurse-submodules git@github.com:railspotting/Railspotting-Android.git
    cd Railspotting-Android
    echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

Build and install a debug build on a phone connected with USB debugging:

    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Before every build, two tasks build the native code for each ABI:
- `cargoNdk` builds `core/` into `app/build/rustJniLibs`;
- `sdrDriver` builds libusb and the patched librtlsdr into `app/build/sdrJniLibs`.

Both are quick when nothing has changed.

### Release signing

Release builds are signed with the upload key named in `keystore.properties` at the repository
root. That file is git-ignored; never commit it or the key. Make the key once:

    keytool -genkeypair -v -keystore railspotting-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000

Then write `keystore.properties`:

    storeFile=railspotting-upload.jks
    storePassword=...
    keyAlias=upload
    keyPassword=...

The same four values can come from the environment instead: `RS_KEYSTORE`, `RS_KEYSTORE_PASSWORD`,
`RS_KEY_ALIAS`, `RS_KEY_PASSWORD`. Then:

    ./gradlew assembleRelease     # app/build/outputs/apk/release/
    ./gradlew bundleRelease       # app/build/outputs/bundle/release/, for Google Play

Keep the upload key and its passwords in a password manager. On Google Play, use Play App Signing:
Google holds the app signing key, and you sign uploads with this one.

## Google Cloud setup (the owner does this once)

Google sign-in on a phone needs an **Android OAuth client** in the **same Google Cloud project**
as the website's web OAuth client. The app asks Credential Manager for a token for the headend's
*web* client ID, which it gets from `GET /mobile/hello`. Google issues that token only to an app
whose package name and signing certificate are registered in the project as an Android client.
Nothing in the app's code changes.

1. In Google Cloud Console, open the project with the website's sign-in, then **APIs & Services >
   Credentials > Create credentials > OAuth client ID > Android**.
2. Package name: `com.railspotting.android`.
3. SHA-1 certificate fingerprint: one Android client per certificate. You need:
   - **Debug** (each developer machine's debug key, for development builds):

         keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1

     The debug keystore is made by the first debug build.
   - **Release upload key**, for builds you sign yourself:

         keytool -list -v -keystore railspotting-upload.jks -alias upload | grep SHA1

   - **Play App Signing**, if the app is on Google Play: copy the SHA-1 of the *app signing key*
     from Play Console (**Test and release > App integrity > App signing**).
4. The OAuth consent screen is the website's. The app asks for no scopes beyond sign-in.

Until the Android client exists, sign-in fails with a Credential Manager error ("developer
console is not set up correctly" or similar). That error comes from Google, not from the headend.

## Tests

The core, on the development machine (no phone needed):

    cd core && cargo test

These cover:

- the headend's signing vectors (`internal/sig`);
- linking, relinking and `403 revoked`;
- clock correction from `X-RS-Server-Time`;
- the fix rules (100 m, 120 s, 20 per call, 1 call per 10 s with bursts of 5, `429 Retry-After`);
- the offline queue (events sent later with their own times);
- the heartbeat's `mobile` block;
- the dongle, simulated by the receiver's radio simulator:
  - an EOT packet decoded on the phone and sent with its fix;
  - live listen following a voice call on the air;
  - switching between road channels and EOT.

The fake headend (`core/tests/common`) follows the headend's `mobile_test.go`.

The app's unit tests (JVM):

    ./gradlew testDebugUnitTest

These cover:

- the dongle USB IDs;
- fix JSON;
- reading the core's status, and describing what was heard;
- the link result and the sign-in nonce's freshness;
- error messages;
- the AES-GCM seal (with a software key: the Keystore exists only on a phone).

There is no sandbox headend. Before linking against production, ask the owner for a test Google
account. A new mobile receiver is provisional and private, so testing doesn't touch the public map.

## How it behaves

- **Signing in** shows the terms and privacy links from `/mobile/hello` first. It makes the
  receiver's Ed25519 key once per install and links with `POST /mobile/link`.
  - The key's 32-byte seed is kept encrypted with an AES-GCM key held in the Android Keystore. It
    is never logged and never backed up.
  - Signing in on a new phone moves the account's receiver there, and the old phone stops
    (`403 revoked`).
- **Listening** runs in a foreground service with a notification and a Stop button. Nothing runs
  outside it: no radio, no location and no headend calls.
  - The service heartbeats every `mobile.heartbeat_interval_s`, uploads events, and runs the
    dongle.
  - Plugging the dongle in opens the app and starts listening when the owner is signed in.
- **Listening means the dongle is connected and streaming**: the contract's "a scanner is
  connected and scanning", with the dongle in the scanner's place. GPS is on only then:
  - fixes are sent after each `location_min_move_m` moved, and at least every
    `location_interval_s`, both from `/config`;
  - the fused provider batches fixes for up to 30 s, to spare the battery.
- **Location permission**: precise location, with an explanation before the prompt. No background
  location permission is needed: the service is started from the screen with the location type,
  so it keeps location access with the screen off.
- **Offline**:
  - Events wait in the outbox with their own times and fixes, and go when the phone is back
    online.
  - Fixes are not kept for long: the headend ignores old ones.
  - `429` and `Retry-After` are honoured.
- **Sharing** is a switch, on by default, sent in every heartbeat. With it off, nothing the phone
  hears reaches the public map.
- **The dongle** runs the receiver's own SDR code in one of two roles:
  - **End-of-train** (`eot`): the plan's EOT channel, with packets decoded on the phone.
  - **Road channels** (`vhf`): the plan's voice channels that fit the dongle's 2.4 MHz.

  The Activity tab switches modes, and the dongle reopens in the new role at once. There is no
  speech transcription on the phone. Burst recordings are off, to save storage.
- **Activity** lists what the phone heard since listening started, newest first, with EOT packets
  decoded. Calls on the air are pulled to the top.
- **Train alerts** come in a high-importance "Trains" notification while listening; both are on
  by default, and the Activity tab turns them off.
  - **End-of-train heard by this phone**, with its decode. One alert per unit every 15 minutes,
    since an EOT repeats about once a minute while the train passes; undecoded signals alert at
    most every 5 minutes.
  - **End-of-train heard by other receivers within 10, 25, 50 (the default) or 100 miles.** Every
    minute the core checks the public live map feed (`/api/public/regions`, `/api/public/state`)
    for the regions within the radius, and alerts once on each new EOT alert whose crossing (or
    receiver) is within it. The first look at a region notes what is there without alerting. The
    phone's position is compared on the phone and never sent: it is the newest fix, or the
    provider's last known position.
  - The phone's own events never appear in the public feed (contract v1.7, 6), which is why the
    first kind exists.
- **The channel list** is what `/config` gives this receiver.
  - When the headend moves the phone (`/location` answers `used` ≥ 1), the app fetches `/config`
    at once, rather than at the next heartbeat.
  - **Refresh** on the Activity tab fetches the list for where the phone is now. It sends a fresh
    fix while listening, and with the proposed v1.9 at any time.
  - The headend picks the plan by where the phone is (contract v1.9, `docs/contract-v1.9.md` in
    the headend repository).
    Every `/location` answer carries `config_version`; when it differs, the app fetches `/config`
    at once. A second refresh within 30 s is limited by the headend, and the app says so.
- **Android Auto** (Car App Library, points-of-interest category):
  - a **Nearby crossings** screen: the crossings within the alert radius from the public live
    map feed, train activity first then the nearest, on a map the car draws, refreshed every
    minute, with a Listening button;
  - the **EOT alerts** appear in the car as heads-up alerts, and tapping one opens that screen.

  Auto allows only its own templates in the car, so the web live map is not there. Test builds
  accept any car host; release builds only Google's Android Auto (`res/values/car_hosts.xml`). To
  try it without a car, use Google's Desktop Head Unit:
  1. In the Android Auto settings on the phone, tap the version ten times for developer mode.
     Turn on **Unknown sources**, then choose **Start head unit server** from the menu.
  2. On the computer: `adb forward tcp:5277 tcp:5277`, then run
     `$ANDROID_HOME/extras/google/auto/desktop-head-unit` (SDK package `extras;google;auto`).
- **Live listen** works in road-channel mode, while the Activity tab is open. It plays the call on
  the air. A call plays to its end, then the newest call still on the air takes over; between
  calls the speaker is quiet.
- **Sign out** stops listening, forgets the key and device ID, and clears the receiver's folder.
  The receiver stays on the account.

## Licences

The app's own source is licensed under either of the Apache License 2.0 (`LICENSE-APACHE`) or the
MIT license (`LICENSE-MIT`), at your option, like the receiver it builds on.

The APK also ships **librtlsdr (GNU GPL 2.0 or later)**, with a small patch, and **libusb (GNU
LGPL 2.1 or later)** as their own shared libraries; the receiver only loads librtlsdr from the
system. Because the APK combines GPL code with the app, the APK as a whole is distributed under
the GNU GPL version 3, which these licences all permit (`NOTICE`).
- The source of every release is this repository at the release's tag, with its submodules. The
  librtlsdr patch and the build script are in [native/](native/README.md).
- The app's menu has **About**: the version, and every piece of open source software in the app
  with its licence and a link to its source. The GPL and LGPL texts ship with the app
  (`app/src/main/assets/licenses`). The list is generated: run `python3 tools/open_source.py` after
  changing dependencies (a test checks it is complete).

## Repository hygiene

Never commit:

- keys, keystores or `keystore.properties`;
- `google-services.json`; the app doesn't use one;
- email addresses, device IDs or locations.

Tests use made-up values (`ann@example.com`, `rs-0a1b2c3d4e5f`, the contract's published test
key). Commits use the GitHub noreply address.
