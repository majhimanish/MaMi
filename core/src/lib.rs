//! MaMi core: the part of the app that must behave identically on Android and
//! iOS.
//!
//! * [`crypto`]: device keys and the Olm (Double Ratchet) session between the
//!   two partners, built on the audited `vodozemac` library.
//! * [`payload`]: everything the two phones say to each other. Payloads are
//!   serialised, padded and encrypted here, so the server only ever sees
//!   opaque ciphertext.
//! * [`attachment`]: encrypts photos, videos, voice notes and files before
//!   they are uploaded, in chunks so big files never have to fit in memory.
//! * [`insight`]: turns the partner's last known device status into
//!   plain-language hints ("phone probably died", "probably asleep").
//!
//! The public API is exported to Kotlin and Swift with UniFFI.

pub mod attachment;
pub mod crypto;
pub mod insight;
pub mod payload;

pub use attachment::*;
pub use crypto::*;
pub use insight::*;
pub use payload::*;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("invalid key: {reason}")]
    InvalidKey { reason: String },
    #[error("signature check failed: {reason}")]
    BadSignature { reason: String },
    #[error("could not create session: {reason}")]
    SessionCreation { reason: String },
    #[error("could not decrypt message: {reason}")]
    Decryption { reason: String },
    #[error("could not encrypt message: {reason}")]
    Encryption { reason: String },
    #[error("malformed message: {reason}")]
    Malformed { reason: String },
    #[error("could not restore saved state: {reason}")]
    Pickle { reason: String },
    #[error("could not read or write a file: {reason}")]
    Io { reason: String },
}

impl CoreError {
    pub(crate) fn invalid_key(e: impl std::fmt::Display) -> Self {
        Self::InvalidKey {
            reason: e.to_string(),
        }
    }
    pub(crate) fn malformed(e: impl std::fmt::Display) -> Self {
        Self::Malformed {
            reason: e.to_string(),
        }
    }
    pub(crate) fn encryption(e: impl std::fmt::Display) -> Self {
        Self::Encryption {
            reason: e.to_string(),
        }
    }
    pub(crate) fn decryption(e: impl std::fmt::Display) -> Self {
        Self::Decryption {
            reason: e.to_string(),
        }
    }
    pub(crate) fn io(e: impl std::fmt::Display) -> Self {
        Self::Io {
            reason: e.to_string(),
        }
    }
}
