//! REST endpoints. See `docs/PROTOCOL.md` for the full contract.

use std::sync::Arc;

use axum::body::Body;
use axum::extract::{DefaultBodyLimit, FromRequestParts, Path, Query, State};
use axum::http::request::Parts;
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post, put};
use axum::{Json, Router};
use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use hmac::{Hmac, KeyInit, Mac};
use rand::Rng;
use serde::{Deserialize, Serialize};
use tokio_util::io::ReaderStream;
use tower_http::trace::TraceLayer;

use crate::error::{ApiError, ApiResult};
use crate::protocol::{
    Accepted, BlobCreated, Envelope, EnvelopeKind, IceServer, IceServers, IdentityBundle,
    PairingEvent, PartnerPresence, SendKind, SendRequest, ServerFrame, SignedOneTimeKey,
};
use crate::push::{PushOutcome, Urgency};
use crate::store::{
    IdentityUpdate, Invite, LoginCodeCheck, LoginCodeIssue, PairResult, User, now_ms, sha256,
};
use crate::{AppState, ws};

type AppStateRef = Arc<AppState>;

const INVITE_TTL_MS: i64 = 7 * 24 * 60 * 60 * 1000;
const INVITE_ALPHABET: &[u8] = b"23456789ABCDEFGHJKMNPQRSTUVWXYZ";
const MAX_BODY_CHARS: usize = 64 * 1024;
const MAX_ID_CHARS: usize = 64;
const MAX_KEY_CHARS: usize = 128;
const MAX_NAME_CHARS: usize = 40;

pub fn router(state: AppStateRef) -> Router {
    Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/v1/auth/start", post(auth_start))
        .route("/v1/auth/verify", post(auth_verify))
        .route("/v1/auth/sign-out", post(sign_out))
        .route("/v1/me", get(me).patch(update_me).delete(delete_account))
        .route("/v1/keys", put(put_keys))
        .route("/v1/push-token", put(put_push_token))
        .route("/v1/invites", post(create_invite).delete(cancel_invite))
        .route("/v1/invites/accept", post(accept_invite))
        .route("/v1/partner", delete(unpair))
        .route("/v1/partner/claim-key", post(claim_partner_key))
        .route("/v1/envelopes", get(list_envelopes).post(send_envelope))
        .route("/v1/envelopes/ack", post(ack_envelopes))
        .route(
            "/v1/blobs",
            post(upload_blob).layer(DefaultBodyLimit::disable()),
        )
        .route("/v1/blobs/{id}", get(download_blob).delete(delete_blob))
        .route("/v1/calls/ice-servers", get(ice_servers))
        .route("/v1/ws", get(ws::upgrade))
        .layer(DefaultBodyLimit::max(256 * 1024))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

/// The signed-in user, from `Authorization: Bearer <token>`.
pub struct AuthUser(pub String);

impl FromRequestParts<AppStateRef> for AuthUser {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppStateRef,
    ) -> Result<Self, Self::Rejection> {
        let token = parts
            .headers
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .ok_or(ApiError::Unauthorized)?;
        state
            .store
            .user_id_for_token(&sha256(&[token.as_bytes()]))
            .await?
            .map(AuthUser)
            .ok_or(ApiError::Unauthorized)
    }
}

// ---- sign-in -------------------------------------------------------------------

#[derive(Deserialize)]
struct AuthStart {
    email: String,
}

async fn auth_start(
    State(state): State<AppStateRef>,
    Json(req): Json<AuthStart>,
) -> ApiResult<StatusCode> {
    let email = normalize_email(&req.email)?;
    let code = format!("{:06}", rand::rng().random_range(0..1_000_000));
    match state
        .store
        .issue_login_code(&email, &login_code_hash(&email, &code))
        .await?
    {
        LoginCodeIssue::Throttled => return Err(ApiError::TooManyRequests),
        LoginCodeIssue::Issued => {}
    }
    let body = format!(
        "Your MaMi sign-in code is {code}\n\nIt expires in 10 minutes. If you didn't ask for it, you can ignore this email."
    );
    if let Err(e) = state
        .mailer
        .send(&email, &format!("{code} is your MaMi code"), body)
        .await
    {
        tracing::error!(error = %e, "could not send sign-in email");
        return Err(ApiError::Conflict("email_failed"));
    }
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct AuthVerify {
    email: String,
    code: String,
}

#[derive(Serialize, Deserialize)]
pub struct AuthVerified {
    pub token: String,
    pub user_id: String,
    pub new_user: bool,
}

async fn auth_verify(
    State(state): State<AppStateRef>,
    Json(req): Json<AuthVerify>,
) -> ApiResult<Json<AuthVerified>> {
    let email = normalize_email(&req.email)?;
    let code: String = req.code.chars().filter(char::is_ascii_digit).collect();
    match state
        .store
        .check_login_code(&email, &login_code_hash(&email, &code))
        .await?
    {
        LoginCodeCheck::Valid => {}
        LoginCodeCheck::Invalid => return Err(ApiError::BadRequest("wrong_code")),
        LoginCodeCheck::TooManyAttempts => return Err(ApiError::TooManyRequests),
    }

    let (user_id, new_user) = match state.store.user_id_by_email(&email).await? {
        Some(id) => (id, false),
        None => {
            let id = random_hex(16);
            state.store.create_user(&id, &email).await?;
            (id, true)
        }
    };
    let token = URL_SAFE_NO_PAD.encode(random_bytes::<32>());
    state
        .store
        .replace_device(&user_id, &sha256(&[token.as_bytes()]))
        .await?;
    state.hub.kick(&user_id);
    Ok(Json(AuthVerified {
        token,
        user_id,
        new_user,
    }))
}

async fn sign_out(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
) -> ApiResult<StatusCode> {
    state.store.sign_out(&me).await?;
    state.hub.kick(&me);
    Ok(StatusCode::NO_CONTENT)
}

// ---- profile ---------------------------------------------------------------------

#[derive(Serialize, Deserialize, Debug)]
pub struct MeResponse {
    pub user_id: String,
    pub email: String,
    pub display_name: String,
    pub has_identity: bool,
    pub one_time_key_count: i64,
    pub partner: Option<PartnerView>,
    pub invite: Option<InviteView>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct PartnerView {
    pub user_id: String,
    pub email: String,
    pub display_name: String,
    pub paired_at_ms: i64,
    /// Absent until the partner's phone has uploaded its keys.
    pub identity: Option<IdentityBundle>,
    pub presence: PartnerPresence,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct InviteView {
    pub code: String,
    pub partner_email: Option<String>,
    pub expires_at_ms: i64,
}

async fn me(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
) -> ApiResult<Json<MeResponse>> {
    let user = load_user(&state, &me).await?;
    let partner = match &user.partner_id {
        Some(partner_id) => {
            Some(partner_view(&state, partner_id, user.paired_at.unwrap_or(0)).await?)
        }
        None => None,
    };
    let invite = state
        .store
        .invite_by_inviter(&me)
        .await?
        .filter(|i| i.expires_at > now_ms())
        .map(invite_view);
    Ok(Json(MeResponse {
        has_identity: state.store.identity(&me).await?.is_some(),
        one_time_key_count: state.store.one_time_key_count(&me).await?,
        user_id: user.id,
        email: user.email,
        display_name: user.display_name,
        partner,
        invite,
    }))
}

#[derive(Deserialize)]
struct UpdateMe {
    display_name: String,
}

async fn update_me(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Json(req): Json<UpdateMe>,
) -> ApiResult<StatusCode> {
    let name = req.display_name.trim();
    if name.chars().count() > MAX_NAME_CHARS {
        return Err(ApiError::BadRequest("name_too_long"));
    }
    state.store.set_display_name(&me, name).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Deletes the account. The partner is unlinked and everything queued between
/// the two of them is deleted.
async fn delete_account(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
) -> ApiResult<StatusCode> {
    unlink(&state, &me).await?;
    let blobs = state.store.take_blobs_of(&[&me]).await?;
    state.blobs.delete_all(&blobs).await;
    state.store.delete_user(&me).await?;
    state.hub.kick(&me);
    Ok(StatusCode::NO_CONTENT)
}

// ---- keys ------------------------------------------------------------------------

#[derive(Deserialize)]
struct PutKeys {
    identity: Option<IdentityBundle>,
    #[serde(default)]
    one_time_keys: Vec<SignedOneTimeKey>,
}

#[derive(Serialize, Deserialize)]
pub struct KeyCount {
    pub one_time_key_count: i64,
}

async fn put_keys(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Json(req): Json<PutKeys>,
) -> ApiResult<Json<KeyCount>> {
    if let Some(identity) = &req.identity {
        for key in [
            &identity.curve25519,
            &identity.ed25519,
            &identity.curve25519_signature,
        ] {
            check_len(key, MAX_KEY_CHARS, "bad_key")?;
        }
        match state.store.set_identity(&me, identity).await? {
            IdentityUpdate::Conflict => return Err(ApiError::Conflict("identity_mismatch")),
            IdentityUpdate::Unchanged => {}
            IdentityUpdate::Set => {
                if let Some(partner) = load_user(&state, &me).await?.partner_id {
                    state.hub.send(
                        &partner,
                        &ServerFrame::Pairing {
                            event: PairingEvent::KeysChanged,
                        },
                    );
                }
            }
        }
    }
    for key in &req.one_time_keys {
        check_len(&key.key_id, MAX_KEY_CHARS, "bad_key")?;
        check_len(&key.key, MAX_KEY_CHARS, "bad_key")?;
        check_len(&key.signature, MAX_KEY_CHARS, "bad_key")?;
    }
    let one_time_key_count = state
        .store
        .add_one_time_keys(&me, &req.one_time_keys)
        .await?;
    Ok(Json(KeyCount { one_time_key_count }))
}

#[derive(Deserialize)]
struct PushToken {
    token: Option<String>,
}

async fn put_push_token(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Json(req): Json<PushToken>,
) -> ApiResult<StatusCode> {
    if let Some(token) = &req.token {
        check_len(token, 4096, "bad_token")?;
    }
    state
        .store
        .set_push_token(&me, req.token.as_deref())
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Serialize, Deserialize)]
pub struct ClaimedKey {
    pub identity: IdentityBundle,
    pub one_time_key: SignedOneTimeKey,
}

/// Gives the caller one of the partner's one-time keys to start a session.
async fn claim_partner_key(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
) -> ApiResult<Json<ClaimedKey>> {
    let partner = partner_of(&state, &me).await?;
    let identity = state
        .store
        .identity(&partner)
        .await?
        .ok_or(ApiError::Conflict("partner_not_ready"))?;
    let one_time_key = state
        .store
        .claim_one_time_key(&partner)
        .await?
        .ok_or(ApiError::Conflict("no_one_time_keys"))?;
    Ok(Json(ClaimedKey {
        identity,
        one_time_key,
    }))
}

// ---- invites and pairing -----------------------------------------------------------

#[derive(Deserialize, Default)]
struct CreateInvite {
    partner_email: Option<String>,
}

async fn create_invite(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Json(req): Json<CreateInvite>,
) -> ApiResult<Json<InviteView>> {
    let user = load_user(&state, &me).await?;
    if user.partner_id.is_some() {
        return Err(ApiError::Conflict("already_paired"));
    }
    let partner_email = match req
        .partner_email
        .as_deref()
        .map(str::trim)
        .filter(|e| !e.is_empty())
    {
        Some(email) => Some(normalize_email(email)?),
        None => None,
    };
    if partner_email.as_deref() == Some(user.email.as_str()) {
        return Err(ApiError::BadRequest("own_email"));
    }
    let invite = Invite {
        code: invite_code(),
        inviter_id: me,
        partner_email: partner_email.clone(),
        expires_at: now_ms() + INVITE_TTL_MS,
    };
    state.store.put_invite(&invite).await?;

    if let Some(to) = &partner_email {
        let from = if user.display_name.is_empty() {
            user.email.clone()
        } else {
            user.display_name.clone()
        };
        let body = format!(
            "{from} invited you to MaMi, a private messenger for the two of you.\n\n\
             Install the app, sign in with this email address and enter the code:\n\n    {}\n\n\
             The code works for 7 days.",
            invite.code
        );
        if let Err(e) = state
            .mailer
            .send(to, &format!("{from} invited you to MaMi"), body)
            .await
        {
            tracing::warn!(error = %e, "could not send invite email");
        }
    }
    Ok(Json(invite_view(invite)))
}

async fn cancel_invite(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
) -> ApiResult<StatusCode> {
    state.store.delete_invite(&me).await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct AcceptInvite {
    code: String,
}

async fn accept_invite(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Json(req): Json<AcceptInvite>,
) -> ApiResult<Json<PartnerView>> {
    let code = normalize_invite_code(&req.code);
    let invite = state
        .store
        .invite_by_code(&code)
        .await?
        .ok_or(ApiError::NotFound("invite_not_found"))?;
    if invite.expires_at < now_ms() {
        return Err(ApiError::NotFound("invite_expired"));
    }
    if invite.inviter_id == me {
        return Err(ApiError::BadRequest("own_invite"));
    }
    let user = load_user(&state, &me).await?;
    if invite
        .partner_email
        .as_ref()
        .is_some_and(|email| *email != user.email)
    {
        return Err(ApiError::Forbidden("invite_for_someone_else"));
    }
    match state.store.pair(&invite.inviter_id, &me).await? {
        PairResult::AlreadyPaired => return Err(ApiError::Conflict("already_paired")),
        PairResult::Paired => {}
    }
    notify(
        &state,
        &invite.inviter_id,
        ServerFrame::Pairing {
            event: PairingEvent::Paired,
        },
        Urgency::High,
    )
    .await;
    let paired_at = load_user(&state, &me)
        .await?
        .paired_at
        .unwrap_or_else(now_ms);
    Ok(Json(
        partner_view(&state, &invite.inviter_id, paired_at).await?,
    ))
}

/// Either partner can leave at any time.
async fn unpair(State(state): State<AppStateRef>, AuthUser(me): AuthUser) -> ApiResult<StatusCode> {
    unlink(&state, &me).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn unlink(state: &AppStateRef, me: &str) -> ApiResult<()> {
    if let Some(partner) = state.store.unpair(me).await? {
        // Attachments were only ever for the two of them.
        let blobs = state.store.take_blobs_of(&[me, &partner]).await?;
        state.blobs.delete_all(&blobs).await;
        notify(
            state,
            &partner,
            ServerFrame::Pairing {
                event: PairingEvent::Unpaired,
            },
            Urgency::Normal,
        )
        .await;
    }
    Ok(())
}

// ---- envelopes ---------------------------------------------------------------------

#[derive(Deserialize)]
struct ListEnvelopes {
    #[serde(default)]
    after_seq: i64,
}

async fn list_envelopes(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Query(query): Query<ListEnvelopes>,
) -> ApiResult<Json<Vec<Envelope>>> {
    Ok(Json(state.store.pending(&me, query.after_seq, 500).await?))
}

async fn send_envelope(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Json(req): Json<SendRequest>,
) -> ApiResult<Json<Accepted>> {
    Ok(Json(deliver(&state, &me, req).await?))
}

#[derive(Deserialize)]
struct Ack {
    seqs: Vec<i64>,
}

async fn ack_envelopes(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Json(req): Json<Ack>,
) -> ApiResult<StatusCode> {
    acknowledge(&state, &me, &req.seqs).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Stores and/or forwards one encrypted envelope from `sender` to their partner.
pub(crate) async fn deliver(
    state: &AppStateRef,
    sender: &str,
    req: SendRequest,
) -> ApiResult<Accepted> {
    if req.id.is_empty() {
        return Err(ApiError::BadRequest("bad_id"));
    }
    check_len(&req.id, MAX_ID_CHARS, "bad_id")?;
    check_len(&req.body, MAX_BODY_CHARS, "too_large")?;
    if !matches!(req.message_type, 0 | 1) {
        return Err(ApiError::BadRequest("bad_message_type"));
    }
    let partner = partner_of(state, sender).await?;

    let envelope = match req.kind {
        SendKind::Message => {
            let (envelope, fresh) = state
                .store
                .enqueue_message(sender, &partner, &req.id, req.message_type, &req.body)
                .await?;
            if fresh
                && !state
                    .hub
                    .send(&partner, &ServerFrame::Envelope(envelope.clone()))
                && req.push
            {
                wake(state, &partner, Urgency::High).await;
            }
            envelope
        }
        SendKind::Status => {
            let envelope = state
                .store
                .replace_status(sender, &partner, &req.id, req.message_type, &req.body)
                .await?;
            state
                .hub
                .send(&partner, &ServerFrame::Envelope(envelope.clone()));
            envelope
        }
        SendKind::Call => {
            let envelope = state
                .store
                .enqueue_call(sender, &partner, &req.id, req.message_type, &req.body)
                .await?;
            if !state
                .hub
                .send(&partner, &ServerFrame::Envelope(envelope.clone()))
                && req.push
            {
                wake(state, &partner, Urgency::Call).await;
            }
            envelope
        }
        SendKind::Ephemeral => {
            let envelope = Envelope {
                seq: None,
                id: req.id.clone(),
                kind: EnvelopeKind::Ephemeral,
                message_type: Some(req.message_type),
                body: Some(req.body),
                at_ms: now_ms(),
            };
            state
                .hub
                .send(&partner, &ServerFrame::Envelope(envelope.clone()));
            envelope
        }
    };
    Ok(Accepted {
        id: envelope.id,
        at_ms: envelope.at_ms,
    })
}

pub(crate) async fn acknowledge(state: &AppStateRef, me: &str, seqs: &[i64]) -> ApiResult<()> {
    if seqs.len() > 1000 {
        return Err(ApiError::BadRequest("too_many"));
    }
    for (sender, receipt) in state.store.ack(me, seqs).await? {
        state.hub.send(&sender, &ServerFrame::Envelope(receipt));
    }
    Ok(())
}

pub(crate) async fn presence_of(state: &AppStateRef, user_id: &str) -> ApiResult<PartnerPresence> {
    Ok(PartnerPresence {
        online: state.hub.is_online(user_id),
        last_seen_ms: state.store.last_seen(user_id).await?,
    })
}

// ---- attachments ---------------------------------------------------------------------

/// Uploads one encrypted attachment for the partner. The body is the
/// encrypted file, nothing else; the server can't open it.
async fn upload_blob(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    headers: HeaderMap,
    body: Body,
) -> ApiResult<Json<BlobCreated>> {
    partner_of(&state, &me).await?;
    let max = state.config.max_blob_bytes;
    let room = state
        .config
        .blob_quota_bytes
        .saturating_sub(state.store.blob_bytes(&me).await?);
    let too_big = |len: u64| {
        if len > max {
            ApiError::TooLarge("too_large")
        } else {
            ApiError::TooLarge("quota_full")
        }
    };
    let declared = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok());
    if let Some(len) = declared
        && len > max.min(room)
    {
        return Err(too_big(len));
    }
    let id = random_hex(16);
    let size = match state.blobs.save(&id, body, max.min(room)).await {
        Err(ApiError::TooLarge(_)) => return Err(too_big(max.min(room) + 1)),
        other => other?,
    };
    if let Err(e) = state.store.add_blob(&id, &me, size).await {
        state.blobs.delete(&id).await;
        return Err(e.into());
    }
    Ok(Json(BlobCreated { id, size }))
}

/// Only the uploader and their partner may see an attachment. Anyone else is
/// told it doesn't exist.
async fn check_blob_access(state: &AppStateRef, me: &str, id: &str) -> ApiResult<()> {
    let owner = state
        .store
        .blob_owner(id)
        .await?
        .ok_or(ApiError::NotFound("blob_not_found"))?;
    if owner == me || load_user(state, me).await?.partner_id.as_deref() == Some(owner.as_str()) {
        Ok(())
    } else {
        Err(ApiError::NotFound("blob_not_found"))
    }
}

async fn download_blob(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Path(id): Path<String>,
) -> ApiResult<Response> {
    check_blob_access(&state, &me, &id).await?;
    let (file, size) = state.blobs.open(&id).await?;
    Ok((
        [
            (header::CONTENT_TYPE, "application/octet-stream".to_owned()),
            (header::CONTENT_LENGTH, size.to_string()),
            (header::CACHE_CONTROL, "no-store".to_owned()),
        ],
        Body::from_stream(ReaderStream::new(file)),
    )
        .into_response())
}

/// The partner calls this once the attachment is safely on their phone.
async fn delete_blob(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
    Path(id): Path<String>,
) -> ApiResult<StatusCode> {
    check_blob_access(&state, &me, &id).await?;
    if state.store.delete_blob(&id).await? {
        state.blobs.delete(&id).await;
    }
    Ok(StatusCode::NO_CONTENT)
}

// ---- calls ---------------------------------------------------------------------------

/// STUN and TURN servers for a call. TURN credentials follow coturn's REST
/// API: the username is `expiry:user`, the password an HMAC of it with the
/// shared secret, so the relay can check them without asking us.
async fn ice_servers(
    State(state): State<AppStateRef>,
    AuthUser(me): AuthUser,
) -> ApiResult<Json<IceServers>> {
    partner_of(&state, &me).await?;
    let mut ice_servers = Vec::new();
    if !state.config.stun_urls.is_empty() {
        ice_servers.push(IceServer {
            urls: state.config.stun_urls.clone(),
            username: None,
            credential: None,
        });
    }
    let ttl_s = match &state.config.turn {
        Some(turn) => {
            let (username, credential) =
                turn_credentials(&turn.secret, &me, now_ms() / 1000 + turn.ttl_s);
            ice_servers.push(IceServer {
                urls: turn.urls.clone(),
                username: Some(username),
                credential: Some(credential),
            });
            turn.ttl_s
        }
        None => 24 * 60 * 60,
    };
    Ok(Json(IceServers { ice_servers, ttl_s }))
}

pub fn turn_credentials(secret: &str, user_id: &str, expires_at_s: i64) -> (String, String) {
    let username = format!("{expires_at_s}:{user_id}");
    let mut mac = Hmac::<sha1::Sha1>::new_from_slice(secret.as_bytes())
        .expect("HMAC takes keys of any length");
    mac.update(username.as_bytes());
    let credential = STANDARD.encode(mac.finalize().into_bytes());
    (username, credential)
}

// ---- helpers -------------------------------------------------------------------------

/// Sends a live frame if the user is connected, otherwise wakes their phone.
async fn notify(state: &AppStateRef, user_id: &str, frame: ServerFrame, urgency: Urgency) {
    if !state.hub.send(user_id, &frame) {
        wake(state, user_id, urgency).await;
    }
}

async fn wake(state: &AppStateRef, user_id: &str, urgency: Urgency) {
    let token = match state.store.push_token(user_id).await {
        Ok(Some(token)) => token,
        Ok(None) => return,
        Err(e) => {
            tracing::error!(error = %e, "could not load push token");
            return;
        }
    };
    let state = state.clone();
    tokio::spawn(async move {
        if let PushOutcome::TokenGone = state.pusher.wake(&token, urgency).await
            && let Err(e) = state.store.clear_push_token(&token).await
        {
            tracing::error!(error = %e, "could not clear dead push token");
        }
    });
}

async fn load_user(state: &AppStateRef, id: &str) -> ApiResult<User> {
    state.store.user(id).await?.ok_or(ApiError::Unauthorized)
}

async fn partner_of(state: &AppStateRef, me: &str) -> ApiResult<String> {
    load_user(state, me)
        .await?
        .partner_id
        .ok_or(ApiError::Forbidden("not_paired"))
}

async fn partner_view(
    state: &AppStateRef,
    partner_id: &str,
    paired_at_ms: i64,
) -> ApiResult<PartnerView> {
    let partner = load_user(state, partner_id).await?;
    Ok(PartnerView {
        identity: state.store.identity(partner_id).await?,
        presence: presence_of(state, partner_id).await?,
        user_id: partner.id,
        email: partner.email,
        display_name: partner.display_name,
        paired_at_ms,
    })
}

fn invite_view(invite: Invite) -> InviteView {
    InviteView {
        code: invite.code,
        partner_email: invite.partner_email,
        expires_at_ms: invite.expires_at,
    }
}

fn check_len(value: &str, max: usize, code: &'static str) -> ApiResult<()> {
    if value.len() > max {
        Err(ApiError::BadRequest(code))
    } else {
        Ok(())
    }
}

fn normalize_email(raw: &str) -> ApiResult<String> {
    let email = raw.trim().to_lowercase();
    let valid = email.len() <= 254
        && !email.chars().any(char::is_whitespace)
        && email.split_once('@').is_some_and(|(local, domain)| {
            !local.is_empty()
                && domain.contains('.')
                && !domain.starts_with('.')
                && !domain.ends_with('.')
        });
    if valid {
        Ok(email)
    } else {
        Err(ApiError::BadRequest("bad_email"))
    }
}

/// "abcd efgh", "ABCD-EFGH" and "abcdefgh" all mean the same code.
fn normalize_invite_code(raw: &str) -> String {
    let chars: String = raw
        .chars()
        .filter(char::is_ascii_alphanumeric)
        .map(|c| c.to_ascii_uppercase())
        .collect();
    if chars.len() == 8 {
        format!("{}-{}", &chars[..4], &chars[4..])
    } else {
        chars
    }
}

fn invite_code() -> String {
    let mut rng = rand::rng();
    let chars: String = (0..8)
        .map(|_| INVITE_ALPHABET[rng.random_range(0..INVITE_ALPHABET.len())] as char)
        .collect();
    normalize_invite_code(&chars)
}

fn login_code_hash(email: &str, code: &str) -> Vec<u8> {
    sha256(&[b"login:", email.as_bytes(), b":", code.as_bytes()])
}

fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0u8; N];
    rand::rng().fill(&mut bytes);
    bytes
}

fn random_hex(n: usize) -> String {
    let mut rng = rand::rng();
    (0..n)
        .map(|_| format!("{:02x}", rng.random::<u8>()))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emails_are_normalized() {
        assert_eq!(
            normalize_email("  Maya@Example.COM ").unwrap(),
            "maya@example.com"
        );
        for bad in [
            "",
            "maya",
            "maya@",
            "@example.com",
            "maya@example",
            "ma ya@example.com",
            "maya@.com",
        ] {
            assert!(normalize_email(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn turn_credentials_match_coturn() {
        // Same algorithm as coturn's use-auth-secret: base64(HMAC-SHA1(secret, username)).
        let (username, credential) = turn_credentials("north", "maya", 1_700_000_000);
        assert_eq!(username, "1700000000:maya");
        assert_eq!(credential, "fIdYkxbrcGwtmRIt9CPVboR2QRE=");
    }

    #[test]
    fn invite_codes_are_forgiving() {
        assert_eq!(normalize_invite_code("abcd efgh"), "ABCD-EFGH");
        assert_eq!(normalize_invite_code("ABCD-EFGH"), "ABCD-EFGH");
        let code = invite_code();
        assert_eq!(code.len(), 9);
        assert_eq!(normalize_invite_code(&code.to_lowercase()), code);
    }
}
