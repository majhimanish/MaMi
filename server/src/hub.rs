//! Who is connected right now. A user counts as online while at least one
//! WebSocket is open, which on the phone means the app is on screen.

use std::collections::HashMap;
use std::sync::Mutex;

use tokio::sync::mpsc::UnboundedSender;

use crate::protocol::ServerFrame;

type Connections = HashMap<String, Vec<(u64, UnboundedSender<ServerFrame>)>>;

#[derive(Default)]
pub struct Hub {
    connections: Mutex<Connections>,
    next_id: Mutex<u64>,
}

impl Hub {
    /// Registers a connection. Returns its id and whether the user just came
    /// online.
    pub fn connect(&self, user_id: &str, tx: UnboundedSender<ServerFrame>) -> (u64, bool) {
        let id = {
            let mut next = self.next_id.lock().unwrap_or_else(|e| e.into_inner());
            *next += 1;
            *next
        };
        let mut connections = self.lock();
        let list = connections.entry(user_id.to_owned()).or_default();
        let came_online = list.is_empty();
        list.push((id, tx));
        (id, came_online)
    }

    /// Returns true if that was the user's last connection.
    pub fn disconnect(&self, user_id: &str, connection_id: u64) -> bool {
        let mut connections = self.lock();
        let Some(list) = connections.get_mut(user_id) else {
            return false;
        };
        list.retain(|(id, _)| *id != connection_id);
        if list.is_empty() {
            connections.remove(user_id);
            true
        } else {
            false
        }
    }

    pub fn is_online(&self, user_id: &str) -> bool {
        self.lock().contains_key(user_id)
    }

    /// Sends a frame to every open connection of the user. Returns false if
    /// the user has none.
    pub fn send(&self, user_id: &str, frame: &ServerFrame) -> bool {
        let connections = self.lock();
        let Some(list) = connections.get(user_id) else {
            return false;
        };
        let mut delivered = false;
        for (_, tx) in list {
            delivered |= tx.send(frame.clone()).is_ok();
        }
        delivered
    }

    /// Closes all of the user's connections (used when they sign out or are
    /// signed in elsewhere).
    pub fn kick(&self, user_id: &str) {
        self.lock().remove(user_id);
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Connections> {
        self.connections.lock().unwrap_or_else(|e| e.into_inner())
    }
}
