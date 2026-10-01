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
        /// A preview of the first link, made on the sender's phone so the
        /// server never learns which links the two of you share.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        link: Option<LinkPreview>,
        /// A scheduled message: it arrives now but only shows (and notifies)
        /// at this moment, so it's on time even if a phone is offline then.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        deliver_at_ms: Option<i64>,
    },
    /// A photo, video, voice note or file. The file itself waits on the
    /// server as an encrypted blob; the key to open it is in here.
    Media {
        id: String,
        sent_at_ms: i64,
        reply_to: Option<String>,
        media: MediaInfo,
        caption: Option<String>,
        /// The receiver can open it once, then it's gone from their phone.
        #[serde(default)]
        view_once: bool,
    },
    /// Sets (or, with no emoji, removes) my reaction to a message.
    Reaction {
        target_id: String,
        emoji: Option<String>,
        sent_at_ms: i64,
    },
    /// New text for one of my earlier messages (or a photo's caption).
    Edit {
        target_id: String,
        body: String,
        edited_at_ms: i64,
    },
    /// Removes one of my messages from both phones.
    Unsend {
        target_id: String,
        at_ms: i64,
    },
    /// Pins a message to the top of the chat, for both of us.
    Pin {
        target_id: String,
        pinned: bool,
        at_ms: i64,
    },
    /// A view-once photo or video was opened.
    Opened {
        target_id: String,
        at_ms: i64,
    },
    /// Starts a voice or video call. `sdp` is the WebRTC offer; its DTLS
    /// fingerprint travels end-to-end encrypted, so nobody can sit in the
    /// middle of the call.
    CallOffer {
        call_id: String,
        video: bool,
        sdp: String,
        sent_at_ms: i64,
    },
    /// The callee's phone is ringing.
    CallRinging {
        call_id: String,
    },
    CallAnswer {
        call_id: String,
        sdp: String,
    },
    /// Network paths the other phone can try.
    CallCandidates {
        call_id: String,
        candidates: Vec<IceCandidate>,
    },
    /// Camera or microphone switched on or off during a call.
    CallMedia {
        call_id: String,
        camera_on: bool,
        muted: bool,
    },
    CallEnd {
        call_id: String,
        reason: CallEndReason,
    },
    /// Something the two of you share, like the day you got together. The
    /// newest change wins on both phones.
    Together {
        key: TogetherKey,
        /// A date ("2024-02-14"), a time in ms, or text, depending on the key.
        value: Option<String>,
        updated_at_ms: i64,
    },
    /// How I'm feeling.
    Mood {
        id: String,
        mood: String,
        note: Option<String>,
        at_ms: i64,
    },
    /// A love letter. With `open_at_ms` it stays sealed until then.
    Letter {
        id: String,
        title: String,
        body: String,
        /// The paper it's written on, like "cream" or "rose".
        paper: String,
        open_at_ms: Option<i64>,
        sent_at_ms: i64,
    },
    /// "Home safe", "Leaving now", "Arrived".
    CheckIn {
        id: String,
        kind: CheckInKind,
        sent_at_ms: i64,
    },
    /// I started sharing my live location until `until_ms`.
    LiveLocation {
        id: String,
        until_ms: i64,
        sent_at_ms: i64,
    },
    /// Where I am now, while sharing live location.
    Location {
        lat: f64,
        lng: f64,
        accuracy_m: Option<f32>,
        speed_mps: Option<f32>,
        at_ms: i64,
        until_ms: i64,
    },
    /// I stopped sharing my live location.
    LiveLocationEnd {
        id: String,
        at_ms: i64,
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

/// Everything needed to download, open and show an attachment.
#[derive(uniffi::Record, Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct MediaInfo {
    pub kind: MediaKind,
    /// Where the encrypted file waits on the server.
    pub blob_id: String,
    /// The file key from [`crate::encrypt_file`], base64.
    pub key: String,
    /// Size of the original file in bytes.
    pub size: u64,
    pub mime: String,
    /// The original file name, for documents.
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub width: Option<i32>,
    #[serde(default)]
    pub height: Option<i32>,
    /// Length of a video or voice note.
    #[serde(default)]
    pub duration_ms: Option<i64>,
    /// A tiny blurred JPEG (base64) shown while the real file downloads.
    #[serde(default)]
    pub thumbnail: Option<String>,
    /// Loudness of a voice note over time, 0 to 255.
    #[serde(default)]
    pub waveform: Vec<u8>,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum MediaKind {
    Photo,
    Video,
    Voice,
    File,
    #[serde(other)]
    Unknown,
}

#[derive(uniffi::Record, Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct LinkPreview {
    pub url: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    /// A small JPEG (base64) of the page's preview image.
    #[serde(default)]
    pub image: Option<String>,
}

#[derive(uniffi::Record, Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct IceCandidate {
    pub sdp_mid: Option<String>,
    pub sdp_m_line_index: i32,
    pub candidate: String,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TogetherKey {
    /// The day you got together, "yyyy-mm-dd".
    Since,
    /// When you'll next see each other, ms since 1970.
    NextMeeting,
    /// What the next meeting is ("Dashain in Pokhara").
    NextMeetingLabel,
    #[serde(other)]
    Unknown,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CheckInKind {
    HomeSafe,
    Leaving,
    Arrived,
    #[serde(other)]
    Unknown,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CallEndReason {
    /// Someone hung up (or cancelled before it was answered).
    Hangup,
    Declined,
    /// Already on another call.
    Busy,
    /// Nobody answered.
    NoAnswer,
    /// The connection could not be set up or was lost.
    Failed,
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
    /// Sharing is paused until then: the status says so honestly instead of
    /// going quiet.
    #[uniffi(default = None)]
    pub paused_until_ms: Option<i64>,
    /// A status the phone set by itself, like "🚗 Driving".
    #[uniffi(default = None)]
    pub auto_status: Option<QuickStatus>,
    /// When the phone was first used this morning.
    #[uniffi(default = None)]
    pub woke_at_ms: Option<i64>,
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

/// The web links in a message, in order, for previews and the "Links" tab.
/// Finds `http://`, `https://` and `www.` addresses; trailing punctuation
/// like the full stop at the end of a sentence is not part of the link.
#[uniffi::export]
pub fn extract_links(text: String) -> Vec<String> {
    text.split(|c: char| c.is_whitespace() || matches!(c, '<' | '>' | '"'))
        .filter_map(|word| {
            let word = word.trim_start_matches(['(', '[', '{', '\'', '*', '_']);
            let lower = word.to_ascii_lowercase();
            if !(lower.starts_with("http://")
                || lower.starts_with("https://")
                || lower.starts_with("www."))
            {
                return None;
            }
            let trailing = ['.', ',', '!', '?', ':', ';', '\'', '*', '_'];
            let mut link = word.trim_end_matches(trailing);
            // Keep a closing bracket only if the link opened one, as in Wikipedia URLs.
            while link.ends_with(')') && link.matches(')').count() > link.matches('(').count() {
                link = link[..link.len() - 1].trim_end_matches(trailing);
            }
            let host = link
                .split_once("://")
                .map_or(link, |(_, rest)| rest)
                .split(['/', '?', '#'])
                .next()
                .unwrap_or("");
            (host.contains('.') && !host.starts_with('.') && !host.ends_with('.'))
                .then(|| link.to_owned())
        })
        .collect()
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
            link: None,
            deliver_at_ms: None,
        };
        let longer = Payload::Text {
            id: "1".into(),
            body: "hello!".into(),
            sent_at_ms: 5,
            reply_to: None,
            link: None,
            deliver_at_ms: None,
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
    fn new_payloads_round_trip() {
        let media = Payload::Media {
            id: "m1".into(),
            sent_at_ms: 10,
            reply_to: Some("t1".into()),
            media: MediaInfo {
                kind: MediaKind::Voice,
                blob_id: "b1".into(),
                key: "a2V5".into(),
                size: 12_345,
                mime: "audio/mp4".into(),
                name: None,
                width: None,
                height: None,
                duration_ms: Some(4_200),
                thumbnail: None,
                waveform: vec![0, 128, 255],
            },
            caption: None,
            view_once: false,
        };
        let call = Payload::CallCandidates {
            call_id: "c1".into(),
            candidates: vec![IceCandidate {
                sdp_mid: Some("0".into()),
                sdp_m_line_index: 0,
                candidate: "candidate:1 1 udp 2122260223 192.0.2.1 54400 typ host".into(),
            }],
        };
        let text = Payload::Text {
            id: "t2".into(),
            body: "look https://example.com".into(),
            sent_at_ms: 11,
            reply_to: None,
            link: Some(LinkPreview {
                url: "https://example.com".into(),
                title: Some("Example".into()),
                description: None,
                image: None,
            }),
            deliver_at_ms: Some(1_800_000_000_000),
        };
        let letter = Payload::Letter {
            id: "l1".into(),
            title: "For you".into(),
            body: "Dear Maya,\n…".into(),
            paper: "rose".into(),
            open_at_ms: Some(1_800_000_000_000),
            sent_at_ms: 12,
        };
        let location = Payload::Location {
            lat: 28.2096,
            lng: 83.9856,
            accuracy_m: Some(12.5),
            speed_mps: None,
            at_ms: 13,
            until_ms: 14,
        };
        let together = Payload::Together {
            key: TogetherKey::Since,
            value: Some("2024-02-14".into()),
            updated_at_ms: 15,
        };
        for payload in [media, call, text, letter, location, together] {
            let sealed = seal(&payload).unwrap();
            assert_eq!(open(&sealed).unwrap(), payload);
        }

        // Older phones leave out the optional fields.
        let minimal = payload_from_json(
            r#"{"t":"media","id":"m","sent_at_ms":1,"reply_to":null,"caption":null,
                "media":{"kind":"sticker","blob_id":"b","key":"k","size":1,"mime":"x"}}"#
                .into(),
        )
        .unwrap();
        let Payload::Media {
            media, view_once, ..
        } = minimal
        else {
            panic!()
        };
        assert_eq!(media.kind, MediaKind::Unknown);
        assert!(media.waveform.is_empty());
        assert!(!view_once);
        let end = payload_from_json(r#"{"t":"call_end","call_id":"c","reason":"exploded"}"#.into())
            .unwrap();
        assert_eq!(
            end,
            Payload::CallEnd {
                call_id: "c".into(),
                reason: CallEndReason::Unknown
            }
        );
    }

    #[test]
    fn links_are_found_without_trailing_punctuation() {
        assert_eq!(
            extract_links(
                "Look: https://example.com/a?b=1. And www.mami.app, \
                 (https://en.wikipedia.org/wiki/Momo_(food)) and not.a.link or http://x"
                    .into()
            ),
            vec![
                "https://example.com/a?b=1",
                "www.mami.app",
                "https://en.wikipedia.org/wiki/Momo_(food)",
            ]
        );
        assert!(extract_links("no links here".into()).is_empty());
        assert_eq!(
            extract_links("HTTPS://EXAMPLE.COM!".into()),
            vec!["HTTPS://EXAMPLE.COM"]
        );
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
