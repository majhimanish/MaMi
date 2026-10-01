-- Live location joins the queue (newest only, like status). Rebuilt the same
-- way as in 0002, keeping sequence numbers counting.
CREATE TABLE queue_new (
    seq          INTEGER PRIMARY KEY AUTOINCREMENT,
    recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind         TEXT NOT NULL CHECK (kind IN ('message', 'status', 'delivered', 'call', 'location')),
    client_id    TEXT NOT NULL,
    message_type INTEGER,
    body         TEXT,
    created_at   INTEGER NOT NULL,
    acked_at     INTEGER
);
INSERT INTO queue_new (seq, recipient_id, sender_id, kind, client_id, message_type, body, created_at, acked_at)
    SELECT seq, recipient_id, sender_id, kind, client_id, message_type, body, created_at, acked_at FROM queue;
DELETE FROM sqlite_sequence WHERE name = 'queue_new';
INSERT INTO sqlite_sequence (name, seq)
    SELECT 'queue_new', seq FROM sqlite_sequence WHERE name = 'queue';
DROP TABLE queue;
ALTER TABLE queue_new RENAME TO queue;
CREATE INDEX queue_by_recipient ON queue (recipient_id, seq);
CREATE UNIQUE INDEX queue_dedupe ON queue (sender_id, recipient_id, kind, client_id);
