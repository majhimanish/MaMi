# MaMi server

A small Rust service (axum + SQLite) that relays encrypted envelopes between the
two phones of a couple. It stores email addresses, display names, public keys,
ciphertext waiting to be delivered and encrypted attachments waiting to be
downloaded — never message contents, attachment keys or private keys.

## Configuration

All settings are environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `MAMI_BIND` | `0.0.0.0:8080` | Address to listen on |
| `MAMI_DATABASE_URL` | `sqlite://mami.db` | SQLite file (created on first start, migrations run automatically) |
| `MAMI_SMTP_URL` | — | e.g. `smtps://user:password@smtp.example.com:465`. Without it, sign-in codes are only printed to the log (fine for development, not for testers). |
| `MAMI_MAIL_FROM` | — | e.g. `MaMi <hello@example.com>` |
| `MAMI_FCM_SERVICE_ACCOUNT` | — | Path to a Firebase service-account JSON key. Without it, phones are not woken by push (and closed apps don't ring for calls). |
| `MAMI_BLOB_DIR` | `blobs` | Folder for encrypted attachments waiting to be downloaded |
| `MAMI_MAX_UPLOAD_MB` | `100` | Largest single attachment |
| `MAMI_UPLOAD_QUOTA_MB` | `2048` | Most attachment data one person can have waiting at once |
| `MAMI_STUN_URLS` | `stun:stun.l.google.com:19302` | Comma-separated STUN servers for calls |
| `MAMI_TURN_URLS` | — | Comma-separated TURN servers, e.g. `turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349?transport=tcp` |
| `MAMI_TURN_SECRET` | — | coturn's `static-auth-secret`. Without TURN, a call only connects when the two phones can reach each other directly. |
| `RUST_LOG` | `info` | Log level |

## Deploying for testers

Any small Linux VM works (1 vCPU / 512 MB is plenty for the first users).

With Docker (build from the repository root):

```sh
docker build -f server/Dockerfile -t mami-server .
docker run -d --name mami --restart always -p 127.0.0.1:8080:8080 \
  -v mami-data:/data \
  -e MAMI_SMTP_URL=... -e MAMI_MAIL_FROM=... \
  -e MAMI_FCM_SERVICE_ACCOUNT=/data/firebase-service-account.json \
  -e MAMI_TURN_URLS=... -e MAMI_TURN_SECRET=... \
  mami-server
```

Or as a plain binary:

```sh
cargo build --release -p mami-server
scp target/release/mami-server server:/opt/mami/
```

Run it behind a TLS-terminating reverse proxy that supports WebSockets and
uploads of up to `MAMI_MAX_UPLOAD_MB` (nginx allows only 1 MB unless you raise
`client_max_body_size`). With [Caddy](https://caddyserver.com), the whole
`Caddyfile` is:

```
mami.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

A systemd unit:

```ini
[Unit]
Description=MaMi relay
After=network-online.target

[Service]
WorkingDirectory=/opt/mami
Environment=MAMI_BIND=127.0.0.1:8080
Environment=MAMI_DATABASE_URL=sqlite:///opt/mami/mami.db
Environment=MAMI_BLOB_DIR=/opt/mami/blobs
EnvironmentFile=/opt/mami/secrets.env
ExecStart=/opt/mami/mami-server
Restart=always
DynamicUser=yes
StateDirectory=mami

[Install]
WantedBy=multi-user.target
```

Back up `mami.db` regularly. Losing it signs everyone out and unlinks all
couples, but no messages are lost from phones. The blob folder doesn't need a
backup: attachments only wait there until the partner's phone has them.

## Calls (STUN and TURN)

Calls are WebRTC, peer to peer and end-to-end encrypted (DTLS-SRTP, with the
key fingerprints exchanged inside the encrypted chat). The server only passes
the call setup along.

Most of the time the phones find each other with STUN. Behind strict mobile
carriers or office Wi-Fi they need a TURN relay, which forwards the
(still encrypted) audio and video. Run [coturn](https://github.com/coturn/coturn)
next to the server:

```
# /etc/turnserver.conf
listening-port=3478
tls-listening-port=5349
realm=turn.example.com
use-auth-secret
static-auth-secret=<a long random string>
cert=/etc/letsencrypt/live/turn.example.com/fullchain.pem
pkey=/etc/letsencrypt/live/turn.example.com/privkey.pem
no-cli
no-multicast-peers
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
```

Then set `MAMI_TURN_URLS=turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349?transport=tcp`
and `MAMI_TURN_SECRET` to the same secret. Phones get credentials that expire
after a day (`GET /v1/calls/ice-servers`). Relayed calls use the server's
bandwidth: roughly 0.1 MB/s for voice and 0.5–1.5 MB/s for video.

## Push notifications (Firebase Cloud Messaging)

1. In the [Firebase console](https://console.firebase.google.com), create a
   project and add an Android app with package name `app.mami`.
2. Download `google-services.json` for the app (see `android/README.md`).
3. Project settings → Service accounts → **Generate new private key**. Store
   the file on the server and point `MAMI_FCM_SERVICE_ACCOUNT` at it. Keep it
   secret; never commit it.

Pushes contain only `{"t": "wake"}` (or `{"t": "call"}` for an incoming
call, which expires after a minute); the phone downloads and decrypts the
message itself.

## Limits built in

- Sign-in codes: 6 digits, 10 minutes, 5 attempts, at most one every 30 s and
  5 per hour per address.
- Invites expire after 7 days; one open invite per person.
- Envelopes up to 64 KB; delivered messages are remembered without their
  contents for 7 days so a resend is never delivered twice.
- Attachments up to 100 MB each and 2 GB waiting per person. The partner's
  phone deletes each one after downloading it; anything left is deleted after
  30 days, and everything is deleted when a couple unlinks.
- Call setup messages expire after a minute, so a phone never rings for a
  call that's already over.

Not yet built: per-IP rate limiting (put the proxy's rate limiter in front),
Postgres, multiple phones per person.
