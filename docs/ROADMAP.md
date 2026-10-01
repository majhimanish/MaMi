# Roadmap

## Now: Android test release (this branch)

Sign-in, pairing, end-to-end encrypted chat with four message states, typing,
presence, two-way status sharing, partner hints, dying-battery alert, nudges,
quick statuses, safety codes, unlink/sign-out/delete; photos, videos, voice
messages, files and link previews; reply, reactions, edit, unsend, pins and
stars; search and shared media; voice and video calls; the Together page
(days together, next meeting, moods, love letters, check-ins, live location,
scheduled messages, pausing sharing) and automatic driving and woke-up
statuses. See the README.

Before handing the APK to testers:

1. Deploy the server with SMTP and FCM configured, and coturn for calls
   (`server/README.md`).
2. Set `MAMI_SERVER_URL` and `GOOGLE_SERVICES_JSON` in the GitHub repository
   (`README.md`) and download the APK from the CI run.
3. Test on at least one Samsung, one Xiaomi/Redmi and one Pixel phone.
   Aggressive battery savers on some brands delay background work and
   incoming calls; tell testers to set MaMi's battery usage to
   "Unrestricted".
4. Calls: try Wi-Fi to mobile data, two different carriers, Bluetooth
   headphones, a locked phone, and calls while the app is closed.
5. Live location with the screen off for an hour, scheduled messages while
   the receiving phone is in Doze, and the driving status on a real drive
   (it needs Google Play services).

## Next (Android)

Couple features still to pick:

- Home-screen widget: partner status, battery, local time, countdown
- "On this day": photos and messages from a year ago
- Anniversary reminders as notifications, and more dates (birthdays)
- A real map for live location (a map provider that doesn't see who looks)

Basics still to do:

- App lock with fingerprint/face (BiometricPrompt)
- Encrypted backup protected by a passphrase, so a lost phone doesn't mean
  lost history
- Switching a voice call to video, and Android's system call integration
  (Telecom/ConnectionService) on phones where it behaves well
- Sending HD originals and compressing long videos before upload
- Wear OS: nudges as wrist vibrations
- Play Store listing: privacy policy, Data safety form, internal testing
  track, and App Bundles so each phone downloads only its own WebRTC library

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
