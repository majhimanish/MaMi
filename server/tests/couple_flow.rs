//! Drives two simulated phones through the whole product flow against a real
//! server, using the real encryption core.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use mami_core::{Ciphertext, CryptoAccount, CryptoSession, DeviceStatus, Payload, ShareKind};
use mami_server::api::{AuthVerified, ClaimedKey, MeResponse, PartnerView};
use mami_server::mail::{Mailer, SentMail};
use mami_server::protocol::{
    ClientFrame, Envelope, EnvelopeKind, PairingEvent, SendKind, SendRequest, ServerFrame,
};
use mami_server::push::Pusher;
use mami_server::store::{self, Store};
use mami_server::{AppState, router};
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
    pushes: Arc<Mutex<Vec<String>>>,
}

impl Server {
    async fn start() -> Self {
        let path = std::env::temp_dir().join(format!("mami-test-{}.db", rand_suffix()));
        let pool = store::open(&format!("sqlite://{}", path.display()))
            .await
            .unwrap();
        let mails = Arc::new(Mutex::new(Vec::new()));
        let pushes = Arc::new(Mutex::new(Vec::new()));
        let store = Store::new(pool);
        let state = AppState::new(
            store.clone(),
            Mailer::Memory(mails.clone()),
            Pusher::Memory(pushes.clone()),
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
        vec!["maya-phone".to_owned()]
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
