//! The live connection used while the app is open: instant delivery, receipts,
//! typing and presence.

use std::sync::Arc;
use std::time::Duration;

use axum::extract::State;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::response::Response;
use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc;

use crate::AppState;
use crate::api::{AuthUser, acknowledge, deliver, presence_of};
use crate::protocol::{ClientFrame, PartnerPresence, ServerFrame};
use crate::store::now_ms;

/// Phones ping every 30 seconds; a connection silent for longer than this is dead.
const IDLE_TIMEOUT: Duration = Duration::from_secs(90);

pub async fn upgrade(
    ws: WebSocketUpgrade,
    State(state): State<Arc<AppState>>,
    AuthUser(me): AuthUser,
) -> Response {
    ws.max_message_size(256 * 1024)
        .on_upgrade(move |socket| run(socket, state, me))
}

async fn run(socket: WebSocket, state: Arc<AppState>, me: String) {
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<ServerFrame>();
    // The hub owns the only strong sender, so `Hub::kick` ends the connection.
    let replies = tx.downgrade();
    let (connection_id, came_online) = state.hub.connect(&me, tx);

    let partner = state
        .store
        .user(&me)
        .await
        .ok()
        .flatten()
        .and_then(|u| u.partner_id);
    let partner_presence = match &partner {
        Some(p) => presence_of(&state, p).await.ok(),
        None => None,
    };
    reply(
        &replies,
        ServerFrame::Ready {
            partner: partner_presence,
        },
    );
    // Anything that arrived while the app was closed.
    if let Ok(pending) = state.store.pending(&me, 0, 500).await {
        for envelope in pending {
            reply(&replies, ServerFrame::Envelope(envelope));
        }
    }
    if came_online && let Some(partner) = &partner {
        state.hub.send(
            partner,
            &ServerFrame::Presence(PartnerPresence {
                online: true,
                last_seen_ms: None,
            }),
        );
    }

    let writer = async {
        while let Some(frame) = rx.recv().await {
            let Ok(text) = serde_json::to_string(&frame) else {
                continue;
            };
            if sink.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
        let _ = sink.close().await;
    };

    let reader = async {
        while let Ok(Some(Ok(message))) = tokio::time::timeout(IDLE_TIMEOUT, stream.next()).await {
            let text = match message {
                Message::Text(text) => text,
                Message::Close(_) => break,
                _ => continue,
            };
            match serde_json::from_str::<ClientFrame>(&text) {
                Ok(ClientFrame::Send(request)) => {
                    let id = request.id.clone();
                    match deliver(&state, &me, request).await {
                        Ok(accepted) => reply(&replies, ServerFrame::Accepted(accepted)),
                        Err(e) => reply(
                            &replies,
                            ServerFrame::Rejected {
                                id,
                                reason: e.code().to_owned(),
                            },
                        ),
                    }
                }
                Ok(ClientFrame::Ack { seqs }) => {
                    if let Err(e) = acknowledge(&state, &me, &seqs).await {
                        tracing::warn!(error = %e, "ack failed");
                    }
                }
                Err(e) => tracing::debug!(error = %e, "ignoring malformed frame"),
            }
        }
    };

    tokio::select! {
        _ = writer => {}
        _ = reader => {}
    }

    if state.hub.disconnect(&me, connection_id) {
        let now = now_ms();
        if let Err(e) = state.store.set_last_seen(&me, now).await {
            tracing::warn!(error = %e, "could not save last seen");
        }
        // Look the partner up again: they may have changed while we were connected.
        if let Some(partner) = state
            .store
            .user(&me)
            .await
            .ok()
            .flatten()
            .and_then(|u| u.partner_id)
        {
            state.hub.send(
                &partner,
                &ServerFrame::Presence(PartnerPresence {
                    online: false,
                    last_seen_ms: Some(now),
                }),
            );
        }
    }
}

fn reply(replies: &mpsc::WeakUnboundedSender<ServerFrame>, frame: ServerFrame) {
    if let Some(tx) = replies.upgrade() {
        let _ = tx.send(frame);
    }
}
