//! JSON shapes shared by the REST API and the WebSocket.
//!
//! The server never sees message contents: `body` is always Olm ciphertext
//! produced by `mami-core` on the phone.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct IdentityBundle {
    pub curve25519: String,
    pub ed25519: String,
    pub curve25519_signature: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SignedOneTimeKey {
    pub key_id: String,
    pub key: String,
    pub signature: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SendKind {
    /// Stored until the partner's phone acknowledges it, then a delivery
    /// receipt goes back to the sender.
    Message,
    /// Device status. Only the newest one is kept.
    Status,
    /// Typing indicators. Forwarded only if the partner is online right now.
    Ephemeral,
    /// Call signalling. Kept for a minute at most (a call nobody picks up in
    /// time is over), and with `push` it rings the partner's phone right away.
    Call,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SendRequest {
    /// Chosen by the sender. Resending the same id is safe.
    pub id: String,
    pub kind: SendKind,
    pub message_type: i32,
    pub body: String,
    /// Wake the partner's phone with a push notification if the app is closed.
    #[serde(default)]
    pub push: bool,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EnvelopeKind {
    Message,
    Status,
    Ephemeral,
    /// Your message `id` reached your partner's phone at `at_ms`.
    Delivered,
    Call,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Envelope {
    /// Acknowledge this to remove the envelope from the server. Absent for
    /// ephemeral envelopes, which are never stored.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seq: Option<i64>,
    pub id: String,
    pub kind: EnvelopeKind,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message_type: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,
    /// When the server received the envelope (or, for a receipt, when it was
    /// delivered).
    pub at_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Accepted {
    pub id: String,
    pub at_ms: i64,
}

/// One STUN or TURN server for WebRTC, in the browser's `RTCIceServer` shape.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct IceServer {
    pub urls: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub username: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub credential: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct IceServers {
    pub ice_servers: Vec<IceServer>,
    /// How long the TURN credentials stay valid.
    pub ttl_s: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct BlobCreated {
    pub id: String,
    pub size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PartnerPresence {
    pub online: bool,
    pub last_seen_ms: Option<i64>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum PairingEvent {
    Paired,
    Unpaired,
    /// The partner signed in on a new phone. Fetch their new keys.
    KeysChanged,
}

/// Frames the phone sends over the WebSocket.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientFrame {
    Send(SendRequest),
    Ack { seqs: Vec<i64> },
}

/// Frames the server sends over the WebSocket.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerFrame {
    /// First frame after connecting.
    Ready {
        partner: Option<PartnerPresence>,
    },
    Accepted(Accepted),
    Rejected {
        id: String,
        reason: String,
    },
    Envelope(Envelope),
    Presence(PartnerPresence),
    Pairing {
        event: PairingEvent,
    },
}
