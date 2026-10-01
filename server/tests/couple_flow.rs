//! Drives two simulated phones through the whole product flow against a real
//! server, using the real encryption core.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use mami_core::{Ciphertext, CryptoAccount, CryptoSession, DeviceStatus, Payload, ShareKind};
use mami_server::api::{AuthVerified, ClaimedKey, MeResponse, PartnerView};
use mami_server::blobs::BlobStore;
use mami_server::mail::{Mailer, SentMail};
use mami_server::protocol::{BlobCreated, IceServers};
use mami_server::protocol::{
    ClientFrame, Envelope, EnvelopeKind, PairingEvent, SendKind, SendRequest, ServerFrame,
};
use mami_server::push::{Pusher, Urgency};
use mami_server::store::{self, Store};
use mami_server::{AppState, Config, Turn, router};
use reqwest::StatusCode;
use serde_json::{Value, json};
use tokio::net::TcpStream;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream};

type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;

struct Server {
    base: String,
    store: Store,
    http: reqwest::Client,
    mails: Arc<Mutex<Vec<SentMail>>>,
    pushes: Arc<Mutex<Vec<(String, Urgency)>>>,
    blobs: BlobStore,
}

impl Server {
    async fn start() -> Self {
        Self::start_with(Config::default()).await
    }

    async fn start_with(config: Config) -> Self {
        let name = format!("mami-test-{}", rand_suffix());
        let path = std::env::temp_dir().join(format!("{name}.db"));
        let pool = store::open(&format!("sqlite://{}", path.display()))
            .await
            .unwrap();
        let mails = Arc::new(Mutex::new(Vec::new()));
        let pushes = Arc::new(Mutex::new(Vec::new()));
        let store = Store::new(pool);
        let blobs = BlobStore::new(std::env::temp_dir().join(format!("{name}-blobs"))).unwrap();
        let state = AppState::new(
            store.clone(),
            Mailer::Memory(mails.clone()),
            Pusher::Memory(pushes.clone()),
            blobs.clone(),
            config,
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, router(state)).await.unwrap() });
        let http = reqwest::Client::builder().no_proxy().build().unwrap();
        Self {
            base,
            store,
            http,
            mails,
            pushes,
            blobs,
        }
    }

    fn last_mail_to(&self, to: &str) -> SentMail {
        self.mails
            .lock()
            .unwrap()
            .iter()
            .rev()
            .find(|m| m.to == to)
            .cloned()
            .expect("no mail sent")
    }

    fn code_in_last_mail(&self, to: &str) -> String {
        let mail = self.last_mail_to(to);
        mail.subject.split_whitespace().next().unwrap().to_owned()
    }

    async fn sign_in(&self, email: &str) -> Phone {
        let r = self
            .http
            .post(format!("{}/v1/auth/start", self.base))
            .json(&json!({ "email": email }))
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::NO_CONTENT);
        let code = self.code_in_last_mail(&email.trim().to_lowercase());
        let r = self
            .http
            .post(format!("{}/v1/auth/verify", self.base))
            .json(&json!({ "email": email, "code": code }))
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        let verified: AuthVerified = r.json().await.unwrap();
        let phone = Phone {
            base: self.base.clone(),
            http: self.http.clone(),
            token: verified.token,
            account: CryptoAccount::new(),
            session: None,
        };
        let identity = phone.account.identity();
        let keys = phone.account.unpublished_one_time_keys(10);
        let r = phone
            .request(reqwest::Method::PUT, "/v1/keys")
            .json(&json!({
                "identity": {
                    "curve25519": identity.curve25519,
                    "ed25519": identity.ed25519,
                    "curve25519_signature": identity.curve25519_signature,
                },
                "one_time_keys": keys.iter().map(|k| json!({ "key_id": k.key_id, "key": k.key, "signature": k.signature })).collect::<Vec<_>>(),
            }))
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        phone.account.mark_keys_published();
        phone
    }
}

struct Phone {
    base: String,
    http: reqwest::Client,
    token: String,
    account: Arc<CryptoAccount>,
    session: Option<Arc<CryptoSession>>,
}

impl Phone {
    fn request(&self, method: reqwest::Method, path: &str) -> reqwest::RequestBuilder {
        self.http
            .request(method, format!("{}{}", self.base, path))
            .bearer_auth(&self.token)
    }

    async fn me(&self) -> MeResponse {
        self.request(reqwest::Method::GET, "/v1/me")
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap()
    }

    async fn connect(&self) -> Socket {
        let mut request = format!("{}/v1/ws", self.base.replace("http", "ws"))
            .into_client_request()
            .unwrap();
        request.headers_mut().insert(
            "Authorization",
            format!("Bearer {}", self.token).parse().unwrap(),
        );
        let (socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        socket
    }

    fn seal(&self, id: &str, kind: SendKind, payload: Payload, push: bool) -> SendRequest {
        let c = self
            .session
            .as_ref()
            .expect("no session")
            .encrypt(payload)
            .unwrap();
        SendRequest {
            id: id.into(),
            kind,
            message_type: c.message_type,
            body: c.body,
            push,
        }
    }

    fn open(&self, envelope: &Envelope) -> Payload {
        self.session
            .as_ref()
            .unwrap()
            .decrypt(Ciphertext {
                message_type: envelope.message_type.unwrap(),
                body: envelope.body.clone().unwrap(),
            })
            .unwrap()
    }

    async fn post_envelope(&self, request: &SendRequest) -> reqwest::Response {
        self.request(reqwest::Method::POST, "/v1/envelopes")
            .json(request)
            .send()
            .await
            .unwrap()
    }

    async fn pending(&self) -> Vec<Envelope> {
        self.request(reqwest::Method::GET, "/v1/envelopes")
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap()
    }
}

async fn send_frame(socket: &mut Socket, frame: &ClientFrame) {
    socket
        .send(Message::Text(serde_json::to_string(frame).unwrap().into()))
        .await
        .unwrap();
}

async fn next_frame(socket: &mut Socket) -> ServerFrame {
    loop {
        let message = tokio::time::timeout(Duration::from_secs(5), socket.next())
            .await
            .expect("timed out waiting for a frame")
            .unwrap()
            .unwrap();
        if let Message::Text(text) = message {
            return serde_json::from_str(&text).unwrap();
        }
    }
}

async fn expect_no_frame(socket: &mut Socket) {
    let result = tokio::time::timeout(Duration::from_millis(300), socket.next()).await;
    assert!(result.is_err(), "unexpected frame: {result:?}");
}

fn rand_suffix() -> String {
    format!(
        "{:x}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    )
}

fn text(id: &str, body: &str) -> Payload {
    Payload::Text {
        id: id.into(),
        body: body.into(),
        sent_at_ms: 1,
        reply_to: None,
        link: None,
        deliver_at_ms: None,
    }
}

#[tokio::test]
async fn two_phones_pair_and_talk() {
    let server = Server::start().await;
    let mut maya = server.sign_in("maya@example.com").await;
    let mut arjun = server.sign_in("Arjun@Example.com ").await;

    // Maya invites Arjun by email.
    let r = maya
        .request(reqwest::Method::POST, "/v1/invites")
        .json(&json!({ "partner_email": "arjun@example.com" }))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    let invite: Value = r.json().await.unwrap();
    let code = invite["code"].as_str().unwrap().to_owned();
    assert!(
        server
            .last_mail_to("arjun@example.com")
            .body
            .contains(&code)
    );

    // Only Arjun can use it.
    let stranger = server.sign_in("stranger@example.com").await;
    let r = stranger
        .request(reqwest::Method::POST, "/v1/invites/accept")
        .json(&json!({ "code": code }))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::FORBIDDEN);

    let mut maya_ws = maya.connect().await;
    assert_eq!(
        next_frame(&mut maya_ws).await,
        ServerFrame::Ready { partner: None }
    );

    let r = arjun
        .request(reqwest::Method::POST, "/v1/invites/accept")
        .json(&json!({ "code": code.to_lowercase().replace('-', " ") }))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    let partner: PartnerView = r.json().await.unwrap();
    assert_eq!(partner.email, "maya@example.com");
    assert!(partner.presence.online);
    assert_eq!(
        next_frame(&mut maya_ws).await,
        ServerFrame::Pairing {
            event: PairingEvent::Paired
        }
    );

    // Arjun starts the encrypted session with one of Maya's one-time keys.
    let claimed: ClaimedKey = arjun
        .request(reqwest::Method::POST, "/v1/partner/claim-key")
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let maya_identity = mami_core::IdentityBundle {
        curve25519: claimed.identity.curve25519.clone(),
        ed25519: claimed.identity.ed25519.clone(),
        curve25519_signature: claimed.identity.curve25519_signature.clone(),
    };
    assert_eq!(maya_identity, maya.account.identity());
    let otk = mami_core::SignedOneTimeKey {
        key_id: claimed.one_time_key.key_id,
        key: claimed.one_time_key.key,
        signature: claimed.one_time_key.signature,
    };
    arjun.session = Some(arjun.account.start_session(maya_identity, otk).unwrap());
    assert_eq!(maya.me().await.one_time_key_count, 9);

    let hello = arjun.seal(
        "hello-1",
        SendKind::Message,
        Payload::Hello {
            display_name: "Arjun".into(),
        },
        true,
    );
    assert_eq!(arjun.post_envelope(&hello).await.status(), StatusCode::OK);

    // Maya is online, so the envelope arrives live; she opens the session with it.
    let ServerFrame::Envelope(envelope) = next_frame(&mut maya_ws).await else {
        panic!()
    };
    assert_eq!(envelope.kind, EnvelopeKind::Message);
    let arjun_identity = maya.me().await.partner.unwrap().identity.unwrap();
    let accepted = maya
        .account
        .accept_session(
            mami_core::IdentityBundle {
                curve25519: arjun_identity.curve25519,
                ed25519: arjun_identity.ed25519,
                curve25519_signature: arjun_identity.curve25519_signature,
            },
            Ciphertext {
                message_type: envelope.message_type.unwrap(),
                body: envelope.body.clone().unwrap(),
            },
        )
        .unwrap();
    assert_eq!(
        accepted.payload,
        Payload::Hello {
            display_name: "Arjun".into()
        }
    );
    maya.session = Some(accepted.session);
    assert_eq!(
        mami_core::safety_code(
            maya.account.identity().ed25519,
            arjun.account.identity().ed25519
        )
        .unwrap(),
        mami_core::safety_code(
            arjun.account.identity().ed25519,
            maya.account.identity().ed25519
        )
        .unwrap(),
    );
    send_frame(
        &mut maya_ws,
        &ClientFrame::Ack {
            seqs: vec![envelope.seq.unwrap()],
        },
    )
    .await;

    // Arjun was offline: the delivery receipt waits for him.
    tokio::time::sleep(Duration::from_millis(100)).await;
    let pending = arjun.pending().await;
    assert_eq!(pending.len(), 1);
    assert_eq!(
        (pending[0].kind, pending[0].id.as_str()),
        (EnvelopeKind::Delivered, "hello-1")
    );

    // Arjun opens the app: he gets the receipt again over the socket, Maya sees him come online.
    let mut arjun_ws = arjun.connect().await;
    let ServerFrame::Ready {
        partner: Some(presence),
    } = next_frame(&mut arjun_ws).await
    else {
        panic!()
    };
    assert!(presence.online);
    let ServerFrame::Envelope(receipt) = next_frame(&mut arjun_ws).await else {
        panic!()
    };
    assert_eq!(receipt.kind, EnvelopeKind::Delivered);
    send_frame(
        &mut arjun_ws,
        &ClientFrame::Ack {
            seqs: vec![receipt.seq.unwrap()],
        },
    )
    .await;
    let ServerFrame::Presence(p) = next_frame(&mut maya_ws).await else {
        panic!()
    };
    assert!(p.online);

    // Maya replies over the socket: accepted, delivered live, receipt comes back live.
    let reply = maya.seal("m-1", SendKind::Message, text("m-1", "hi love"), true);
    send_frame(&mut maya_ws, &ClientFrame::Send(reply.clone())).await;
    let ServerFrame::Accepted(a) = next_frame(&mut maya_ws).await else {
        panic!()
    };
    assert_eq!(a.id, "m-1");
    let ServerFrame::Envelope(incoming) = next_frame(&mut arjun_ws).await else {
        panic!()
    };
    assert_eq!(arjun.open(&incoming), text("m-1", "hi love"));
    send_frame(
        &mut arjun_ws,
        &ClientFrame::Ack {
            seqs: vec![incoming.seq.unwrap()],
        },
    )
    .await;
    let ServerFrame::Envelope(receipt) = next_frame(&mut maya_ws).await else {
        panic!()
    };
    assert_eq!(
        (receipt.kind, receipt.id.as_str()),
        (EnvelopeKind::Delivered, "m-1")
    );
    send_frame(
        &mut maya_ws,
        &ClientFrame::Ack {
            seqs: vec![receipt.seq.unwrap()],
        },
    )
    .await;

    // Resending the same id is harmless.
    send_frame(&mut maya_ws, &ClientFrame::Send(reply)).await;
    let ServerFrame::Accepted(again) = next_frame(&mut maya_ws).await else {
        panic!()
    };
    assert_eq!(again.id, "m-1");
    expect_no_frame(&mut arjun_ws).await;

    // Typing is forwarded live and never stored.
    let typing = arjun.seal(
        "t-1",
        SendKind::Ephemeral,
        Payload::Typing { active: true },
        false,
    );
    send_frame(&mut arjun_ws, &ClientFrame::Send(typing)).await;
    let ServerFrame::Accepted(_) = next_frame(&mut arjun_ws).await else {
        panic!()
    };
    let ServerFrame::Envelope(t) = next_frame(&mut maya_ws).await else {
        panic!()
    };
    assert_eq!((t.kind, t.seq), (EnvelopeKind::Ephemeral, None));
    assert_eq!(maya.open(&t), Payload::Typing { active: true });

    // Maya closes the app. Arjun is told when she was last seen.
    maya_ws.close(None).await.unwrap();
    let ServerFrame::Presence(p) = next_frame(&mut arjun_ws).await else {
        panic!()
    };
    assert!(!p.online && p.last_seen_ms.is_some());

    // While she is away: typing is dropped, only the newest status is kept,
    // and a new message wakes her phone.
    maya.request(reqwest::Method::PUT, "/v1/push-token")
        .json(&json!({ "token": "maya-phone" }))
        .send()
        .await
        .unwrap();
    let typing = arjun.seal(
        "t-2",
        SendKind::Ephemeral,
        Payload::Typing { active: true },
        false,
    );
    assert_eq!(arjun.post_envelope(&typing).await.status(), StatusCode::OK);
    for (id, battery) in [("s-1", 50), ("s-2", 4)] {
        let status = DeviceStatus {
            captured_at_ms: 1,
            battery_percent: Some(battery),
            shares: vec![ShareKind::Battery],
            platform: "android".into(),
            ..Default::default()
        };
        assert_eq!(
            arjun
                .post_envelope(&arjun.seal(id, SendKind::Status, Payload::Status { status }, false))
                .await
                .status(),
            StatusCode::OK
        );
    }
    assert!(server.pushes.lock().unwrap().is_empty());
    let late = arjun.seal("m-2", SendKind::Message, text("m-2", "call me?"), true);
    assert_eq!(arjun.post_envelope(&late).await.status(), StatusCode::OK);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(
        *server.pushes.lock().unwrap(),
        vec![("maya-phone".to_owned(), Urgency::High)]
    );

    let pending = maya.pending().await;
    assert_eq!(
        pending
            .iter()
            .map(|e| (e.kind, e.id.as_str()))
            .collect::<Vec<_>>(),
        vec![
            (EnvelopeKind::Status, "s-2"),
            (EnvelopeKind::Message, "m-2"),
        ]
    );
    // The dropped typing and status messages leave gaps the ratchet handles.
    let Payload::Status { status } = maya.open(&pending[0]) else {
        panic!()
    };
    assert_eq!(status.battery_percent, Some(4));
    assert_eq!(maya.open(&pending[1]), text("m-2", "call me?"));

    // Either partner can leave at any time; everything queued between them goes.
    let r = maya
        .request(reqwest::Method::DELETE, "/v1/partner")
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::NO_CONTENT);
    assert_eq!(
        next_frame(&mut arjun_ws).await,
        ServerFrame::Pairing {
            event: PairingEvent::Unpaired
        }
    );
    assert!(maya.pending().await.is_empty());
    assert!(maya.me().await.partner.is_none());
    let after = arjun.seal("m-3", SendKind::Message, text("m-3", "?"), true);
    assert_eq!(
        arjun.post_envelope(&after).await.status(),
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn sign_in_codes_are_single_use_and_rate_limited() {
    let server = Server::start().await;
    let http = &server.http;
    let start = |email: &'static str| {
        http.post(format!("{}/v1/auth/start", server.base))
            .json(&json!({ "email": email }))
            .send()
    };
    assert_eq!(
        start("bad-address").await.unwrap().status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        start("maya@example.com").await.unwrap().status(),
        StatusCode::NO_CONTENT
    );
    // A second code within 30 seconds is refused.
    assert_eq!(
        start("maya@example.com").await.unwrap().status(),
        StatusCode::TOO_MANY_REQUESTS
    );

    let code = server.code_in_last_mail("maya@example.com");
    let verify = |code: String| {
        http.post(format!("{}/v1/auth/verify", server.base))
            .json(&json!({ "email": "maya@example.com", "code": code }))
            .send()
    };
    let wrong = if code == "000000" { "111111" } else { "000000" };
    assert_eq!(
        verify(wrong.into()).await.unwrap().status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(verify(code.clone()).await.unwrap().status(), StatusCode::OK);
    assert_eq!(
        verify(code).await.unwrap().status(),
        StatusCode::BAD_REQUEST
    );
}

#[tokio::test]
async fn signing_in_on_a_new_phone_replaces_the_old_one() {
    let server = Server::start().await;
    let maya = server.sign_in("maya@example.com").await;
    let arjun = server.sign_in("arjun@example.com").await;
    pair(&maya, &arjun).await;
    let mut arjun_ws = arjun.connect().await;
    let ServerFrame::Ready { .. } = next_frame(&mut arjun_ws).await else {
        panic!()
    };

    // Something is waiting for Maya's old phone.
    let r = arjun
        .post_envelope(&SendRequest {
            id: "old".into(),
            kind: SendKind::Message,
            message_type: 0,
            body: "x".into(),
            push: false,
        })
        .await;
    assert_eq!(r.status(), StatusCode::OK);

    // Maya signs in on a new phone (the sign-in endpoint ends in exactly this call).
    let maya_id = maya.me().await.user_id;
    let new_token = "new-phone-token".to_owned();
    server
        .store
        .replace_device(&maya_id, &store::sha256(&[new_token.as_bytes()]))
        .await
        .unwrap();
    assert_eq!(
        maya.request(reqwest::Method::GET, "/v1/me")
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );

    let new_phone = Phone {
        base: server.base.clone(),
        http: server.http.clone(),
        token: new_token,
        account: CryptoAccount::new(),
        session: None,
    };
    let me = new_phone.me().await;
    assert!(!me.has_identity);
    assert_eq!(me.one_time_key_count, 0);
    assert!(
        new_phone.pending().await.is_empty(),
        "the old phone's envelopes cannot be read by the new one"
    );

    // Uploading the new keys tells Arjun to start over with them.
    let identity = new_phone.account.identity();
    let r = new_phone
        .request(reqwest::Method::PUT, "/v1/keys")
        .json(&json!({ "identity": {
            "curve25519": identity.curve25519,
            "ed25519": identity.ed25519,
            "curve25519_signature": identity.curve25519_signature,
        }}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    assert_eq!(
        next_frame(&mut arjun_ws).await,
        ServerFrame::Pairing {
            event: PairingEvent::KeysChanged
        }
    );
    let seen_by_arjun = arjun.me().await.partner.unwrap().identity.unwrap();
    assert_eq!(seen_by_arjun.ed25519, identity.ed25519);

    // A phone cannot silently swap its identity keys.
    let other = CryptoAccount::new().identity();
    let r = new_phone
        .request(reqwest::Method::PUT, "/v1/keys")
        .json(&json!({ "identity": {
            "curve25519": other.curve25519,
            "ed25519": other.ed25519,
            "curve25519_signature": other.curve25519_signature,
        }}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::CONFLICT);
}

async fn pair(inviter: &Phone, joiner: &Phone) {
    let invite: Value = inviter
        .request(reqwest::Method::POST, "/v1/invites")
        .json(&json!({}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let r = joiner
        .request(reqwest::Method::POST, "/v1/invites/accept")
        .json(&json!({ "code": invite["code"] }))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::OK);
}

#[tokio::test]
async fn deleting_the_account_unlinks_the_partner() {
    let server = Server::start().await;
    let maya = server.sign_in("maya@example.com").await;
    let arjun = server.sign_in("arjun@example.com").await;
    pair(&maya, &arjun).await;
    assert!(maya.me().await.partner.is_some());

    let r = arjun
        .request(reqwest::Method::DELETE, "/v1/me")
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::NO_CONTENT);
    assert!(maya.me().await.partner.is_none());
    let gone = arjun
        .request(reqwest::Method::GET, "/v1/me")
        .send()
        .await
        .unwrap();
    assert_eq!(gone.status(), StatusCode::UNAUTHORIZED);
}

/// Arjun starts an encrypted session with Maya (the first message must reach
/// her before she can answer).
async fn start_session(maya: &mut Phone, arjun: &mut Phone) {
    let claimed: ClaimedKey = arjun
        .request(reqwest::Method::POST, "/v1/partner/claim-key")
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let maya_identity = mami_core::IdentityBundle {
        curve25519: claimed.identity.curve25519.clone(),
        ed25519: claimed.identity.ed25519.clone(),
        curve25519_signature: claimed.identity.curve25519_signature.clone(),
    };
    let otk = mami_core::SignedOneTimeKey {
        key_id: claimed.one_time_key.key_id,
        key: claimed.one_time_key.key,
        signature: claimed.one_time_key.signature,
    };
    arjun.session = Some(arjun.account.start_session(maya_identity, otk).unwrap());
    let hello = arjun.seal(
        "hello",
        SendKind::Message,
        Payload::Hello {
            display_name: "Arjun".into(),
        },
        false,
    );
    assert_eq!(arjun.post_envelope(&hello).await.status(), StatusCode::OK);
    let envelope = maya
        .pending()
        .await
        .into_iter()
        .find(|e| e.id == "hello")
        .unwrap();
    let arjun_identity = arjun.account.identity();
    let accepted = maya
        .account
        .accept_session(
            arjun_identity,
            Ciphertext {
                message_type: envelope.message_type.unwrap(),
                body: envelope.body.clone().unwrap(),
            },
        )
        .unwrap();
    maya.session = Some(accepted.session);
    let r = maya
        .request(reqwest::Method::POST, "/v1/envelopes/ack")
        .json(&json!({ "seqs": [envelope.seq.unwrap()] }))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn attachments_are_only_for_the_couple() {
    let server = Server::start_with(Config {
        max_blob_bytes: 200 * 1024,
        blob_quota_bytes: 300 * 1024,
        ..Config::default()
    })
    .await;
    let mut maya = server.sign_in("maya@example.com").await;
    let mut arjun = server.sign_in("arjun@example.com").await;
    let stranger = server.sign_in("stranger@example.com").await;

    // Nothing to upload for before pairing.
    let r = maya
        .request(reqwest::Method::POST, "/v1/blobs")
        .body(vec![1u8; 10])
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::FORBIDDEN);

    pair(&maya, &arjun).await;
    start_session(&mut maya, &mut arjun).await;

    // Arjun encrypts a photo with the core and uploads only ciphertext.
    let dir = std::env::temp_dir().join(format!("mami-photo-{}", rand_suffix()));
    std::fs::create_dir_all(&dir).unwrap();
    let photo: Vec<u8> = (0..150_000u32).map(|i| (i % 253) as u8).collect();
    std::fs::write(dir.join("photo.jpg"), &photo).unwrap();
    let key = mami_core::encrypt_file(
        dir.join("photo.jpg").display().to_string(),
        dir.join("photo.enc").display().to_string(),
    )
    .unwrap();
    let encrypted = std::fs::read(dir.join("photo.enc")).unwrap();
    let r = arjun
        .request(reqwest::Method::POST, "/v1/blobs")
        .body(encrypted.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    let blob: BlobCreated = r.json().await.unwrap();
    assert_eq!(blob.size, encrypted.len() as u64);

    // The key travels inside an end-to-end encrypted message.
    let media = Payload::Media {
        id: "photo-1".into(),
        sent_at_ms: 5,
        reply_to: None,
        media: mami_core::MediaInfo {
            kind: mami_core::MediaKind::Photo,
            blob_id: blob.id.clone(),
            key: key.key.clone(),
            size: key.plain_size,
            mime: "image/jpeg".into(),
            name: None,
            width: Some(4000),
            height: Some(3000),
            duration_ms: None,
            thumbnail: Some("AAAA".into()),
            waveform: vec![],
        },
        caption: Some("us 💗".into()),
        view_once: false,
    };
    let sent = arjun.seal("photo-1", SendKind::Message, media.clone(), true);
    assert_eq!(arjun.post_envelope(&sent).await.status(), StatusCode::OK);
    let envelope = maya
        .pending()
        .await
        .into_iter()
        .find(|e| e.id == "photo-1")
        .unwrap();
    let Payload::Media { media: info, .. } = maya.open(&envelope) else {
        panic!()
    };

    // A stranger can't fetch it (and can't tell it exists).
    let path = format!("/v1/blobs/{}", info.blob_id);
    for who in [&stranger] {
        let r = who
            .request(reqwest::Method::GET, &path)
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::NOT_FOUND);
        let r = who
            .request(reqwest::Method::DELETE, &path)
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::NOT_FOUND);
    }
    let r = maya
        .request(reqwest::Method::GET, "/v1/blobs/../../etc/passwd")
        .send()
        .await
        .unwrap();
    assert!(!r.status().is_success());

    // Maya downloads, decrypts, and then deletes it from the server.
    let r = maya
        .request(reqwest::Method::GET, &path)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    std::fs::write(dir.join("download.enc"), r.bytes().await.unwrap()).unwrap();
    mami_core::decrypt_file(
        dir.join("download.enc").display().to_string(),
        dir.join("download.jpg").display().to_string(),
        info.key,
    )
    .unwrap();
    assert_eq!(std::fs::read(dir.join("download.jpg")).unwrap(), photo);
    let r = maya
        .request(reqwest::Method::DELETE, &path)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::NO_CONTENT);
    let r = maya
        .request(reqwest::Method::GET, &path)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::NOT_FOUND);
    assert_eq!(
        std::fs::read_dir(server.blobs.dir())
            .unwrap()
            .flat_map(|d| std::fs::read_dir(d.unwrap().path()).unwrap())
            .count(),
        0
    );

    // Too big for one file, then too much in total.
    let r = arjun
        .request(reqwest::Method::POST, "/v1/blobs")
        .body(vec![0u8; 250 * 1024])
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::PAYLOAD_TOO_LARGE);
    let mut kept = Vec::new();
    for _ in 0..2 {
        let r = arjun
            .request(reqwest::Method::POST, "/v1/blobs")
            .body(vec![0u8; 140 * 1024])
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        kept.push(r.json::<BlobCreated>().await.unwrap().id);
    }
    let r = arjun
        .request(reqwest::Method::POST, "/v1/blobs")
        .body(vec![0u8; 140 * 1024])
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::PAYLOAD_TOO_LARGE);
    let error: Value = r.json().await.unwrap();
    assert_eq!(error["error"], "quota_full");

    // Unlinking deletes every attachment between them.
    let r = maya
        .request(reqwest::Method::DELETE, "/v1/partner")
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::NO_CONTENT);
    for id in kept {
        let r = arjun
            .request(reqwest::Method::GET, &format!("/v1/blobs/{id}"))
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::NOT_FOUND);
    }
    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn calls_ring_right_away_and_ring_out() {
    let server = Server::start_with(Config {
        stun_urls: vec!["stun:stun.example.com:3478".into()],
        turn: Some(Turn {
            urls: vec!["turn:turn.example.com:3478?transport=udp".into()],
            secret: "north".into(),
            ttl_s: 3600,
        }),
        ..Config::default()
    })
    .await;
    let mut maya = server.sign_in("maya@example.com").await;
    let mut arjun = server.sign_in("arjun@example.com").await;
    pair(&maya, &arjun).await;
    start_session(&mut maya, &mut arjun).await;

    // Call servers come with short-lived TURN credentials.
    let ice: IceServers = arjun
        .request(reqwest::Method::GET, "/v1/calls/ice-servers")
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(ice.ice_servers.len(), 2);
    assert_eq!(ice.ice_servers[0].urls, vec!["stun:stun.example.com:3478"]);
    let turn = &ice.ice_servers[1];
    let username = turn.username.clone().unwrap();
    let (expires, user) = username.split_once(':').unwrap();
    assert!(expires.parse::<i64>().unwrap() > store::now_ms() / 1000 + 3000);
    assert_eq!(user, arjun.me().await.user_id);
    assert_eq!(
        turn.credential.clone().unwrap(),
        mami_server::api::turn_credentials("north", user, expires.parse().unwrap()).1
    );

    // Maya's app is closed: the offer wakes her phone with a call push.
    maya.request(reqwest::Method::PUT, "/v1/push-token")
        .json(&json!({ "token": "maya-phone" }))
        .send()
        .await
        .unwrap();
    let offer = arjun.seal(
        "call-1-offer",
        SendKind::Call,
        Payload::CallOffer {
            call_id: "call-1".into(),
            video: true,
            sdp: "v=0 ...".into(),
            sent_at_ms: 1,
        },
        true,
    );
    assert_eq!(arjun.post_envelope(&offer).await.status(), StatusCode::OK);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(
        *server.pushes.lock().unwrap(),
        vec![("maya-phone".to_owned(), Urgency::Call)]
    );

    // She opens the app, gets the offer and answers over the live socket.
    let mut maya_ws = maya.connect().await;
    let _ready = next_frame(&mut maya_ws).await;
    let pending = maya.pending().await;
    let call = pending
        .iter()
        .find(|e| e.kind == EnvelopeKind::Call)
        .unwrap();
    assert!(matches!(
        maya.open(call),
        Payload::CallOffer { video: true, .. }
    ));
    send_frame(
        &mut maya_ws,
        &ClientFrame::Ack {
            seqs: vec![call.seq.unwrap()],
        },
    )
    .await;
    let mut arjun_ws = arjun.connect().await;
    let _ready = next_frame(&mut arjun_ws).await;
    let answer = maya.seal(
        "call-1-answer",
        SendKind::Call,
        Payload::CallAnswer {
            call_id: "call-1".into(),
            sdp: "v=0 answer".into(),
        },
        false,
    );
    send_frame(&mut maya_ws, &ClientFrame::Send(answer)).await;
    loop {
        // The receipt for the earlier hello may arrive first.
        if let ServerFrame::Envelope(e) = next_frame(&mut arjun_ws).await
            && e.kind == EnvelopeKind::Call
        {
            assert!(matches!(arjun.open(&e), Payload::CallAnswer { .. }));
            break;
        }
    }
    assert!(
        maya.pending()
            .await
            .iter()
            .all(|e| e.kind != EnvelopeKind::Call)
    );

    // An offer nobody picks up within a minute rings out: it's never delivered late.
    let stale = arjun.seal(
        "call-2-offer",
        SendKind::Call,
        Payload::CallOffer {
            call_id: "call-2".into(),
            video: false,
            sdp: "v=0".into(),
            sent_at_ms: 2,
        },
        false,
    );
    drop(maya_ws);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(arjun.post_envelope(&stale).await.status(), StatusCode::OK);
    sqlx::query("UPDATE queue SET created_at = created_at - 120000 WHERE kind = 'call'")
        .execute(&server.store.pool)
        .await
        .unwrap();
    assert!(
        maya.pending()
            .await
            .iter()
            .all(|e| e.kind != EnvelopeKind::Call)
    );
    // The stale offer and the answer Arjun never acknowledged.
    assert_eq!(server.store.purge_stale_calls().await.unwrap(), 2);
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM queue WHERE kind = 'call'")
        .fetch_one(&server.store.pool)
        .await
        .unwrap();
    assert_eq!(left, 0);
}

#[tokio::test]
async fn upgrading_the_queue_keeps_counting_sequence_numbers() {
    // A database created by the first release, with a message already
    // delivered and forgotten.
    let path = std::env::temp_dir().join(format!("mami-upgrade-{}.db", rand_suffix()));
    let url = format!("sqlite://{}?mode=rwc", path.display());
    let pool = sqlx::SqlitePool::connect(&url).await.unwrap();
    sqlx::raw_sql(include_str!("../migrations/0001_init.sql"))
        .execute(&pool)
        .await
        .unwrap();
    sqlx::raw_sql(
        "INSERT INTO users (id, email, created_at) VALUES ('a', 'a@x.y', 0), ('b', 'b@x.y', 0);
         INSERT INTO queue (recipient_id, sender_id, kind, client_id, created_at)
             VALUES ('a', 'b', 'message', 'm1', 0), ('a', 'b', 'message', 'm2', 0);
         DELETE FROM queue WHERE client_id = 'm2';",
    )
    .execute(&pool)
    .await
    .unwrap();
    sqlx::raw_sql(include_str!("../migrations/0002_blobs_and_calls.sql"))
        .execute(&pool)
        .await
        .unwrap();
    sqlx::raw_sql(include_str!("../migrations/0003_live_location.sql"))
        .execute(&pool)
        .await
        .unwrap();
    let seq: i64 = sqlx::query_scalar(
        "INSERT INTO queue (recipient_id, sender_id, kind, client_id, created_at)
         VALUES ('a', 'b', 'call', 'c1', 0) RETURNING seq",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(seq, 3, "sequence numbers must never be reused");
    let kept: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM queue WHERE client_id = 'm1'")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(kept, 1);
}

#[tokio::test]
async fn only_the_newest_live_location_waits() {
    let server = Server::start().await;
    let mut maya = server.sign_in("maya@example.com").await;
    let mut arjun = server.sign_in("arjun@example.com").await;
    pair(&maya, &arjun).await;
    start_session(&mut maya, &mut arjun).await;

    for (i, lat) in [27.70, 27.71, 27.72].iter().enumerate() {
        let update = arjun.seal(
            &format!("loc-{i}"),
            SendKind::Location,
            Payload::Location {
                lat: *lat,
                lng: 85.32,
                accuracy_m: Some(10.0),
                speed_mps: None,
                at_ms: i as i64,
                until_ms: 1_000,
            },
            false,
        );
        assert_eq!(arjun.post_envelope(&update).await.status(), StatusCode::OK);
    }
    let waiting: Vec<_> = maya
        .pending()
        .await
        .into_iter()
        .filter(|e| e.kind == EnvelopeKind::Location)
        .collect();
    assert_eq!(waiting.len(), 1);
    assert_eq!(waiting[0].id, "loc-2");
    // Olm can skip the replaced messages and still open the newest one.
    let Payload::Location { lat, .. } = maya.open(&waiting[0]) else {
        panic!()
    };
    assert_eq!(lat, 27.72);
    assert!(server.pushes.lock().unwrap().is_empty());
}
