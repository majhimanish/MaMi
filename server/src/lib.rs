//! MaMi relay server.
//!
//! Responsibilities:
//! * email sign-in with one-time codes,
//! * pairing two accounts with an invite code,
//! * a directory of *public* keys so phones can start encrypted sessions,
//! * store-and-forward of encrypted envelopes, delivery receipts, presence,
//! * waking phones through push notifications that carry no content.
//!
//! It never has the keys to read a message.

pub mod api;
pub mod error;
pub mod hub;
pub mod mail;
pub mod protocol;
pub mod push;
pub mod store;
mod ws;

use std::sync::Arc;

use hub::Hub;
use mail::Mailer;
use push::Pusher;
use store::Store;

pub struct AppState {
    pub store: Store,
    pub hub: Hub,
    pub mailer: Mailer,
    pub pusher: Pusher,
}

impl AppState {
    pub fn new(store: Store, mailer: Mailer, pusher: Pusher) -> Arc<Self> {
        Arc::new(Self {
            store,
            hub: Hub::default(),
            mailer,
            pusher,
        })
    }
}

pub use api::router;
