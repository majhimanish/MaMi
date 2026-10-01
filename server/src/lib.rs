//! MaMi relay server.
//!
//! Responsibilities:
//! * email sign-in with one-time codes,
//! * pairing two accounts with an invite code,
//! * a directory of *public* keys so phones can start encrypted sessions,
//! * store-and-forward of encrypted envelopes, delivery receipts, presence,
//! * encrypted attachments (photos, videos, voice notes, files) until the
//!   partner has downloaded them,
//! * call signalling and short-lived TURN credentials for voice and video
//!   calls,
//! * waking phones through push notifications that carry no content.
//!
//! It never has the keys to read a message.

pub mod api;
pub mod blobs;
pub mod error;
pub mod hub;
pub mod mail;
pub mod protocol;
pub mod push;
pub mod store;
mod ws;

use std::sync::Arc;

use blobs::BlobStore;
use hub::Hub;
use mail::Mailer;
use push::Pusher;
use store::Store;

pub struct AppState {
    pub store: Store,
    pub hub: Hub,
    pub mailer: Mailer,
    pub pusher: Pusher,
    pub blobs: BlobStore,
    pub config: Config,
}

impl AppState {
    pub fn new(
        store: Store,
        mailer: Mailer,
        pusher: Pusher,
        blobs: BlobStore,
        config: Config,
    ) -> Arc<Self> {
        Arc::new(Self {
            store,
            hub: Hub::default(),
            mailer,
            pusher,
            blobs,
            config,
        })
    }
}

/// Limits and call servers. `main.rs` reads them from the environment.
#[derive(Debug, Clone)]
pub struct Config {
    /// Largest single attachment.
    pub max_blob_bytes: u64,
    /// Most attachment bytes one person may have waiting on the server.
    pub blob_quota_bytes: u64,
    /// STUN servers let phones find their public address for calls.
    pub stun_urls: Vec<String>,
    /// TURN relays calls when the two phones can't reach each other directly.
    pub turn: Option<Turn>,
}

#[derive(Debug, Clone)]
pub struct Turn {
    pub urls: Vec<String>,
    /// coturn's `static-auth-secret`.
    pub secret: String,
    pub ttl_s: i64,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            max_blob_bytes: 100 * 1024 * 1024,
            blob_quota_bytes: 2 * 1024 * 1024 * 1024,
            stun_urls: vec!["stun:stun.l.google.com:19302".into()],
            turn: None,
        }
    }
}

pub use api::router;
