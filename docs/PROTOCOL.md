# How MaMi works

## Who knows what

| | The two phones | The MaMi server | Google (push) |
|---|---|---|---|
| Message text, nudges, alerts, reactions, edits | ✅ | ❌ ciphertext only | ❌ |
| Photos, videos, voice messages, files | ✅ | ❌ encrypted blobs, without the keys; deleted once downloaded | ❌ |
| Which links you share (and their previews) | ✅ (made on the sender's phone) | ❌ | ❌ |
| Call audio and video | ✅ | ❌ (peer to peer, or relayed by TURN still encrypted) | ❌ |
| That a call happened, and when | ✅ | ✅ (call setup goes through it, encrypted) | ✅ (`{"t":"call"}`) |
| Battery, network, ringer, local time, quick status | ✅ (only what's shared, two-way) | ❌ ciphertext only | ❌ |
| Read receipts, typing | ✅ | ❌ ciphertext only | ❌ |
| Email, display name, who is paired with whom | ✅ | ✅ | ❌ |
| When messages are sent and delivered, message sizes (padded to 64-byte steps), attachment sizes | ✅ | ✅ | ❌ |
| When the app is open (presence) | ✅ | ✅ | ❌ |
| That a phone has something waiting | — | ✅ | ✅ (`{"t":"wake"}`, nothing else) |
| Private keys | only the phone that made them | ❌ | ❌ |

## Keys and sessions

Each phone creates an Olm account: a long-term Curve25519 key (for key
agreement) and an Ed25519 key (for signatures). It uploads the public keys, a
signature binding the two, and 20 signed one-time keys. It tops the one-time
keys up when fewer than 5 are left.

To talk, a phone claims one of the partner's one-time keys from the server,
checks both signatures, and starts a Double Ratchet session (Olm 3DH). Its
first message is a `Hello`, sent as a pre-key message; the partner's phone
creates the matching session from it. Each message then uses a new key, and
old keys are deleted, so a key stolen later cannot decrypt earlier messages.

Both phones may start a session at the same moment. That's fine: each keeps
up to four sessions per partner, tries all of them when decrypting, and
encrypts with the one that most recently received a message, so both settle on
the same one.

The **safety code** is 8 groups of 5 digits derived from both Ed25519 keys
(SHA-512, sorted keys, so both phones show the same code). If the codes match
when compared in person, the server did not swap the keys. When a partner's
keys change (new phone), the app shows a warning, clears the "verified" mark,
and re-sends anything the old phone never received.

On the phone, the account and sessions are serialised by `vodozemac`,
encrypted with a random 32-byte key, and that key is sealed with an AES key
inside the Android Keystore. Backups are disabled.

## Payloads

Encrypted payloads are JSON, padded to a multiple of 64 bytes before
encryption. Kinds (`t`):

| `t` | Meaning | Server kind |
|---|---|---|
| `hello` | first message on a new session | message |
| `text` | a chat message (`id`, `body`, `sent_at_ms`, `reply_to`) | message |
| `read` | these message ids were on screen | message |
| `typing` | started/stopped typing | ephemeral |
| `status` | device status snapshot (only shared fields) | status |
| `nudge` | thinking of you / hug / kiss / miss you | message |
| `alert` | battery about to run out | message |
| `media` | photo, video, voice message or file: blob id, file key, size, type, dimensions, length, a tiny thumbnail, voice waveform, caption, `view_once` | message |
| `reaction` | set or remove my reaction to a message | message |
| `edit` | new text for one of my messages | message |
| `unsend` | remove one of my messages from both phones | message |
| `pin` | pin or unpin a message for both | message |
| `opened` | a view-once photo or video was opened | message |
| `call_offer`, `call_answer` | WebRTC session descriptions | call |
| `call_ringing`, `call_candidates`, `call_media`, `call_end` | ringing, network paths, camera/mute, hang up / declined / busy / no answer | call |
| `together` | a shared value: the day you got together, the next meeting time or its label; each carries `updated_at_ms` and the newest wins | message |
| `mood` | a mood check-in: an emoji and an optional note | message |
| `letter` | a love letter: `title`, `body`, `paper`, optional `open_at_ms` (sealed until then) | message |
| `check_in` | home safe / leaving now / arrived | message |
| `live_location` | I started sharing my location until `until_ms` (the card in the chat) | message |
| `location` | a position (`lat`, `lng`, accuracy, speed) while sharing | location |
| `live_location_end` | I stopped sharing early | message |

Text messages can carry a `link` preview (`url`, `title`, `description`, a
small image), made on the sender's phone, and a `deliver_at_ms`: a scheduled
message. It's sent straight away (so it arrives even if the sender's phone is
off at that time), and the receiving phone keeps it hidden, without a
notification, until then.

The status payload can say `paused_until_ms` (sharing paused: no battery,
network, sound or time fields, and the partner's app says so instead of
guessing), `auto_status` (a status the phone set itself, like driving) and
`woke_at_ms` (first use of the phone this morning). Pausing is two-way like
everything else: while I've paused, my app doesn't show the partner's phone
either.

Unknown kinds and enum values decode as `unknown` and are ignored, so newer
apps can add features without breaking older ones.

## Server envelope kinds

- **message**: stored until the recipient's phone acknowledges it, then a
  `delivered` receipt is queued for the sender. Resending the same id never
  delivers twice. Can wake the recipient with a push.
- **status**: only the newest one is kept.
- **location**: live location updates; only the newest one is kept, and
  only for the partner's open app or next sync. Never pushed.
- **ephemeral**: forwarded only if the partner's app is open; never stored.
- **delivered**: generated by the server when the recipient acknowledges a
  message.
- **call**: call setup. Stored until acknowledged, but never delivered more
  than a minute after it was sent (a call nobody answered in time is over).
  With `push`, the server wakes the partner's phone with a high-priority
  `{"t":"call"}` push that expires after 60 seconds.

## Attachments

1. The sender's phone encrypts the file with a new random 32-byte key:
   ChaCha20-Poly1305 over 64 KiB chunks, STREAM construction (a 7-byte random
   nonce prefix, the chunk number and a "last chunk" flag), so chunks can't
   be reordered, dropped or cut off. Photos are re-encoded first (at most
   2560 px), which also drops their location data.
2. It uploads the ciphertext to `POST /v1/blobs` and gets a blob id.
3. It sends a `media` payload with the blob id and the key, end-to-end
   encrypted like any message.
4. The partner's phone downloads, checks and decrypts the file, then deletes
   the blob from the server. Photos and voice messages download straight
   away; videos and files over 16 MB wait for Wi-Fi or a tap.

Blobs are visible only to the uploader and their partner, limited to 100 MB
each and 2 GB waiting per person, deleted after 30 days if nobody fetched
them, and deleted with the couple's link.

## Calls

Calls are WebRTC with DTLS-SRTP. The offer and answer carry the DTLS
fingerprints and travel inside the Olm-encrypted chat, so the media
encryption keys are agreed between the two phones and can't be swapped by
the server or a relay. Media flows peer to peer when possible; otherwise
through the TURN relay, which only sees encrypted packets.

`GET /v1/calls/ice-servers` gives STUN servers and TURN credentials
(coturn's REST scheme: username `expiry:user`, password
base64(HMAC-SHA1(secret, username)), valid for a day).

Caller → `call_offer` (with push) → callee rings and answers `call_ringing`
→ callee accepts: `call_answer` → both trickle `call_candidates` → connected.
Either side ends with `call_end`. Unanswered calls ring out after 45
seconds; a phone already on a call answers `busy`; if both call at once, the
call with the smaller id wins on both phones.

## REST API (all JSON, `Authorization: Bearer <token>` unless noted)

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/auth/start` | `{email}` → emails a code. No auth. |
| POST | `/v1/auth/verify` | `{email, code}` → `{token, user_id, new_user}`. Replaces the previous phone. No auth. |
| POST | `/v1/auth/sign-out` | |
| GET / PATCH / DELETE | `/v1/me` | profile, partner (with public keys and presence), open invite / `{display_name}` / delete account |
| PUT | `/v1/keys` | `{identity?, one_time_keys}` |
| PUT | `/v1/push-token` | `{token}` |
| POST / DELETE | `/v1/invites` | `{partner_email?}` → `{code, partner_email, expires_at_ms}` / cancel |
| POST | `/v1/invites/accept` | `{code}` → partner |
| DELETE | `/v1/partner` | unlink (both sides) |
| POST | `/v1/partner/claim-key` | → partner identity + one one-time key |
| GET | `/v1/envelopes?after_seq=` | waiting envelopes |
| POST | `/v1/envelopes` | `{id, kind, message_type, body, push}` → `{id, at_ms}` |
| POST | `/v1/envelopes/ack` | `{seqs}` |
| POST | `/v1/blobs` | body: the encrypted file → `{id, size}` |
| GET / DELETE | `/v1/blobs/{id}` | download / delete (uploader or partner only) |
| GET | `/v1/calls/ice-servers` | → `{ice_servers: [{urls, username?, credential?}], ttl_s}` |
| GET | `/v1/ws` | WebSocket |

Errors are `{"error": "<code>"}` with a matching HTTP status.

## WebSocket frames

Client → server: `{"type":"send", ...SendRequest}`, `{"type":"ack","seqs":[…]}`.

Server → client: `ready` (partner presence), `accepted`, `rejected`,
`envelope`, `presence` (`online`, `last_seen_ms`), `pairing`
(`paired` / `unpaired` / `keys_changed`). Waiting envelopes are sent right
after `ready`.
