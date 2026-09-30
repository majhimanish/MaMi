//! Everything the two phones say to each other.
//!
//! A [`Payload`] is serialised to JSON, padded so the ciphertext length only
//! reveals a rough size bucket, and then encrypted by the session. Unknown
//! payload kinds from a newer app version decode as [`Payload::Unknown`] and
//! are ignored instead of breaking the conversation.

use serde::{Deserialize, Serialize};

use crate::CoreError;

/// Plaintexts are padded to a multiple of this many bytes.
const PADDING_BLOCK: usize = 64;
/// ISO/IEC 7816-4 padding marker. Never the last byte of valid UTF-8 JSON.
const PADDING_MARKER: u8 = 0x80;

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum Payload {
    /// First message on a new session, so the receiver can finish pairing.
    Hello {
        display_name: String,
    },
    Text {
        id: String,
        body: String,
        sent_at_ms: i64,
        reply_to: Option<String>,
    },
    /// These messages were shown on the partner's screen.
    Read {
        ids: Vec<String>,
        read_at_ms: i64,
    },
    Typing {
        active: bool,
    },
    Status {
        status: DeviceStatus,
    },
    /// A "thinking of you" tap.
    Nudge {
        id: String,
        kind: NudgeKind,
        sent_at_ms: i64,
    },
    /// Something the phone says on its own, like "battery almost empty".
    Alert {
        id: String,
        kind: AlertKind,
        battery_percent: Option<i32>,
        sent_at_ms: i64,
    },
    /// A payload kind this app version does not know yet.
    #[serde(other)]
    Unknown,
}

/// A snapshot of the sender's phone. Every field is optional: the sender only
/// fills in what they chose to share.
#[derive(uniffi::Record, Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(default)]
pub struct DeviceStatus {
    pub captured_at_ms: i64,
    pub battery_percent: Option<i32>,
    pub charging: Option<bool>,
    pub network: Option<NetworkKind>,
    /// 0 (no signal) to 4 (full bars).
    pub signal_level: Option<i32>,
    pub ringer: Option<RingerMode>,
    pub do_not_disturb: Option<bool>,
    /// IANA time zone, for example "Asia/Kathmandu".
    pub timezone: Option<String>,
    pub utc_offset_minutes: Option<i32>,
    /// A status the person set themselves, like "🚗 Driving".
    pub quick_status: Option<QuickStatus>,
    /// What the sender shares. The receiver only shows what it shares back.
    pub shares: Vec<ShareKind>,
    /// "android" or "ios".
    pub platform: String,
}

#[derive(uniffi::Record, Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct QuickStatus {
    pub emoji: String,
    pub label: String,
    pub until_ms: Option<i64>,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum ShareKind {
    /// Battery level and charging.
    Battery,
    /// Wi-Fi or mobile data, and signal strength.
    Network,
    /// Silent, vibrate and Do Not Disturb.
    Ringer,
    /// Time zone, so the partner can see the local time.
    LocalTime,
    #[serde(other)]
    Unknown,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum NetworkKind {
    Wifi,
    Cellular,
    Ethernet,
    Offline,
    #[serde(other)]
    Other,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RingerMode {
    Normal,
    Vibrate,
    Silent,
    #[serde(other)]
    Unknown,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum NudgeKind {
    ThinkingOfYou,
    Hug,
    Kiss,
    MissYou,
    #[serde(other)]
    Unknown,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AlertKind {
    /// Sent automatically when the battery is about to run out.
    BatteryCritical,
    #[serde(other)]
    Unknown,
}

/// Removes everything from the partner's status that I don't share myself,
/// so sharing always stays two-way.
#[uniffi::export]
pub fn visible_status(partner: DeviceStatus, my_shares: Vec<ShareKind>) -> DeviceStatus {
    let both = |kind: ShareKind| my_shares.contains(&kind) && partner.shares.contains(&kind);
    let mut visible = partner.clone();
    if !both(ShareKind::Battery) {
        visible.battery_percent = None;
        visible.charging = None;
    }
    if !both(ShareKind::Network) {
        visible.network = None;
        visible.signal_level = None;
    }
    if !both(ShareKind::Ringer) {
        visible.ringer = None;
        visible.do_not_disturb = None;
    }
    if !both(ShareKind::LocalTime) {
        visible.timezone = None;
        visible.utc_offset_minutes = None;
    }
    visible
}

/// JSON form of a payload, for debugging and for local storage.
#[uniffi::export]
pub fn payload_to_json(payload: Payload) -> Result<String, CoreError> {
    serde_json::to_string(&payload).map_err(CoreError::malformed)
}

#[uniffi::export]
pub fn payload_from_json(json: String) -> Result<Payload, CoreError> {
    serde_json::from_str(&json).map_err(CoreError::malformed)
}

pub(crate) fn seal(payload: &Payload) -> Result<Vec<u8>, CoreError> {
    let mut bytes = serde_json::to_vec(payload).map_err(CoreError::malformed)?;
    bytes.push(PADDING_MARKER);
    let padded_len = bytes.len().div_ceil(PADDING_BLOCK) * PADDING_BLOCK;
    bytes.resize(padded_len, 0);
    Ok(bytes)
}

pub(crate) fn open(bytes: &[u8]) -> Result<Payload, CoreError> {
    let end = bytes
        .iter()
        .rposition(|b| *b != 0)
        .filter(|i| bytes[*i] == PADDING_MARKER)
        .ok_or_else(|| CoreError::malformed("missing padding"))?;
    serde_json::from_slice(&bytes[..end]).map_err(CoreError::malformed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn padding_round_trip_and_hides_length() {
        let short = Payload::Text {
            id: "1".into(),
            body: "hi".into(),
            sent_at_ms: 5,
            reply_to: None,
        };
        let longer = Payload::Text {
            id: "1".into(),
            body: "hello!".into(),
            sent_at_ms: 5,
            reply_to: None,
        };
        let a = seal(&short).unwrap();
        let b = seal(&longer).unwrap();
        assert_eq!(a.len() % PADDING_BLOCK, 0);
        assert_eq!(a.len(), b.len());
        assert_eq!(open(&a).unwrap(), short);
        assert!(open(b"{}").is_err());
    }

    #[test]
    fn wire_format_is_stable() {
        let json = payload_to_json(Payload::Nudge {
            id: "n1".into(),
            kind: NudgeKind::ThinkingOfYou,
            sent_at_ms: 9,
        })
        .unwrap();
        assert_eq!(
            json,
            r#"{"t":"nudge","id":"n1","kind":"thinking_of_you","sent_at_ms":9}"#
        );
    }

    #[test]
    fn unknown_kinds_from_newer_apps_are_tolerated() {
        assert_eq!(
            payload_from_json(r#"{"t":"voice_note","url":"x"}"#.into()).unwrap(),
            Payload::Unknown
        );
        let nudge =
            payload_from_json(r#"{"t":"nudge","id":"n","kind":"high_five","sent_at_ms":1}"#.into())
                .unwrap();
        assert_eq!(
            nudge,
            Payload::Nudge {
                id: "n".into(),
                kind: NudgeKind::Unknown,
                sent_at_ms: 1
            }
        );
        let status = payload_from_json(
            r#"{"t":"status","status":{"captured_at_ms":3,"network":"satellite","shares":["battery","telepathy"],"new_field":1}}"#.into(),
        )
        .unwrap();
        let Payload::Status { status } = status else {
            panic!()
        };
        assert_eq!(status.network, Some(NetworkKind::Other));
        assert_eq!(status.shares, vec![ShareKind::Battery, ShareKind::Unknown]);
        assert_eq!(status.battery_percent, None);
    }

    #[test]
    fn sharing_is_symmetric() {
        let partner = DeviceStatus {
            battery_percent: Some(40),
            charging: Some(true),
            network: Some(NetworkKind::Wifi),
            signal_level: Some(3),
            ringer: Some(RingerMode::Silent),
            timezone: Some("Asia/Kathmandu".into()),
            utc_offset_minutes: Some(345),
            shares: vec![ShareKind::Battery, ShareKind::Network, ShareKind::LocalTime],
            ..Default::default()
        };
        // I share battery and ringer; they share battery, network and time.
        let visible = visible_status(partner, vec![ShareKind::Battery, ShareKind::Ringer]);
        assert_eq!(visible.battery_percent, Some(40));
        assert_eq!(visible.charging, Some(true));
        assert_eq!(visible.network, None);
        assert_eq!(visible.signal_level, None);
        assert_eq!(visible.ringer, None);
        assert_eq!(visible.timezone, None);
    }
}
