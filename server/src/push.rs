//! Wakes a partner's phone through Firebase Cloud Messaging (FCM).
//!
//! Pushes carry no content at all, only "you have something waiting". The
//! phone then downloads and decrypts the envelope itself, so Google never
//! sees who said what.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use jsonwebtoken::{Algorithm, EncodingKey, Header};
use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::sync::Mutex as AsyncMutex;

use crate::store::now_ms;

const FCM_SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";

#[derive(Clone)]
pub enum Pusher {
    Fcm(Arc<Fcm>),
    Disabled,
    /// Tests: remembers which tokens were woken, and how urgently.
    Memory(Arc<Mutex<Vec<(String, Urgency)>>>),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Urgency {
    /// A new message or nudge: wake the phone even in Doze.
    High,
    Normal,
    /// An incoming call: wake the phone now, and drop the push if it can't
    /// be delivered within a minute, because by then the call is over.
    Call,
}

pub enum PushOutcome {
    Sent,
    /// The app was uninstalled or the token expired. Forget it.
    TokenGone,
    Failed,
}

impl Pusher {
    pub async fn wake(&self, token: &str, urgency: Urgency) -> PushOutcome {
        match self {
            Self::Fcm(fcm) => fcm.send(token, urgency).await,
            Self::Disabled => PushOutcome::Sent,
            Self::Memory(sent) => {
                sent.lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push((token.to_owned(), urgency));
                PushOutcome::Sent
            }
        }
    }
}

/// The parts of a Google service-account key file we need.
#[derive(Deserialize)]
struct ServiceAccount {
    project_id: String,
    client_email: String,
    private_key: String,
    token_uri: String,
}

#[derive(Serialize)]
struct Claims<'a> {
    iss: &'a str,
    scope: &'a str,
    aud: &'a str,
    iat: i64,
    exp: i64,
}

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    expires_in: i64,
}

pub struct Fcm {
    account: ServiceAccount,
    key: EncodingKey,
    http: reqwest::Client,
    access_token: AsyncMutex<Option<(String, i64)>>,
}

impl Fcm {
    /// Reads a service-account JSON key downloaded from the Firebase console.
    pub fn from_service_account_file(path: &str) -> Result<Self, String> {
        let raw = std::fs::read_to_string(path).map_err(|e| format!("cannot read {path}: {e}"))?;
        let account: ServiceAccount = serde_json::from_str(&raw)
            .map_err(|e| format!("{path} is not a service-account key: {e}"))?;
        let key = EncodingKey::from_rsa_pem(account.private_key.as_bytes())
            .map_err(|e| format!("bad private key in {path}: {e}"))?;
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(10))
            .build()
            .map_err(|e| e.to_string())?;
        Ok(Self {
            account,
            key,
            http,
            access_token: AsyncMutex::new(None),
        })
    }

    async fn token(&self) -> Result<String, String> {
        let mut cached = self.access_token.lock().await;
        let now = now_ms() / 1000;
        if let Some((token, expires_at)) = cached.as_ref()
            && *expires_at > now + 60
        {
            return Ok(token.clone());
        }
        let claims = Claims {
            iss: &self.account.client_email,
            scope: FCM_SCOPE,
            aud: &self.account.token_uri,
            iat: now,
            exp: now + 3600,
        };
        let assertion = jsonwebtoken::encode(&Header::new(Algorithm::RS256), &claims, &self.key)
            .map_err(|e| e.to_string())?;
        let response: TokenResponse = self
            .http
            .post(&self.account.token_uri)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
                ("assertion", &assertion),
            ])
            .send()
            .await
            .and_then(|r| r.error_for_status())
            .map_err(|e| e.to_string())?
            .json()
            .await
            .map_err(|e| e.to_string())?;
        *cached = Some((response.access_token.clone(), now + response.expires_in));
        Ok(response.access_token)
    }

    async fn send(&self, device_token: &str, urgency: Urgency) -> PushOutcome {
        let access_token = match self.token().await {
            Ok(token) => token,
            Err(e) => {
                tracing::error!(error = %e, "could not get an FCM access token");
                return PushOutcome::Failed;
            }
        };
        let (priority, ttl, kind) = match urgency {
            Urgency::High => ("HIGH", "86400s", "wake"),
            Urgency::Normal => ("NORMAL", "86400s", "wake"),
            Urgency::Call => ("HIGH", "60s", "call"),
        };
        let body = json!({
            "message": {
                "token": device_token,
                "data": { "t": kind },
                "android": { "priority": priority, "ttl": ttl }
            }
        });
        let url = format!(
            "https://fcm.googleapis.com/v1/projects/{}/messages:send",
            self.account.project_id
        );
        match self
            .http
            .post(url)
            .bearer_auth(access_token)
            .json(&body)
            .send()
            .await
        {
            Ok(r) if r.status().is_success() => PushOutcome::Sent,
            Ok(r) if r.status() == reqwest::StatusCode::NOT_FOUND => PushOutcome::TokenGone,
            Ok(r) => {
                let status = r.status();
                let text = r.text().await.unwrap_or_default();
                if text.contains("UNREGISTERED") {
                    PushOutcome::TokenGone
                } else {
                    tracing::warn!(%status, %text, "FCM rejected a push");
                    PushOutcome::Failed
                }
            }
            Err(e) => {
                tracing::warn!(error = %e, "FCM request failed");
                PushOutcome::Failed
            }
        }
    }
}
