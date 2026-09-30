-- Accounts. A user is identified by a verified email address.
CREATE TABLE users (
    id           TEXT PRIMARY KEY,
    email        TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL DEFAULT '',
    partner_id   TEXT REFERENCES users(id) ON DELETE SET NULL,
    paired_at    INTEGER,
    created_at   INTEGER NOT NULL
);

-- The one phone a user is signed in on. Signing in again replaces it.
-- Only public keys are stored here; private keys never leave the phone.
CREATE TABLE devices (
    user_id              TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    token_hash           BLOB NOT NULL UNIQUE,
    curve25519           TEXT,
    ed25519              TEXT,
    curve25519_signature TEXT,
    push_token           TEXT,
    last_seen_at         INTEGER,
    created_at           INTEGER NOT NULL
);

CREATE TABLE one_time_keys (
    user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key_id    TEXT NOT NULL,
    key       TEXT NOT NULL,
    signature TEXT NOT NULL,
    PRIMARY KEY (user_id, key_id)
);

CREATE TABLE login_codes (
    email        TEXT PRIMARY KEY,
    code_hash    BLOB NOT NULL,
    expires_at   INTEGER NOT NULL,
    attempts     INTEGER NOT NULL DEFAULT 0,
    sent_at      INTEGER NOT NULL,
    window_start INTEGER NOT NULL,
    window_count INTEGER NOT NULL
);

-- One open invite per user.
CREATE TABLE invites (
    code          TEXT PRIMARY KEY,
    inviter_id    TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    partner_email TEXT,
    expires_at    INTEGER NOT NULL
);

-- Encrypted envelopes waiting for the recipient's phone, plus delivery
-- receipts waiting for the sender's phone. Acknowledged messages lose their
-- body but are kept for a week so a resent message is not delivered twice.
CREATE TABLE queue (
    seq          INTEGER PRIMARY KEY AUTOINCREMENT,
    recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind         TEXT NOT NULL CHECK (kind IN ('message', 'status', 'delivered')),
    client_id    TEXT NOT NULL,
    message_type INTEGER,
    body         TEXT,
    created_at   INTEGER NOT NULL,
    acked_at     INTEGER
);
CREATE INDEX queue_by_recipient ON queue (recipient_id, seq);
CREATE UNIQUE INDEX queue_dedupe ON queue (sender_id, recipient_id, kind, client_id);
