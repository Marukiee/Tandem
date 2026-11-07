//! The core's types as the JSON the interface reads. Keys are camelCase; numbers that can outgrow what a script
//! can count exactly (the key of a cover) travel as text.

use serde_json::{Value, json};
use tandem_core::ffi::{
    TandemSoundMode,
    TandemDevice, TandemMediaPlayer, TandemNetKind, TandemNotification, TandemPlatform, TandemRoute, TandemShareItem,
    TandemShareOrigin, TandemStatus,
};

pub fn platform(p: TandemPlatform) -> &'static str {
    match p {
        TandemPlatform::Android => "android",
        TandemPlatform::MacOs => "macos",
        TandemPlatform::Linux => "linux",
        TandemPlatform::Windows => "windows",
        TandemPlatform::Ios => "ios",
        TandemPlatform::Other => "other",
    }
}

fn route(r: TandemRoute) -> &'static str {
    match r {
        TandemRoute::Lan => "lan",
        TandemRoute::Tailnet => "tailnet",
        TandemRoute::Other => "other",
    }
}

fn net_kind(k: TandemNetKind) -> &'static str {
    match k {
        TandemNetKind::None => "none",
        TandemNetKind::Wifi => "wifi",
        TandemNetKind::Cellular => "cellular",
        TandemNetKind::Ethernet => "ethernet",
        TandemNetKind::Other => "other",
    }
}

fn status(s: &TandemStatus) -> Value {
    json!({
        "battery": s.battery.map(|b| json!({ "level": b.level, "charging": b.charging, "powerSave": b.power_save })),
        "network": s.network.as_ref().map(|n| json!({
            "kind": net_kind(n.kind),
            "ssid": n.ssid,
            "metered": n.metered,
            "roaming": n.roaming,
            "signal": n.signal,
        })),
        "hotspot": s.hotspot,
        "dnd": s.dnd,
        "locked": s.locked,
        "freeStorage": s.free_storage,
        "asleep": s.asleep,
        "awake": s.awake,
        "sound": s.sound.map(|mode| match mode {
            TandemSoundMode::Sound => "sound",
            TandemSoundMode::Vibrate => "vibrate",
            TandemSoundMode::Silent => "silent",
            TandemSoundMode::DoNotDisturb => "dnd",
        }),
        "soundDnd": s.sound_dnd,
        "lock": s.lock,
        "watch": s.watch.as_ref().map(|w| json!({
            "paired": w.paired, "online": w.online, "level": w.level, "charging": w.charging, "ringing": w.ringing, "name": w.name,
        })),
    })
}

pub fn health_record(r: &tandem_core::ffi::health::TandemHealthRecord) -> Value {
    use tandem_core::ffi::health::{TandemHealthKind as K, TandemSleepStage as S};
    let stage = |stage: S| match stage {
        S::Wake => "wake",
        S::Light => "light",
        S::Deep => "deep",
        S::Rem => "rem",
        S::Unknown => "unknown",
    };
    json!({
        "id": r.id,
        "kind": match r.kind { K::Sleep => "sleep", K::Workout => "workout", K::Day => "day", K::Unknown => "unknown" },
        "startMs": r.start_ms,
        "endMs": r.end_ms,
        "sleepMinutes": r.sleep_minutes,
        "efficiencyPercent": r.efficiency_percent,
        "lightMinutes": r.light_minutes,
        "deepMinutes": r.deep_minutes,
        "remMinutes": r.rem_minutes,
        "stages": r.stages.iter().map(|run| json!({ "stage": stage(run.stage), "seconds": run.seconds })).collect::<Vec<_>>(),
        "heartRateMean": r.heart_rate_mean,
        "heartRateLow": r.heart_rate_low,
        "heartRateHigh": r.heart_rate_high,
        "steps": r.steps,
        "activeMinutes": r.active_minutes,
        "heartCurve": r.heart_curve,
    })
}

pub fn health_insights(i: &tandem_core::ffi::health::TandemHealthInsights) -> Value {
    let week = |w: &tandem_core::ffi::health::TandemWeekSummary| {
        json!({
            "nights": w.nights,
            "meanSleepMinutes": w.mean_sleep_minutes,
            "meanScore": w.mean_score,
            "meanResting": w.mean_resting,
            "workouts": w.workouts,
            "workoutMinutes": w.workout_minutes,
            "workoutSteps": w.workout_steps,
            "naps": w.naps,
            "napMinutes": w.nap_minutes,
            "meanSteps": w.mean_steps,
            "activeMinutes": w.active_minutes,
        })
    };
    json!({
        "latestNight": i.latest_night.as_ref().map(|n| json!({
            "id": n.id,
            "score": n.score,
            "durationScore": n.duration_score,
            "continuityScore": n.continuity_score,
            "stagesScore": n.stages_score,
            "wakeMoments": n.wake_moments.iter().map(|m| json!({ "startMs": m.start_ms, "minutes": m.minutes })).collect::<Vec<_>>(),
        })),
        "resting": i.resting.as_ref().map(|r| json!({ "latest": r.latest, "usual": r.usual, "nightsNeeded": r.nights_needed })),
        "regularity": i.regularity.as_ref().map(|r| json!({
            "bedtimeMinutes": r.bedtime_minutes,
            "bedtimeSpreadMinutes": r.bedtime_spread_minutes,
            "wakeMinutes": r.wake_minutes,
            "wakeSpreadMinutes": r.wake_spread_minutes,
        })),
        "regularityNightsNeeded": i.regularity_nights_needed,
        "alertAboveUsual": i.alert_above_usual,
        "thisWeek": week(&i.this_week),
        "lastWeek": week(&i.last_week),
        "latestDayHeart": i.latest_day_heart.as_ref().map(|d| json!({
            "startMs": d.start_ms, "curve": d.curve, "low": d.low, "mean": d.mean, "high": d.high,
        })),
        "week": i.week.iter().map(|d| json!({
            "startMs": d.start_ms, "sleepMinutes": d.sleep_minutes, "score": d.score, "resting": d.resting,
            "bedMinutes": d.bed_minutes, "wakeMinutes": d.wake_minutes, "steps": d.steps, "activeMinutes": d.active_minutes,
            "napMinutes": d.nap_minutes,
        })).collect::<Vec<_>>(),
        "naps": i.naps.iter().map(|n| json!({
            "startMs": n.start_ms, "endMs": n.end_ms, "sleepMinutes": n.sleep_minutes, "heartRateMean": n.heart_rate_mean,
        })).collect::<Vec<_>>(),
    })
}

pub fn device(d: &TandemDevice) -> Value {
    json!({
        "id": d.id,
        "name": d.name,
        "platform": platform(d.platform),
        "online": d.online,
        "route": d.route.map(route),
        "rttMs": d.rtt_ms,
        "status": status(&d.status),
        "appVersion": d.app_version,
        "caps": d.caps,
        "vouchedByRemoved": d.vouched_by_removed,
        "clipboard": d.clipboard_enabled,
        "autoAccept": d.auto_accept,
        "notifications": d.notifications_enabled,
        "ble": d.ble,
        "features": crate::features::list_json(&d.features),
        "lastSeen": d.last_seen.as_ref().map(|s| json!({ "atMs": s.at_ms, "ssid": s.ssid, "route": s.route.map(route) })),
    })
}

pub fn player(p: &TandemMediaPlayer) -> Value {
    json!({
        "id": p.id,
        "app": p.app,
        "title": p.title,
        "artist": p.artist,
        "album": p.album,
        "playing": p.playing,
        "positionMs": p.position_ms,
        "durationMs": p.duration_ms,
        "canPrev": p.can_prev,
        "canNext": p.can_next,
        "canSeek": p.can_seek,
        "art": p.art.to_string(),
    })
}

pub fn origin(o: TandemShareOrigin) -> &'static str {
    match o {
        TandemShareOrigin::Files => "files",
        TandemShareOrigin::Screenshot => "screenshot",
        TandemShareOrigin::Photo => "photo",
        TandemShareOrigin::Clipboard => "clipboard",
        TandemShareOrigin::Capture { .. } => "capture",
        TandemShareOrigin::Drag => "drag",
        TandemShareOrigin::Other => "other",
    }
}

pub fn items(items: &[TandemShareItem]) -> Value {
    Value::Array(items.iter().map(|i| json!({ "name": i.name, "size": i.size, "mime": i.mime })).collect())
}

pub fn notification(from: &str, device_name: &str, n: &TandemNotification) -> Value {
    json!({
        "device": from,
        "deviceName": device_name,
        "key": n.key,
        "appId": n.app_id,
        "appName": n.app_name,
        "title": n.title,
        "text": n.text,
        "ts": n.ts,
        "ongoing": n.ongoing,
        "otp": n.otp,
        "buttons": n.buttons.iter().map(|b| json!({ "id": b.id, "title": b.title, "isReply": b.is_reply })).collect::<Vec<_>>(),
    })
}

/// A text that is only a web address: the clipboard then travels as a link.
pub fn is_url(text: &str) -> bool {
    let t = text.trim();
    (t.starts_with("http://") || t.starts_with("https://")) && !t.contains(char::is_whitespace)
}
