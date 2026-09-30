# Roadmap

## Now: Android test release (this branch)

Sign-in, pairing, end-to-end encrypted chat with four message states, typing,
presence, two-way status sharing, partner hints, dying-battery alert, nudges,
quick statuses, safety codes, unlink/sign-out/delete. See the README.

Before handing the APK to testers:

1. Deploy the server with SMTP and FCM configured (`server/README.md`).
2. Set `MAMI_SERVER_URL` and `GOOGLE_SERVICES_JSON` in the GitHub repository
   (`README.md`) and download the APK from the CI run.
3. Test on at least one Samsung, one Xiaomi/Redmi and one Pixel phone.
   Aggressive battery savers on some brands delay background work; tell
   testers to set MaMi's battery usage to "Unrestricted".

## Next (Android)

- App lock with fingerprint/face (BiometricPrompt)
- Photos and voice notes (encrypted attachments)
- Reply, edit, unsend, reactions
- Home-screen widget: partner status, battery, local time, countdown
- "Home safe": one-tap arrival message, then optional geofence
- Encrypted backup protected by a passphrase, so a lost phone doesn't mean
  lost history
- Voice and video calls (WebRTC, phone-style ringing via ConnectionService)
- Anniversaries, countdown to the next meeting, "on this day"
- Wear OS: nudges as wrist vibrations
- Play Store listing: privacy policy, Data safety form, internal testing track

## Then: iOS

A SwiftUI app on the same `core/` (UniFFI generates the Swift bindings;
`core/uniffi.toml` already names the module `MamiCore`). Needs a Mac with
Xcode. The notification service extension will decrypt messages and send
delivery receipts while the app is closed. Signal strength is not available on
iOS; everything else in the status card is.

## Server

- Per-IP rate limiting, abuse reporting
- Postgres option and horizontal scaling (presence hub via Redis/NATS)
- More than one phone per person (a tablet next to a phone)
