//! All database access. SQLite keeps deployment to a single file; every
//! query here is plain SQL so moving to Postgres later is mechanical.

use std::str::FromStr;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use sha2::{Digest, Sha256};
use sqlx::Row;
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions};

use crate::protocol::{Envelope, EnvelopeKind, IdentityBundle, SignedOneTimeKey};

/// Limits how many unused one-time keys a phone may park on the server.
pub const MAX_ONE_TIME_KEYS: i64 = 100;
const LOGIN_CODE_TTL_MS: i64 = 10 * 60 * 1000;
const LOGIN_CODE_MAX_ATTEMPTS: i64 = 5;
const LOGIN_CODE_MIN_INTERVAL_MS: i64 = 30 * 1000;
const LOGIN_CODE_WINDOW_MS: i64 = 60 * 60 * 1000;
const LOGIN_CODE_MAX_PER_WINDOW: i64 = 5;
/// A call that isn't answered within a minute has rung out.
pub const CALL_SIGNAL_TTL_MS: i64 = 60 * 1000;

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn sha256(parts: &[&[u8]]) -> Vec<u8> {
    let mut hasher = Sha256::new();
    for part in parts {
        hasher.update(part);
    }
    hasher.finalize().to_vec()
}

pub async fn open(database_url: &str) -> Result<SqlitePool> {
    let options = SqliteConnectOptions::from_str(database_url)?
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .foreign_keys(true)
        .busy_timeout(Duration::from_secs(5));
    let pool = SqlitePoolOptions::new()
        .max_connections(8)
        .connect_with(options)
        .await?;
    sqlx::migrate!("./migrations")
        .run(&pool)
        .await
        .map_err(sqlx::Error::from)?;
    Ok(pool)
}

#[derive(Clone)]
pub struct Store {
    pub pool: SqlitePool,
}

#[derive(Debug, Clone)]
pub struct User {
    pub id: String,
    pub email: String,
    pub display_name: String,
    pub partner_id: Option<String>,
    pub paired_at: Option<i64>,
}

#[derive(Debug, Clone)]
pub struct Invite {
    pub code: String,
    pub inviter_id: String,
    pub partner_email: Option<String>,
    pub expires_at: i64,
}

pub enum LoginCodeIssue {
    Issued,
    Throttled,
}

#[derive(Debug, PartialEq, Eq)]
pub enum LoginCodeCheck {
    Valid,
    Invalid,
    TooManyAttempts,
}

pub enum IdentityUpdate {
    /// First keys for this device.
    Set,
    Unchanged,
    /// The device already registered different keys.
    Conflict,
}

pub enum PairResult {
    Paired,
    AlreadyPaired,
}

type Result<T> = std::result::Result<T, sqlx::Error>;
/// seq, client_id, kind, message_type, body, created_at
type QueueRow = (i64, String, String, Option<i32>, Option<String>, i64);

impl Store {
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    // ---- users -----------------------------------------------------------

    pub async fn user(&self, id: &str) -> Result<Option<User>> {
        let row = sqlx::query(
            "SELECT id, email, display_name, partner_id, paired_at FROM users WHERE id = ?",
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(|r| User {
            id: r.get("id"),
            email: r.get("email"),
            display_name: r.get("display_name"),
            partner_id: r.get("partner_id"),
            paired_at: r.get("paired_at"),
        }))
    }

    pub async fn user_id_by_email(&self, email: &str) -> Result<Option<String>> {
        sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
            .bind(email)
            .fetch_optional(&self.pool)
            .await
    }

    pub async fn create_user(&self, id: &str, email: &str) -> Result<()> {
        sqlx::query("INSERT INTO users (id, email, created_at) VALUES (?, ?, ?)")
            .bind(id)
            .bind(email)
            .bind(now_ms())
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn set_display_name(&self, id: &str, name: &str) -> Result<()> {
        sqlx::query("UPDATE users SET display_name = ? WHERE id = ?")
            .bind(name)
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn delete_user(&self, id: &str) -> Result<()> {
        sqlx::query("DELETE FROM users WHERE id = ?")
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    // ---- sign-in codes ---------------------------------------------------

    pub async fn issue_login_code(&self, email: &str, code_hash: &[u8]) -> Result<LoginCodeIssue> {
        let now = now_ms();
        let existing: Option<(i64, i64, i64)> = sqlx::query_as(
            "SELECT sent_at, window_start, window_count FROM login_codes WHERE email = ?",
        )
        .bind(email)
        .fetch_optional(&self.pool)
        .await?;
        let (window_start, window_count) = match existing {
            Some((sent_at, _, _)) if now - sent_at < LOGIN_CODE_MIN_INTERVAL_MS => {
                return Ok(LoginCodeIssue::Throttled);
            }
            Some((_, start, count)) if now - start < LOGIN_CODE_WINDOW_MS => {
                if count >= LOGIN_CODE_MAX_PER_WINDOW {
                    return Ok(LoginCodeIssue::Throttled);
                }
                (start, count + 1)
            }
            _ => (now, 1),
        };
        sqlx::query(
            "INSERT INTO login_codes (email, code_hash, expires_at, attempts, sent_at, window_start, window_count)
             VALUES (?, ?, ?, 0, ?, ?, ?)
             ON CONFLICT (email) DO UPDATE SET code_hash = excluded.code_hash, expires_at = excluded.expires_at,
               attempts = 0, sent_at = excluded.sent_at, window_start = excluded.window_start,
               window_count = excluded.window_count",
        )
        .bind(email)
        .bind(code_hash)
        .bind(now + LOGIN_CODE_TTL_MS)
        .bind(now)
        .bind(window_start)
        .bind(window_count)
        .execute(&self.pool)
        .await?;
        Ok(LoginCodeIssue::Issued)
    }

    /// Checks a sign-in code. A valid code can only be used once.
    pub async fn check_login_code(&self, email: &str, code_hash: &[u8]) -> Result<LoginCodeCheck> {
        let mut tx = self.pool.begin().await?;
        let row: Option<(Vec<u8>, i64, i64)> = sqlx::query_as(
            "SELECT code_hash, expires_at, attempts FROM login_codes WHERE email = ?",
        )
        .bind(email)
        .fetch_optional(&mut *tx)
        .await?;
        let Some((stored, expires_at, attempts)) = row else {
            return Ok(LoginCodeCheck::Invalid);
        };
        if attempts >= LOGIN_CODE_MAX_ATTEMPTS {
            return Ok(LoginCodeCheck::TooManyAttempts);
        }
        if expires_at < now_ms() {
            return Ok(LoginCodeCheck::Invalid);
        }
        let result = if constant_time_eq(&stored, code_hash) {
            // Keep the row (and its rate-limit window) but make the code unusable.
            sqlx::query("UPDATE login_codes SET expires_at = 0 WHERE email = ?")
                .bind(email)
                .execute(&mut *tx)
                .await?;
            LoginCodeCheck::Valid
        } else {
            sqlx::query("UPDATE login_codes SET attempts = attempts + 1 WHERE email = ?")
                .bind(email)
                .execute(&mut *tx)
                .await?;
            LoginCodeCheck::Invalid
        };
        tx.commit().await?;
        Ok(result)
    }

    // ---- devices and keys --------------------------------------------------

    /// Signs the user in on a new phone. The old phone's token and keys stop
    /// working, and anything queued for the old phone is dropped because the
    /// new phone could not decrypt it anyway.
    pub async fn replace_device(&self, user_id: &str, token_hash: &[u8]) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        let last_seen: Option<i64> =
            sqlx::query_scalar("SELECT last_seen_at FROM devices WHERE user_id = ?")
                .bind(user_id)
                .fetch_optional(&mut *tx)
                .await?
                .flatten();
        sqlx::query("DELETE FROM devices WHERE user_id = ?")
            .bind(user_id)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM one_time_keys WHERE user_id = ?")
            .bind(user_id)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM queue WHERE recipient_id = ?")
            .bind(user_id)
            .execute(&mut *tx)
            .await?;
        sqlx::query("INSERT INTO devices (user_id, token_hash, last_seen_at, created_at) VALUES (?, ?, ?, ?)")
            .bind(user_id)
            .bind(token_hash)
            .bind(last_seen)
            .bind(now_ms())
            .execute(&mut *tx)
            .await?;
        tx.commit().await
    }

    pub async fn user_id_for_token(&self, token_hash: &[u8]) -> Result<Option<String>> {
        sqlx::query_scalar("SELECT user_id FROM devices WHERE token_hash = ?")
            .bind(token_hash)
            .fetch_optional(&self.pool)
            .await
    }

    pub async fn sign_out(&self, user_id: &str) -> Result<()> {
        sqlx::query("DELETE FROM devices WHERE user_id = ?")
            .bind(user_id)
            .execute(&self.pool)
            .await?;
        sqlx::query("DELETE FROM one_time_keys WHERE user_id = ?")
            .bind(user_id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn set_identity(
        &self,
        user_id: &str,
        identity: &IdentityBundle,
    ) -> Result<IdentityUpdate> {
        let current = self.identity(user_id).await?;
        match current {
            Some(existing) if existing == *identity => Ok(IdentityUpdate::Unchanged),
            Some(_) => Ok(IdentityUpdate::Conflict),
            None => {
                sqlx::query(
                    "UPDATE devices SET curve25519 = ?, ed25519 = ?, curve25519_signature = ? WHERE user_id = ?",
                )
                .bind(&identity.curve25519)
                .bind(&identity.ed25519)
                .bind(&identity.curve25519_signature)
                .bind(user_id)
                .execute(&self.pool)
                .await?;
                Ok(IdentityUpdate::Set)
            }
        }
    }

    pub async fn identity(&self, user_id: &str) -> Result<Option<IdentityBundle>> {
        let row: Option<(Option<String>, Option<String>, Option<String>)> = sqlx::query_as(
            "SELECT curve25519, ed25519, curve25519_signature FROM devices WHERE user_id = ?",
        )
        .bind(user_id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(match row {
            Some((Some(curve25519), Some(ed25519), Some(curve25519_signature))) => {
                Some(IdentityBundle {
                    curve25519,
                    ed25519,
                    curve25519_signature,
                })
            }
            _ => None,
        })
    }

    pub async fn add_one_time_keys(&self, user_id: &str, keys: &[SignedOneTimeKey]) -> Result<i64> {
        let mut tx = self.pool.begin().await?;
        let mut count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM one_time_keys WHERE user_id = ?")
                .bind(user_id)
                .fetch_one(&mut *tx)
                .await?;
        for key in keys {
            if count >= MAX_ONE_TIME_KEYS {
                break;
            }
            let inserted = sqlx::query(
                "INSERT OR IGNORE INTO one_time_keys (user_id, key_id, key, signature) VALUES (?, ?, ?, ?)",
            )
            .bind(user_id)
            .bind(&key.key_id)
            .bind(&key.key)
            .bind(&key.signature)
            .execute(&mut *tx)
            .await?;
            count += inserted.rows_affected() as i64;
        }
        tx.commit().await?;
        Ok(count)
    }

    pub async fn one_time_key_count(&self, user_id: &str) -> Result<i64> {
        sqlx::query_scalar("SELECT COUNT(*) FROM one_time_keys WHERE user_id = ?")
            .bind(user_id)
            .fetch_one(&self.pool)
            .await
    }

    /// Hands out one of the user's one-time keys, exactly once.
    pub async fn claim_one_time_key(&self, user_id: &str) -> Result<Option<SignedOneTimeKey>> {
        let row: Option<(String, String, String)> = sqlx::query_as(
            "DELETE FROM one_time_keys WHERE rowid =
               (SELECT rowid FROM one_time_keys WHERE user_id = ? ORDER BY rowid LIMIT 1)
             RETURNING key_id, key, signature",
        )
        .bind(user_id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(|(key_id, key, signature)| SignedOneTimeKey {
            key_id,
            key,
            signature,
        }))
    }

    pub async fn set_push_token(&self, user_id: &str, token: Option<&str>) -> Result<()> {
        sqlx::query("UPDATE devices SET push_token = ? WHERE user_id = ?")
            .bind(token)
            .bind(user_id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn push_token(&self, user_id: &str) -> Result<Option<String>> {
        Ok(
            sqlx::query_scalar("SELECT push_token FROM devices WHERE user_id = ?")
                .bind(user_id)
                .fetch_optional(&self.pool)
                .await?
                .flatten(),
        )
    }

    /// Forgets a push token the push service reported as dead.
    pub async fn clear_push_token(&self, token: &str) -> Result<()> {
        sqlx::query("UPDATE devices SET push_token = NULL WHERE push_token = ?")
            .bind(token)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn set_last_seen(&self, user_id: &str, at_ms: i64) -> Result<()> {
        sqlx::query("UPDATE devices SET last_seen_at = ? WHERE user_id = ?")
            .bind(at_ms)
            .bind(user_id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn last_seen(&self, user_id: &str) -> Result<Option<i64>> {
        Ok(
            sqlx::query_scalar("SELECT last_seen_at FROM devices WHERE user_id = ?")
                .bind(user_id)
                .fetch_optional(&self.pool)
                .await?
                .flatten(),
        )
    }

    // ---- invites and pairing -----------------------------------------------

    pub async fn put_invite(&self, invite: &Invite) -> Result<()> {
        sqlx::query(
            "INSERT INTO invites (code, inviter_id, partner_email, expires_at) VALUES (?, ?, ?, ?)
             ON CONFLICT (inviter_id) DO UPDATE SET code = excluded.code,
               partner_email = excluded.partner_email, expires_at = excluded.expires_at",
        )
        .bind(&invite.code)
        .bind(&invite.inviter_id)
        .bind(&invite.partner_email)
        .bind(invite.expires_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn invite_by_inviter(&self, inviter_id: &str) -> Result<Option<Invite>> {
        self.invite_where("inviter_id", inviter_id).await
    }

    pub async fn invite_by_code(&self, code: &str) -> Result<Option<Invite>> {
        self.invite_where("code", code).await
    }

    async fn invite_where(&self, column: &'static str, value: &str) -> Result<Option<Invite>> {
        let sql = match column {
            "code" => {
                "SELECT code, inviter_id, partner_email, expires_at FROM invites WHERE code = ?"
            }
            _ => {
                "SELECT code, inviter_id, partner_email, expires_at FROM invites WHERE inviter_id = ?"
            }
        };
        let row: Option<(String, String, Option<String>, i64)> = sqlx::query_as(sql)
            .bind(value)
            .fetch_optional(&self.pool)
            .await?;
        Ok(
            row.map(|(code, inviter_id, partner_email, expires_at)| Invite {
                code,
                inviter_id,
                partner_email,
                expires_at,
            }),
        )
    }

    pub async fn delete_invite(&self, inviter_id: &str) -> Result<()> {
        sqlx::query("DELETE FROM invites WHERE inviter_id = ?")
            .bind(inviter_id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn pair(&self, a: &str, b: &str) -> Result<PairResult> {
        let mut tx = self.pool.begin().await?;
        let already: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM users WHERE id IN (?, ?) AND partner_id IS NOT NULL",
        )
        .bind(a)
        .bind(b)
        .fetch_one(&mut *tx)
        .await?;
        if already > 0 {
            return Ok(PairResult::AlreadyPaired);
        }
        let now = now_ms();
        for (me, partner) in [(a, b), (b, a)] {
            sqlx::query("UPDATE users SET partner_id = ?, paired_at = ? WHERE id = ?")
                .bind(partner)
                .bind(now)
                .bind(me)
                .execute(&mut *tx)
                .await?;
        }
        sqlx::query("DELETE FROM invites WHERE inviter_id IN (?, ?)")
            .bind(a)
            .bind(b)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        Ok(PairResult::Paired)
    }

    /// Ends the pairing for both partners and deletes everything still queued
    /// between them. Returns the former partner.
    pub async fn unpair(&self, user_id: &str) -> Result<Option<String>> {
        let mut tx = self.pool.begin().await?;
        let partner: Option<String> =
            sqlx::query_scalar("SELECT partner_id FROM users WHERE id = ?")
                .bind(user_id)
                .fetch_optional(&mut *tx)
                .await?
                .flatten();
        if let Some(partner) = &partner {
            sqlx::query("UPDATE users SET partner_id = NULL, paired_at = NULL WHERE id IN (?, ?)")
                .bind(user_id)
                .bind(partner)
                .execute(&mut *tx)
                .await?;
            sqlx::query(
                "DELETE FROM queue WHERE (sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?)",
            )
            .bind(user_id)
            .bind(partner)
            .bind(partner)
            .bind(user_id)
            .execute(&mut *tx)
            .await?;
        }
        tx.commit().await?;
        Ok(partner)
    }

    // ---- the envelope queue --------------------------------------------------

    /// Stores a message for the recipient. Resending the same id returns the
    /// original envelope instead of storing it twice.
    pub async fn enqueue_message(
        &self,
        sender: &str,
        recipient: &str,
        id: &str,
        message_type: i32,
        body: &str,
    ) -> Result<(Envelope, bool)> {
        let now = now_ms();
        let inserted: Option<i64> = sqlx::query_scalar(
            "INSERT INTO queue (recipient_id, sender_id, kind, client_id, message_type, body, created_at)
             VALUES (?, ?, 'message', ?, ?, ?, ?)
             ON CONFLICT DO NOTHING RETURNING seq",
        )
        .bind(recipient)
        .bind(sender)
        .bind(id)
        .bind(message_type)
        .bind(body)
        .bind(now)
        .fetch_optional(&self.pool)
        .await?;
        if let Some(seq) = inserted {
            return Ok((
                envelope(
                    seq,
                    id,
                    EnvelopeKind::Message,
                    Some(message_type),
                    Some(body),
                    now,
                ),
                true,
            ));
        }
        let (seq, created_at): (i64, i64) = sqlx::query_as(
            "SELECT seq, created_at FROM queue
             WHERE sender_id = ? AND recipient_id = ? AND kind = 'message' AND client_id = ?",
        )
        .bind(sender)
        .bind(recipient)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok((
            envelope(
                seq,
                id,
                EnvelopeKind::Message,
                Some(message_type),
                Some(body),
                created_at,
            ),
            false,
        ))
    }

    /// Keeps only the newest status from `sender` for `recipient`.
    pub async fn replace_status(
        &self,
        sender: &str,
        recipient: &str,
        id: &str,
        message_type: i32,
        body: &str,
    ) -> Result<Envelope> {
        let now = now_ms();
        let mut tx = self.pool.begin().await?;
        sqlx::query(
            "DELETE FROM queue WHERE sender_id = ? AND recipient_id = ? AND kind = 'status'",
        )
        .bind(sender)
        .bind(recipient)
        .execute(&mut *tx)
        .await?;
        let seq: i64 = sqlx::query_scalar(
            "INSERT INTO queue (recipient_id, sender_id, kind, client_id, message_type, body, created_at)
             VALUES (?, ?, 'status', ?, ?, ?, ?) RETURNING seq",
        )
        .bind(recipient)
        .bind(sender)
        .bind(id)
        .bind(message_type)
        .bind(body)
        .bind(now)
        .fetch_one(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(envelope(
            seq,
            id,
            EnvelopeKind::Status,
            Some(message_type),
            Some(body),
            now,
        ))
    }

    pub async fn pending(
        &self,
        recipient: &str,
        after_seq: i64,
        limit: i64,
    ) -> Result<Vec<Envelope>> {
        let rows: Vec<QueueRow> = sqlx::query_as(
            "SELECT seq, client_id, kind, message_type, body, created_at FROM queue
             WHERE recipient_id = ? AND seq > ? AND acked_at IS NULL
               AND (kind != 'call' OR created_at > ?)
             ORDER BY seq LIMIT ?",
        )
        .bind(recipient)
        .bind(after_seq)
        .bind(now_ms() - CALL_SIGNAL_TTL_MS)
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows
            .into_iter()
            .map(|(seq, id, kind, message_type, body, at_ms)| {
                let kind = match kind.as_str() {
                    "status" => EnvelopeKind::Status,
                    "delivered" => EnvelopeKind::Delivered,
                    "call" => EnvelopeKind::Call,
                    _ => EnvelopeKind::Message,
                };
                Envelope {
                    seq: Some(seq),
                    id,
                    kind,
                    message_type,
                    body,
                    at_ms,
                }
            })
            .collect())
    }

    /// Handles envelopes the phone has safely stored. For every message that
    /// reached the recipient's phone, queues a delivery receipt for its sender
    /// and returns `(sender, receipt)` pairs so live senders can be told right
    /// away.
    pub async fn ack(&self, recipient: &str, seqs: &[i64]) -> Result<Vec<(String, Envelope)>> {
        let now = now_ms();
        let mut receipts = Vec::new();
        let mut tx = self.pool.begin().await?;
        for seq in seqs {
            // Messages keep a body-less row so a resend is recognised later.
            let message: Option<(String, String)> = sqlx::query_as(
                "UPDATE queue SET acked_at = ?, body = NULL
                 WHERE seq = ? AND recipient_id = ? AND kind = 'message' AND acked_at IS NULL
                 RETURNING sender_id, client_id",
            )
            .bind(now)
            .bind(seq)
            .bind(recipient)
            .fetch_optional(&mut *tx)
            .await?;
            let Some((sender, client_id)) = message else {
                sqlx::query(
                    "DELETE FROM queue WHERE seq = ? AND recipient_id = ? AND kind != 'message'",
                )
                .bind(seq)
                .bind(recipient)
                .execute(&mut *tx)
                .await?;
                continue;
            };
            let receipt_seq: Option<i64> = sqlx::query_scalar(
                "INSERT INTO queue (recipient_id, sender_id, kind, client_id, created_at)
                 VALUES (?, ?, 'delivered', ?, ?) ON CONFLICT DO NOTHING RETURNING seq",
            )
            .bind(&sender)
            .bind(recipient)
            .bind(&client_id)
            .bind(now)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some(receipt_seq) = receipt_seq {
                receipts.push((
                    sender,
                    envelope(
                        receipt_seq,
                        &client_id,
                        EnvelopeKind::Delivered,
                        None,
                        None,
                        now,
                    ),
                ));
            }
        }
        tx.commit().await?;
        Ok(receipts)
    }

    /// Stores one piece of call signalling for the partner.
    pub async fn enqueue_call(
        &self,
        sender: &str,
        recipient: &str,
        id: &str,
        message_type: i32,
        body: &str,
    ) -> Result<Envelope> {
        let now = now_ms();
        let seq: i64 = sqlx::query_scalar(
            "INSERT INTO queue (recipient_id, sender_id, kind, client_id, message_type, body, created_at)
             VALUES (?, ?, 'call', ?, ?, ?, ?)
             ON CONFLICT (sender_id, recipient_id, kind, client_id)
             DO UPDATE SET body = excluded.body RETURNING seq",
        )
        .bind(recipient)
        .bind(sender)
        .bind(id)
        .bind(message_type)
        .bind(body)
        .bind(now)
        .fetch_one(&self.pool)
        .await?;
        Ok(envelope(
            seq,
            id,
            EnvelopeKind::Call,
            Some(message_type),
            Some(body),
            now,
        ))
    }

    /// Call signalling nobody picked up in time is useless; drop it.
    pub async fn purge_stale_calls(&self) -> Result<u64> {
        let done = sqlx::query("DELETE FROM queue WHERE kind = 'call' AND created_at < ?")
            .bind(now_ms() - CALL_SIGNAL_TTL_MS)
            .execute(&self.pool)
            .await?;
        Ok(done.rows_affected())
    }

    // ---- attachments -------------------------------------------------------------

    pub async fn add_blob(&self, id: &str, owner: &str, size: u64) -> Result<()> {
        sqlx::query("INSERT INTO blobs (id, owner_id, size, created_at) VALUES (?, ?, ?, ?)")
            .bind(id)
            .bind(owner)
            .bind(size as i64)
            .bind(now_ms())
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn blob_owner(&self, id: &str) -> Result<Option<String>> {
        sqlx::query_scalar("SELECT owner_id FROM blobs WHERE id = ?")
            .bind(id)
            .fetch_optional(&self.pool)
            .await
    }

    /// Bytes this user has waiting on the server.
    pub async fn blob_bytes(&self, owner: &str) -> Result<u64> {
        let total: i64 =
            sqlx::query_scalar("SELECT COALESCE(SUM(size), 0) FROM blobs WHERE owner_id = ?")
                .bind(owner)
                .fetch_one(&self.pool)
                .await?;
        Ok(total.max(0) as u64)
    }

    pub async fn delete_blob(&self, id: &str) -> Result<bool> {
        let done = sqlx::query("DELETE FROM blobs WHERE id = ?")
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(done.rows_affected() > 0)
    }

    /// Forgets every attachment either of these users uploaded and returns
    /// their ids, so the files can be removed too.
    pub async fn take_blobs_of(&self, users: &[&str]) -> Result<Vec<String>> {
        let mut ids = Vec::new();
        for user in users {
            let taken: Vec<String> =
                sqlx::query_scalar("DELETE FROM blobs WHERE owner_id = ? RETURNING id")
                    .bind(user)
                    .fetch_all(&self.pool)
                    .await?;
            ids.extend(taken);
        }
        Ok(ids)
    }

    /// Forgets attachments older than `max_age_ms` and returns their ids.
    pub async fn take_expired_blobs(&self, max_age_ms: i64) -> Result<Vec<String>> {
        sqlx::query_scalar("DELETE FROM blobs WHERE created_at < ? RETURNING id")
            .bind(now_ms() - max_age_ms)
            .fetch_all(&self.pool)
            .await
    }

    /// Forgets delivered messages older than `max_age_ms`.
    pub async fn purge_delivered(&self, max_age_ms: i64) -> Result<u64> {
        let done = sqlx::query("DELETE FROM queue WHERE acked_at IS NOT NULL AND acked_at < ?")
            .bind(now_ms() - max_age_ms)
            .execute(&self.pool)
            .await?;
        Ok(done.rows_affected())
    }
}

fn envelope(
    seq: i64,
    id: &str,
    kind: EnvelopeKind,
    message_type: Option<i32>,
    body: Option<&str>,
    at_ms: i64,
) -> Envelope {
    Envelope {
        seq: Some(seq),
        id: id.to_owned(),
        kind,
        message_type,
        body: body.map(str::to_owned),
        at_ms,
    }
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}
