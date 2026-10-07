//! The core's types as the JSON the interface reads. Keys are camelCase; numbers that can outgrow what a script
//! can count exactly (the key of a cover) travel as text.

use serde_json::{Value, json};
use tandem_core::ffi::{
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
