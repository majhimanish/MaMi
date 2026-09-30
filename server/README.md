# MaMi server

A small Rust service (axum + SQLite) that relays encrypted envelopes between the
two phones of a couple. It stores email addresses, display names, public keys
and ciphertext waiting to be delivered — never message contents or private
keys.

## Configuration

All settings are environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `MAMI_BIND` | `0.0.0.0:8080` | Address to listen on |
| `MAMI_DATABASE_URL` | `sqlite://mami.db` | SQLite file (created on first start, migrations run automatically) |
| `MAMI_SMTP_URL` | — | e.g. `smtps://user:password@smtp.example.com:465`. Without it, sign-in codes are only printed to the log (fine for development, not for testers). |
| `MAMI_MAIL_FROM` | — | e.g. `MaMi <hello@example.com>` |
| `MAMI_FCM_SERVICE_ACCOUNT` | — | Path to a Firebase service-account JSON key. Without it, phones are not woken by push. |
| `RUST_LOG` | `info` | Log level |

## Deploying for testers

Any small Linux VM works (1 vCPU / 512 MB is plenty for the first users).

```sh
cargo build --release -p mami-server
scp target/release/mami-server server:/opt/mami/
```

Run it behind a TLS-terminating reverse proxy that supports WebSockets. With
[Caddy](https://caddyserver.com), the whole `Caddyfile` is:

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
EnvironmentFile=/opt/mami/secrets.env
ExecStart=/opt/mami/mami-server
Restart=always
DynamicUser=yes
StateDirectory=mami

[Install]
WantedBy=multi-user.target
```

Back up `mami.db` regularly. Losing it signs everyone out and unlinks all
couples, but no messages are lost from phones.

## Push notifications (Firebase Cloud Messaging)

1. In the [Firebase console](https://console.firebase.google.com), create a
   project and add an Android app with package name `app.mami`.
2. Download `google-services.json` for the app (see `android/README.md`).
3. Project settings → Service accounts → **Generate new private key**. Store
   the file on the server and point `MAMI_FCM_SERVICE_ACCOUNT` at it. Keep it
   secret; never commit it.

Pushes contain only `{"t": "wake"}`; the phone downloads and decrypts the
message itself.

## Limits built in

- Sign-in codes: 6 digits, 10 minutes, 5 attempts, at most one every 30 s and
  5 per hour per address.
- Invites expire after 7 days; one open invite per person.
- Envelopes up to 64 KB; delivered messages are remembered without their
  contents for 7 days so a resend is never delivered twice.

Not yet built: per-IP rate limiting (put the proxy's rate limiter in front),
Postgres, multiple phones per person.
