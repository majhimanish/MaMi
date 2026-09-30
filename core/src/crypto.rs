//! Device keys and the end-to-end encrypted session between two partners.
//!
//! Every phone creates its own [`CryptoAccount`]; the private keys never
//! leave the device. Pairing works like this:
//!
//! 1. Both phones upload their public [`IdentityBundle`] and a batch of
//!    [`SignedOneTimeKey`]s to the server.
//! 2. The partner who *joins* an invite claims one of the inviter's one-time
//!    keys and calls [`CryptoAccount::start_session`]. Its first encrypted
//!    message (a `Hello`) is a pre-key message.
//! 3. The inviter receives that message and calls
//!    [`CryptoAccount::accept_session`]. From then on both sides share one
//!    Double Ratchet [`CryptoSession`].
//!
//! The server can swap keys in transit, which is why the partners should
//! compare the [`safety_code`] once, in person.

use std::sync::{Arc, Mutex, MutexGuard};

use sha2::{Digest, Sha512};
use vodozemac::olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle};
use vodozemac::{
    Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature, base64_decode, base64_encode,
};
use zeroize::Zeroizing;

use crate::CoreError;
use crate::payload::{Payload, open, seal};

/// A device's public identity: the keys the partner needs to start a session.
#[derive(uniffi::Record, Clone, Debug, PartialEq, Eq)]
pub struct IdentityBundle {
    /// Curve25519 key used for the Diffie-Hellman handshake (unpadded base64).
    pub curve25519: String,
    /// Ed25519 key used for signatures and for the safety code (unpadded base64).
    pub ed25519: String,
    /// Ed25519 signature over `curve25519`, binding the two keys together.
    pub curve25519_signature: String,
}

/// A public one-time key, signed with the device's Ed25519 identity key.
#[derive(uniffi::Record, Clone, Debug, PartialEq, Eq)]
pub struct SignedOneTimeKey {
    pub key_id: String,
    pub key: String,
    pub signature: String,
}

/// An encrypted message as it travels through the server.
#[derive(uniffi::Record, Clone, Debug, PartialEq, Eq)]
pub struct Ciphertext {
    /// 0 = pre-key message (can start a session), 1 = normal message.
    pub message_type: i32,
    /// Unpadded base64 of the Olm message.
    pub body: String,
}

/// Result of accepting the partner's first message.
#[derive(uniffi::Record)]
pub struct AcceptedSession {
    pub session: Arc<CryptoSession>,
    pub payload: Payload,
}

/// Holds this device's long-term identity keys and one-time keys.
#[derive(uniffi::Object)]
pub struct CryptoAccount {
    inner: Mutex<Account>,
}

#[uniffi::export]
impl CryptoAccount {
    /// Creates a brand-new device identity.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(Account::new()),
        })
    }

    /// Restores an account saved with [`CryptoAccount::pickle`].
    #[uniffi::constructor]
    pub fn restore(pickle: String, pickle_key: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        let key = pickle_key_from(&pickle_key)?;
        let pickle =
            AccountPickle::from_encrypted(&pickle, &key).map_err(|e| CoreError::Pickle {
                reason: e.to_string(),
            })?;
        Ok(Arc::new(Self {
            inner: Mutex::new(Account::from_pickle(pickle)),
        }))
    }

    /// Serialises the account, encrypted with a 32-byte `pickle_key`, so it can
    /// be stored on disk. Save it again after every call that changes it.
    pub fn pickle(&self, pickle_key: Vec<u8>) -> Result<String, CoreError> {
        let key = pickle_key_from(&pickle_key)?;
        Ok(self.lock().pickle().encrypt(&key))
    }

    pub fn identity(&self) -> IdentityBundle {
        let account = self.lock();
        let keys = account.identity_keys();
        let curve25519 = keys.curve25519.to_base64();
        let curve25519_signature = account.sign(curve25519.as_bytes()).to_base64();
        IdentityBundle {
            curve25519,
            ed25519: keys.ed25519.to_base64(),
            curve25519_signature,
        }
    }

    /// Generates `count` fresh one-time keys and returns every key that has
    /// not been uploaded yet. Call [`CryptoAccount::mark_keys_published`] once
    /// the server has stored them.
    pub fn unpublished_one_time_keys(&self, count: i32) -> Vec<SignedOneTimeKey> {
        let mut account = self.lock();
        if count > 0 {
            account.generate_one_time_keys(count.unsigned_abs() as usize);
        }
        let mut keys: Vec<_> = account
            .one_time_keys()
            .into_iter()
            .map(|(id, key)| {
                let key = key.to_base64();
                let signature = account.sign(key.as_bytes()).to_base64();
                SignedOneTimeKey {
                    key_id: id.to_base64(),
                    key,
                    signature,
                }
            })
            .collect();
        keys.sort_by(|a, b| a.key_id.cmp(&b.key_id));
        keys
    }

    pub fn mark_keys_published(&self) {
        self.lock().mark_keys_as_published();
    }

    /// Starts a session with the partner using one of their one-time keys.
    /// Checks both of the partner's signatures first.
    pub fn start_session(
        &self,
        partner: IdentityBundle,
        one_time_key: SignedOneTimeKey,
    ) -> Result<Arc<CryptoSession>, CoreError> {
        let (curve, ed) = verified_keys(&partner)?;
        verify(&ed, &one_time_key.key, &one_time_key.signature)?;
        let otk =
            Curve25519PublicKey::from_base64(&one_time_key.key).map_err(CoreError::invalid_key)?;
        let session = self
            .lock()
            .create_outbound_session(SessionConfig::version_1(), curve, otk)
            .map_err(|e| CoreError::SessionCreation {
                reason: e.to_string(),
            })?;
        Ok(CryptoSession::wrap(session))
    }

    /// Creates the inbound session from the partner's first (pre-key)
    /// message and decrypts it. Uses up the one-time key it was built on, so
    /// save the account afterwards.
    pub fn accept_session(
        &self,
        partner: IdentityBundle,
        ciphertext: Ciphertext,
    ) -> Result<AcceptedSession, CoreError> {
        let (curve, _) = verified_keys(&partner)?;
        let OlmMessage::PreKey(message) = decode_message(&ciphertext)? else {
            return Err(CoreError::SessionCreation {
                reason: "a session can only be started by a pre-key message".into(),
            });
        };
        let result = self
            .lock()
            .create_inbound_session(SessionConfig::version_1(), curve, &message)
            .map_err(|e| CoreError::SessionCreation {
                reason: e.to_string(),
            })?;
        let payload = open(&result.plaintext)?;
        Ok(AcceptedSession {
            session: CryptoSession::wrap(result.session),
            payload,
        })
    }
}

impl CryptoAccount {
    fn lock(&self) -> MutexGuard<'_, Account> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// The Double Ratchet session shared by the two partners.
#[derive(uniffi::Object)]
pub struct CryptoSession {
    inner: Mutex<Session>,
}

#[uniffi::export]
impl CryptoSession {
    #[uniffi::constructor]
    pub fn restore(pickle: String, pickle_key: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        let key = pickle_key_from(&pickle_key)?;
        let pickle =
            SessionPickle::from_encrypted(&pickle, &key).map_err(|e| CoreError::Pickle {
                reason: e.to_string(),
            })?;
        Ok(Self::wrap(Session::from_pickle(pickle)))
    }

    /// Save again after every encrypt or decrypt: the ratchet moves forward
    /// each time, and old keys are deleted on purpose.
    pub fn pickle(&self, pickle_key: Vec<u8>) -> Result<String, CoreError> {
        let key = pickle_key_from(&pickle_key)?;
        Ok(self.lock().pickle().encrypt(&key))
    }

    pub fn session_id(&self) -> String {
        self.lock().session_id()
    }

    /// True once the partner has answered on this session. Until then every
    /// outgoing message is a pre-key message.
    pub fn is_confirmed(&self) -> bool {
        self.lock().has_received_message()
    }

    pub fn encrypt(&self, payload: Payload) -> Result<Ciphertext, CoreError> {
        let plaintext = Zeroizing::new(seal(&payload)?);
        let message =
            self.lock()
                .encrypt(plaintext.as_slice())
                .map_err(|e| CoreError::Encryption {
                    reason: e.to_string(),
                })?;
        let (message_type, bytes) = message.to_parts();
        Ok(Ciphertext {
            message_type: message_type as i32,
            body: base64_encode(bytes),
        })
    }

    pub fn decrypt(&self, ciphertext: Ciphertext) -> Result<Payload, CoreError> {
        let message = decode_message(&ciphertext)?;
        let plaintext =
            Zeroizing::new(
                self.lock()
                    .decrypt(&message)
                    .map_err(|e| CoreError::Decryption {
                        reason: e.to_string(),
                    })?,
            );
        open(&plaintext)
    }

    /// For a pre-key message: does it belong to this session? If not, the
    /// partner has started a new session (for example after reinstalling) and
    /// [`CryptoAccount::accept_session`] should be used instead.
    pub fn owns_prekey_message(&self, ciphertext: Ciphertext) -> bool {
        match decode_message(&ciphertext) {
            Ok(OlmMessage::PreKey(message)) => message.session_id() == self.lock().session_id(),
            _ => false,
        }
    }
}

impl CryptoSession {
    fn wrap(session: Session) -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(session),
        })
    }

    fn lock(&self) -> MutexGuard<'_, Session> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// Checks that the partner's Curve25519 key really belongs to their Ed25519
/// key. Call this before showing or trusting a partner's keys.
#[uniffi::export]
pub fn verify_identity(identity: IdentityBundle) -> Result<(), CoreError> {
    verified_keys(&identity).map(|_| ())
}

/// A short code both partners see on their screens. If the codes match, no
/// one (not even the MaMi server) is sitting between the two phones.
///
/// The result is 8 groups of 5 digits and is the same on both phones,
/// whichever order the keys are passed in.
#[uniffi::export]
pub fn safety_code(my_ed25519: String, partner_ed25519: String) -> Result<String, CoreError> {
    let mut keys = [
        Ed25519PublicKey::from_base64(&my_ed25519).map_err(CoreError::invalid_key)?,
        Ed25519PublicKey::from_base64(&partner_ed25519).map_err(CoreError::invalid_key)?,
    ];
    keys.sort_by(|a, b| a.as_bytes().cmp(b.as_bytes()));

    let mut hasher = Sha512::new();
    hasher.update(b"MaMi safety code v1");
    for key in &keys {
        hasher.update(key.as_bytes());
    }
    let digest = hasher.finalize();

    let groups: Vec<String> = digest
        .chunks(5)
        .take(8)
        .map(|chunk| {
            let n = chunk.iter().fold(0u64, |acc, b| (acc << 8) | u64::from(*b));
            format!("{:05}", n % 100_000)
        })
        .collect();
    Ok(groups.join(" "))
}

fn verified_keys(
    identity: &IdentityBundle,
) -> Result<(Curve25519PublicKey, Ed25519PublicKey), CoreError> {
    let ed = Ed25519PublicKey::from_base64(&identity.ed25519).map_err(CoreError::invalid_key)?;
    let curve =
        Curve25519PublicKey::from_base64(&identity.curve25519).map_err(CoreError::invalid_key)?;
    verify(&ed, &identity.curve25519, &identity.curve25519_signature)?;
    Ok((curve, ed))
}

fn verify(key: &Ed25519PublicKey, message: &str, signature: &str) -> Result<(), CoreError> {
    let signature =
        Ed25519Signature::from_base64(signature).map_err(|e| CoreError::BadSignature {
            reason: e.to_string(),
        })?;
    key.verify(message.as_bytes(), &signature)
        .map_err(|e| CoreError::BadSignature {
            reason: e.to_string(),
        })
}

fn decode_message(ciphertext: &Ciphertext) -> Result<OlmMessage, CoreError> {
    let bytes = base64_decode(&ciphertext.body).map_err(CoreError::malformed)?;
    let message_type = usize::try_from(ciphertext.message_type).map_err(CoreError::malformed)?;
    OlmMessage::from_parts(message_type, &bytes).map_err(CoreError::malformed)
}

fn pickle_key_from(bytes: &[u8]) -> Result<Zeroizing<[u8; 32]>, CoreError> {
    let key: [u8; 32] = bytes.try_into().map_err(|_| CoreError::Pickle {
        reason: format!("pickle key must be 32 bytes, got {}", bytes.len()),
    })?;
    Ok(Zeroizing::new(key))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::payload::Payload;

    const KEY: [u8; 32] = [7; 32];

    fn text(id: &str, body: &str) -> Payload {
        Payload::Text {
            id: id.into(),
            body: body.into(),
            sent_at_ms: 1,
            reply_to: None,
        }
    }

    /// Runs the full pairing handshake and returns (joiner, inviter) sessions.
    fn paired() -> (
        Arc<CryptoAccount>,
        Arc<CryptoSession>,
        Arc<CryptoAccount>,
        Arc<CryptoSession>,
    ) {
        let inviter = CryptoAccount::new();
        let joiner = CryptoAccount::new();

        let otks = inviter.unpublished_one_time_keys(5);
        assert_eq!(otks.len(), 5);
        inviter.mark_keys_published();
        assert!(inviter.unpublished_one_time_keys(0).is_empty());

        let joiner_session = joiner
            .start_session(inviter.identity(), otks[2].clone())
            .unwrap();
        let hello = joiner_session
            .encrypt(Payload::Hello {
                display_name: "Maya".into(),
            })
            .unwrap();
        assert_eq!(hello.message_type, 0);

        let accepted = inviter.accept_session(joiner.identity(), hello).unwrap();
        assert_eq!(
            accepted.payload,
            Payload::Hello {
                display_name: "Maya".into()
            }
        );
        assert_eq!(accepted.session.session_id(), joiner_session.session_id());
        (joiner, joiner_session, inviter, accepted.session)
    }

    #[test]
    fn handshake_and_conversation() {
        let (_, joiner, _, inviter) = paired();

        // Until the inviter answers, the joiner keeps sending pre-key messages.
        let early = joiner.encrypt(text("1", "hi")).unwrap();
        assert_eq!(early.message_type, 0);
        assert!(inviter.owns_prekey_message(early.clone()));
        assert_eq!(inviter.decrypt(early).unwrap(), text("1", "hi"));
        assert!(!joiner.is_confirmed());

        let reply = inviter.encrypt(text("2", "hello love")).unwrap();
        assert_eq!(reply.message_type, 1);
        assert_eq!(joiner.decrypt(reply).unwrap(), text("2", "hello love"));
        assert!(joiner.is_confirmed());

        let normal = joiner.encrypt(text("3", "❤️")).unwrap();
        assert_eq!(normal.message_type, 1);
        assert_eq!(inviter.decrypt(normal).unwrap(), text("3", "❤️"));
    }

    #[test]
    fn out_of_order_and_dropped_messages() {
        let (_, joiner, _, inviter) = paired();
        let a = joiner.encrypt(text("a", "one")).unwrap();
        let _dropped = joiner.encrypt(Payload::Typing { active: true }).unwrap();
        let b = joiner.encrypt(text("b", "two")).unwrap();
        assert_eq!(inviter.decrypt(b).unwrap(), text("b", "two"));
        assert_eq!(inviter.decrypt(a).unwrap(), text("a", "one"));
    }

    #[test]
    fn replayed_message_is_rejected() {
        let (_, joiner, _, inviter) = paired();
        let reply = inviter.encrypt(text("1", "x")).unwrap();
        joiner.decrypt(reply.clone()).unwrap();
        assert!(matches!(
            joiner.decrypt(reply),
            Err(CoreError::Decryption { .. })
        ));
    }

    #[test]
    fn pickles_round_trip() {
        let (_, joiner, inviter_account, inviter) = paired();

        let account =
            CryptoAccount::restore(inviter_account.pickle(KEY.to_vec()).unwrap(), KEY.to_vec())
                .unwrap();
        assert_eq!(account.identity(), inviter_account.identity());

        let restored =
            CryptoSession::restore(inviter.pickle(KEY.to_vec()).unwrap(), KEY.to_vec()).unwrap();
        let msg = joiner.encrypt(text("1", "after restart")).unwrap();
        assert_eq!(restored.decrypt(msg).unwrap(), text("1", "after restart"));

        assert!(matches!(
            CryptoSession::restore(inviter.pickle(KEY.to_vec()).unwrap(), vec![1; 32]),
            Err(CoreError::Pickle { .. })
        ));
        assert!(matches!(
            inviter.pickle(vec![1; 16]),
            Err(CoreError::Pickle { .. })
        ));
    }

    #[test]
    fn one_time_key_is_used_up() {
        let inviter = CryptoAccount::new();
        let otk = inviter.unpublished_one_time_keys(1).remove(0);
        inviter.mark_keys_published();

        let first = CryptoAccount::new();
        let s1 = first
            .start_session(inviter.identity(), otk.clone())
            .unwrap();
        inviter
            .accept_session(
                first.identity(),
                s1.encrypt(Payload::Typing { active: false }).unwrap(),
            )
            .unwrap();

        // Someone replaying the same one-time key cannot open a second session.
        let second = CryptoAccount::new();
        let s2 = second.start_session(inviter.identity(), otk).unwrap();
        let attempt = inviter.accept_session(
            second.identity(),
            s2.encrypt(Payload::Typing { active: false }).unwrap(),
        );
        assert!(matches!(attempt, Err(CoreError::SessionCreation { .. })));
    }

    #[test]
    fn partner_restart_is_detected() {
        let (joiner_account, _, inviter_account, inviter) = paired();
        // The joiner reinstalls: new session with a fresh one-time key.
        let otk = inviter_account.unpublished_one_time_keys(1).remove(0);
        let fresh = joiner_account
            .start_session(inviter_account.identity(), otk)
            .unwrap();
        let hello = fresh
            .encrypt(Payload::Hello {
                display_name: "Maya".into(),
            })
            .unwrap();
        assert!(!inviter.owns_prekey_message(hello.clone()));
        let accepted = inviter_account
            .accept_session(joiner_account.identity(), hello)
            .unwrap();
        assert_eq!(accepted.session.session_id(), fresh.session_id());
    }

    #[test]
    fn tampered_keys_are_rejected() {
        let inviter = CryptoAccount::new();
        let mallory = CryptoAccount::new();
        let joiner = CryptoAccount::new();
        let otk = inviter.unpublished_one_time_keys(1).remove(0);

        // Server swaps in its own Curve25519 key but keeps the real Ed25519 key.
        let mut forged = inviter.identity();
        forged.curve25519 = mallory.identity().curve25519;
        assert!(matches!(
            verify_identity(forged.clone()),
            Err(CoreError::BadSignature { .. })
        ));
        assert!(joiner.start_session(forged, otk.clone()).is_err());

        // Server swaps the one-time key.
        let mut forged_otk = otk;
        forged_otk.key = mallory.unpublished_one_time_keys(1).remove(0).key;
        assert!(matches!(
            joiner.start_session(inviter.identity(), forged_otk),
            Err(CoreError::BadSignature { .. })
        ));
    }

    #[test]
    fn accept_session_requires_the_senders_real_identity() {
        let inviter = CryptoAccount::new();
        let joiner = CryptoAccount::new();
        let otk = inviter.unpublished_one_time_keys(1).remove(0);
        let session = joiner.start_session(inviter.identity(), otk).unwrap();
        let hello = session
            .encrypt(Payload::Hello {
                display_name: "x".into(),
            })
            .unwrap();
        let someone_else = CryptoAccount::new().identity();
        assert!(inviter.accept_session(someone_else, hello).is_err());
    }

    #[test]
    fn safety_code_is_symmetric() {
        let a = CryptoAccount::new().identity().ed25519;
        let b = CryptoAccount::new().identity().ed25519;
        let code = safety_code(a.clone(), b.clone()).unwrap();
        assert_eq!(code, safety_code(b.clone(), a.clone()).unwrap());
        assert_eq!(code.split(' ').count(), 8);
        assert!(
            code.split(' ')
                .all(|g| g.len() == 5 && g.chars().all(|c| c.is_ascii_digit()))
        );
        let c = CryptoAccount::new().identity().ed25519;
        assert_ne!(code, safety_code(a, c).unwrap());
    }
}
