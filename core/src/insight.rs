//! Turns the partner's last known status into plain-language hints, so the
//! app can answer "why haven't they replied?" before anyone starts to worry.
//!
//! The logic lives in the core so Android and iOS always tell the same story.

use crate::payload::{DeviceStatus, NetworkKind, RingerMode};

/// At or below this battery level, a silent phone has probably switched off.
const CRITICAL_BATTERY: i32 = 5;
const LOW_BATTERY: i32 = 15;
/// How long we must have heard nothing before guessing the phone died.
const DIED_AFTER_MS: i64 = 10 * 60 * 1000;
const NIGHT_STARTS: i32 = 23;
const NIGHT_ENDS: i32 = 7;
/// "Woke up at 7:12" is news for a few hours.
const WOKE_RECENT_MS: i64 = 4 * 60 * 60 * 1000;

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct Presence {
    /// The app is open on the partner's phone right now.
    pub online: bool,
    /// When the app was last open, if known.
    pub last_seen_ms: Option<i64>,
}

#[derive(uniffi::Enum, Clone, Debug, PartialEq)]
pub enum Insight {
    OnlineNow,
    /// They paused sharing; nothing else can be said about their phone.
    SharingPaused {
        until_ms: Option<i64>,
    },
    /// They picked their phone up this morning.
    WokeUp {
        at_ms: i64,
    },
    /// They set a status like "🚗 Driving" (or their phone noticed it: `automatic`).
    Busy {
        emoji: String,
        label: String,
        until_ms: Option<i64>,
        automatic: bool,
    },
    /// The battery was almost empty and the phone has gone quiet.
    PhoneMayHaveDied {
        battery_percent: i32,
        since_ms: i64,
    },
    BatteryLow {
        battery_percent: i32,
        charging: bool,
    },
    /// It is night where they are.
    ProbablyAsleep {
        local_hour: i32,
        local_minute: i32,
    },
    /// Their phone is on silent/vibrate or Do Not Disturb.
    Silenced {
        do_not_disturb: bool,
        ringer: Option<RingerMode>,
    },
    WeakSignal {
        network: NetworkKind,
        level: i32,
    },
    Offline,
    LastSeen {
        at_ms: i64,
    },
}

/// Hints about the partner, most important first. `status` should already be
/// filtered with [`crate::visible_status`].
#[uniffi::export]
pub fn partner_insights(
    status: Option<DeviceStatus>,
    presence: Presence,
    now_ms: i64,
) -> Vec<Insight> {
    let mut out = Vec::new();
    if presence.online {
        out.push(Insight::OnlineNow);
    }
    let Some(status) = status else {
        push_last_seen(&mut out, &presence);
        return out;
    };

    // While paused there's nothing to go on, and silence must not look like a dead phone.
    if let Some(until) = status.paused_until_ms
        && until > now_ms
    {
        out.push(Insight::SharingPaused {
            until_ms: Some(until),
        });
        push_last_seen(&mut out, &presence);
        return out;
    }

    let current = |q: &&crate::payload::QuickStatus| q.until_ms.is_none_or(|until| until > now_ms);
    if let Some(quick) = status.quick_status.as_ref().filter(current) {
        out.push(Insight::Busy {
            emoji: quick.emoji.clone(),
            label: quick.label.clone(),
            until_ms: quick.until_ms,
            automatic: false,
        });
    } else if let Some(auto) = status.auto_status.as_ref().filter(current) {
        out.push(Insight::Busy {
            emoji: auto.emoji.clone(),
            label: auto.label.clone(),
            until_ms: auto.until_ms,
            automatic: true,
        });
    }

    let woke_recently = status
        .woke_at_ms
        .filter(|at| now_ms - at < WOKE_RECENT_MS && *at <= now_ms);
    if let Some(at_ms) = woke_recently {
        out.push(Insight::WokeUp { at_ms });
    }

    if let Some(battery) = status.battery_percent {
        let charging = status.charging.unwrap_or(false);
        let quiet_since = presence
            .last_seen_ms
            .unwrap_or(0)
            .max(status.captured_at_ms);
        if !presence.online
            && !charging
            && battery <= CRITICAL_BATTERY
            && now_ms - quiet_since >= DIED_AFTER_MS
        {
            out.push(Insight::PhoneMayHaveDied {
                battery_percent: battery,
                since_ms: status.captured_at_ms,
            });
        } else if battery <= LOW_BATTERY {
            out.push(Insight::BatteryLow {
                battery_percent: battery,
                charging,
            });
        }
    }

    if let Some(offset) = status.utc_offset_minutes {
        let (hour, minute) = local_time(now_ms, offset);
        if !presence.online
            && woke_recently.is_none()
            && !(NIGHT_ENDS..NIGHT_STARTS).contains(&hour)
        {
            out.push(Insight::ProbablyAsleep {
                local_hour: hour,
                local_minute: minute,
            });
        }
    }

    let dnd = status.do_not_disturb.unwrap_or(false);
    let quiet_ringer = status
        .ringer
        .filter(|r| matches!(r, RingerMode::Silent | RingerMode::Vibrate));
    if dnd || quiet_ringer.is_some() {
        out.push(Insight::Silenced {
            do_not_disturb: dnd,
            ringer: quiet_ringer,
        });
    }

    match (status.network, status.signal_level) {
        (Some(NetworkKind::Offline), _) => out.push(Insight::Offline),
        (Some(network @ (NetworkKind::Wifi | NetworkKind::Cellular)), Some(level))
            if level <= 1 =>
        {
            out.push(Insight::WeakSignal { network, level })
        }
        _ => {}
    }

    push_last_seen(&mut out, &presence);
    out
}

fn local_time(now_ms: i64, utc_offset_minutes: i32) -> (i32, i32) {
    let minutes = (now_ms.div_euclid(60_000) + i64::from(utc_offset_minutes)).rem_euclid(24 * 60);
    ((minutes / 60) as i32, (minutes % 60) as i32)
}

fn push_last_seen(out: &mut Vec<Insight>, presence: &Presence) {
    if let (false, Some(at_ms)) = (presence.online, presence.last_seen_ms) {
        out.push(Insight::LastSeen { at_ms });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::payload::QuickStatus;

    const MIN: i64 = 60_000;
    // 2026-01-01 12:00 UTC
    const NOON_UTC: i64 = 1_767_268_800_000;

    fn offline_since(ms: i64) -> Presence {
        Presence {
            online: false,
            last_seen_ms: Some(ms),
        }
    }

    #[test]
    fn paused_sharing_is_said_honestly_and_hides_guesses() {
        let status = DeviceStatus {
            captured_at_ms: NOON_UTC - 40 * MIN,
            battery_percent: Some(3),
            paused_until_ms: Some(NOON_UTC + 60 * MIN),
            ..Default::default()
        };
        let insights = partner_insights(
            Some(status.clone()),
            offline_since(NOON_UTC - 45 * MIN),
            NOON_UTC,
        );
        assert_eq!(
            insights,
            vec![
                Insight::SharingPaused {
                    until_ms: Some(NOON_UTC + 60 * MIN)
                },
                Insight::LastSeen {
                    at_ms: NOON_UTC - 45 * MIN
                }
            ]
        );
        // Once the pause is over, the usual hints come back.
        let later = partner_insights(
            Some(status),
            offline_since(NOON_UTC - 45 * MIN),
            NOON_UTC + 61 * MIN,
        );
        assert!(matches!(later[0], Insight::PhoneMayHaveDied { .. }));
    }

    #[test]
    fn automatic_statuses_and_waking_up() {
        let driving = crate::payload::QuickStatus {
            emoji: "🚗".into(),
            label: "Driving".into(),
            until_ms: None,
        };
        // 06:30 in Kathmandu is still night, but they woke up at 06:20.
        let morning = NOON_UTC - 11 * 60 * MIN; // 01:00 UTC = 06:45 NPT
        let status = DeviceStatus {
            captured_at_ms: morning,
            utc_offset_minutes: Some(345),
            auto_status: Some(driving.clone()),
            woke_at_ms: Some(morning - 25 * MIN),
            ..Default::default()
        };
        let insights =
            partner_insights(Some(status.clone()), offline_since(morning - MIN), morning);
        assert_eq!(
            insights[0],
            Insight::Busy {
                emoji: "🚗".into(),
                label: "Driving".into(),
                until_ms: None,
                automatic: true
            }
        );
        assert_eq!(
            insights[1],
            Insight::WokeUp {
                at_ms: morning - 25 * MIN
            }
        );
        assert!(
            !insights
                .iter()
                .any(|i| matches!(i, Insight::ProbablyAsleep { .. }))
        );

        // A status they set themselves wins over the automatic one.
        let manual = DeviceStatus {
            quick_status: Some(crate::payload::QuickStatus {
                emoji: "💼".into(),
                label: "At work".into(),
                until_ms: None,
            }),
            ..status
        };
        let insights = partner_insights(Some(manual), offline_since(morning - MIN), morning);
        assert!(
            matches!(&insights[0], Insight::Busy { automatic: false, label, .. } if label == "At work")
        );
    }

    #[test]
    fn dying_battery_then_silence_means_phone_probably_died() {
        let status = DeviceStatus {
            captured_at_ms: NOON_UTC - 40 * MIN,
            battery_percent: Some(4),
            charging: Some(false),
            ..Default::default()
        };
        let insights = partner_insights(
            Some(status.clone()),
            offline_since(NOON_UTC - 45 * MIN),
            NOON_UTC,
        );
        assert_eq!(
            insights[0],
            Insight::PhoneMayHaveDied {
                battery_percent: 4,
                since_ms: NOON_UTC - 40 * MIN
            }
        );

        // Charging, or heard from a minute ago: just low battery.
        let charging = DeviceStatus {
            charging: Some(true),
            ..status.clone()
        };
        assert_eq!(
            partner_insights(Some(charging), offline_since(NOON_UTC), NOON_UTC)[0],
            Insight::BatteryLow {
                battery_percent: 4,
                charging: true
            }
        );
        let recent = DeviceStatus {
            captured_at_ms: NOON_UTC - MIN,
            ..status
        };
        assert_eq!(
            partner_insights(Some(recent), offline_since(NOON_UTC - MIN), NOON_UTC)[0],
            Insight::BatteryLow {
                battery_percent: 4,
                charging: false
            }
        );
    }

    #[test]
    fn night_time_where_they_are() {
        // 12:00 UTC is 23:30 in UTC+11:30.
        let status = DeviceStatus {
            captured_at_ms: NOON_UTC,
            utc_offset_minutes: Some(690),
            ..Default::default()
        };
        let insights = partner_insights(
            Some(status.clone()),
            offline_since(NOON_UTC - 5 * MIN),
            NOON_UTC,
        );
        assert_eq!(
            insights[0],
            Insight::ProbablyAsleep {
                local_hour: 23,
                local_minute: 30
            }
        );
        assert_eq!(
            insights[1],
            Insight::LastSeen {
                at_ms: NOON_UTC - 5 * MIN
            }
        );

        // Online at night: not asleep.
        let online = Presence {
            online: true,
            last_seen_ms: None,
        };
        assert_eq!(
            partner_insights(Some(status), online, NOON_UTC),
            vec![Insight::OnlineNow]
        );
    }

    #[test]
    fn busy_silenced_and_weak_signal() {
        let status = DeviceStatus {
            captured_at_ms: NOON_UTC,
            ringer: Some(RingerMode::Vibrate),
            network: Some(NetworkKind::Cellular),
            signal_level: Some(1),
            quick_status: Some(QuickStatus {
                emoji: "🚗".into(),
                label: "Driving".into(),
                until_ms: Some(NOON_UTC + MIN),
            }),
            ..Default::default()
        };
        let insights = partner_insights(
            Some(status.clone()),
            Presence {
                online: true,
                last_seen_ms: None,
            },
            NOON_UTC,
        );
        assert_eq!(
            insights,
            vec![
                Insight::OnlineNow,
                Insight::Busy {
                    emoji: "🚗".into(),
                    label: "Driving".into(),
                    until_ms: Some(NOON_UTC + MIN),
                    automatic: false
                },
                Insight::Silenced {
                    do_not_disturb: false,
                    ringer: Some(RingerMode::Vibrate)
                },
                Insight::WeakSignal {
                    network: NetworkKind::Cellular,
                    level: 1
                },
            ]
        );
        // An expired status is not shown.
        let later = partner_insights(
            Some(status),
            Presence {
                online: true,
                last_seen_ms: None,
            },
            NOON_UTC + 2 * MIN,
        );
        assert!(!later.iter().any(|i| matches!(i, Insight::Busy { .. })));
    }

    #[test]
    fn no_status_yet() {
        assert_eq!(
            partner_insights(None, offline_since(5), NOON_UTC),
            vec![Insight::LastSeen { at_ms: 5 }]
        );
        assert!(
            partner_insights(
                None,
                Presence {
                    online: false,
                    last_seen_ms: None
                },
                NOON_UTC
            )
            .is_empty()
        );
    }

    #[test]
    fn local_time_handles_negative_offsets() {
        assert_eq!(local_time(NOON_UTC, -300), (7, 0));
        assert_eq!(local_time(NOON_UTC, 345), (17, 45));
        assert_eq!(local_time(NOON_UTC, 0), (12, 0));
    }
}
