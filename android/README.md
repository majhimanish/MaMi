# MaMi for Android

Kotlin + Jetpack Compose. The encryption and protocol live in the shared Rust
core (`../core`); a Gradle task compiles it for each Android ABI with
`cargo-ndk` and generates the Kotlin bindings (`app.mami.core`) with UniFFI.

## Requirements

- Android Studio (or the SDK command-line tools) with **SDK platform 37** and an
  **NDK** (any recent one; set `ANDROID_NDK_HOME` or `android.ndkVersion`)
- Rust via [rustup](https://rustup.rs), plus:

  ```sh
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install cargo-ndk
  ```

## Build and run

```sh
./gradlew installDebug                                   # all ABIs
./gradlew installDebug -Pmami.abis=arm64-v8a             # faster: one ABI
./gradlew installDebug -Pmami.serverUrl=http://192.168.1.20:8080
```

Debug builds default to `http://10.0.2.2:8080` (the host machine, seen from the
emulator) and allow plain HTTP. Release builds require HTTPS and take the
server from `-Pmami.serverUrl`.

## Push notifications

Optional but strongly recommended for testers. In the Firebase console add an
Android app with package `app.mami`, download `google-services.json` and put
it at `android/app/google-services.json` (it is git-ignored). The build reads it
directly; no Google Services Gradle plugin is needed. For CI, store the file's
contents in the `GOOGLE_SERVICES_JSON` repository secret.

## Release signing

Release builds are signed only when these environment variables are set (for
example from CI secrets):

```
MAMI_KEYSTORE_FILE, MAMI_KEYSTORE_PASSWORD, MAMI_KEY_ALIAS, MAMI_KEY_PASSWORD
```

## How the app is put together

| Package | Role |
|---|---|
| `data` | REST client, WebSocket, Room database, Keystore-sealed settings, `CryptoStore` (keys and sessions saved after every change) |
| `sync` | `Messenger` (the orchestrator: pairing, sessions, send/receive, receipts, status), push service, background workers, notifications |
| `device` | Reads battery, charging, network, signal, ringer and Do Not Disturb; watches for changes |
| `ui` | Compose screens: sign-in, profile, sharing, pairing, chat, partner sheet, settings, safety code |
| `demo` | `DemoBackend`: a simulated partner behind the same `MamiBackend` interface as `Messenger`, used by debug builds and the screenshot tests |
| `ui/debug` | The 🐞 playground (debug builds only): jump to any screen, drive the demo partner, change the theme |

What each phone can and can't know is described in `docs/PROTOCOL.md`.

## Screenshots

`./gradlew recordRoborazziDebug` renders every screen with Robolectric into
`docs/screenshots/`, using the demo partner. It needs the host build of the
Rust core first (`cargo build -p mami-core` from the repository root). CI does
both and commits the images on `claude/` branches.
