# MaMi

A private messenger for couples. Two people, one encrypted conversation, and
enough context about each other's phone that nobody has to guess why the other
one went quiet.

## What it does today (Android, first test release)

- **Sign in with email.** A 6-digit code, no password.
- **Pairing.** One partner creates an invite (optionally locked to the other's
  email address), the other enters the code. After that the two accounts are
  linked; either one can unlink at any time, which deletes the conversation on
  both phones.
- **End-to-end encryption.** Every phone creates its own keys. Messages, read
  receipts, typing and device status are encrypted on the phone with the
  Double Ratchet (Olm, via the audited `vodozemac` library). The server only
  relays ciphertext. Partners can compare a safety code to rule out a
  man-in-the-middle, and are warned when the other's keys change.
- **Four honest message states**: still on my phone → reached MaMi → reached
  their phone → seen, each with an exact timestamp (tap a message).
- **Partner status, shared two-way and opt-in**: battery and charging,
  Wi-Fi/mobile data and signal, silent/vibrate/Do Not Disturb, local time,
  plus a status you set yourself ("🚗 Driving for 1 hour"). You only see what
  you share too.
- **"Why are they quiet?" hints**: phone probably died (it was at 4% and went
  silent), probably asleep (it's 2 am there), on silent, weak signal, last
  seen.
- **Dying-battery alert**: the phone tells the partner automatically at 5%.
- **Typing indicator, presence, "thinking of you" nudges** (hug, kiss, miss
  you) with their own notification channel.
- **Privacy**: keys sealed by the Android Keystore, no cloud backups, optional
  hidden notification text, content-free push notifications.

## Repository layout

| Path | What |
|---|---|
| [`core/`](core) | Rust library shared by Android now and iOS later: keys, encrypted sessions, the message format, sharing rules and the partner hints. Exported to Kotlin/Swift with UniFFI. |
| [`server/`](server) | Rust relay server (axum + SQLite): sign-in, pairing, public-key directory, encrypted message queue, receipts, presence, push. |
| [`android/`](android) | The Android app (Kotlin, Jetpack Compose). |
| [`docs/`](docs) | [Protocol](docs/PROTOCOL.md), [roadmap](docs/ROADMAP.md). |

## Getting a test build

Every push builds a debug APK on GitHub Actions (**Actions → CI → Artifacts →
`mami-debug-apk`**). Testers install it by allowing "install unknown apps".

For that APK to work outside an emulator, point it at your server: set the
repository variable **`MAMI_SERVER_URL`** (Settings → Secrets and variables →
Actions → Variables), for example `https://mami.example.com`. Debug builds also
let testers change the server address on the sign-in screen.

Push notifications need Firebase: add the contents of your
`google-services.json` as the repository secret **`GOOGLE_SERVICES_JSON`**
(see [`android/README.md`](android/README.md)). Without it the app still
works, but messages arrive only while the app is open or on the 15-minute
background refresh.

## Running everything locally

```sh
# Core and server tests
cargo test --workspace

# Server (sign-in codes are printed to the log until SMTP is configured)
cargo run -p mami-server

# Android app (needs the Android SDK + NDK, Rust, and `cargo install cargo-ndk`)
cd android && ./gradlew installDebug
```

See [`server/README.md`](server/README.md) to deploy the server and
[`android/README.md`](android/README.md) for app setup.
