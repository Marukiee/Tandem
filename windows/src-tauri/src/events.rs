//! What the engine reports, turned into what the person sees: the lists the interface shows, Windows notifications
//! for what is worth one, and the clipboard when text arrives.

use std::time::{Duration, Instant};

use base64::Engine as _;
use serde_json::{Value, json};
use tandem_core::ffi::{TandemCall, TandemCallState, TandemEvent, TandemNotification, TandemShareItem, TandemShareOrigin};
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_notification::NotificationExt;

use crate::{clip, i18n, input, media, model, settings, state::AppState};

/// A notification on the desktop of Windows.
pub fn toast(app: &AppHandle, title: &str, body: &str) {
    let _ = app.notification().builder().title(title).body(body).show();
}

/// A short message in the window itself.
pub fn say(app: &AppHandle, text: &str) {
    let _ = app.emit("say", text);
}

pub fn refresh_devices(app: &AppHandle) {
    let state = app.state::<AppState>();
    let Ok(engine) = state.engine() else { return };
    let list: Vec<Value> = engine.devices().iter().map(model::device).collect();
    state.data.lock().unwrap().devices = list.clone();
    let _ = app.emit("devices", list);
}

fn device(app: &AppHandle, id: &str) -> Option<Value> {
    app.state::<AppState>().data.lock().unwrap().devices.iter().find(|d| d["id"] == id).cloned()
}

pub fn device_name(app: &AppHandle, id: &str) -> String {
    device(app, id).and_then(|d| d["name"].as_str().map(str::to_string)).unwrap_or_else(|| "?".to_string())
}

/// A device setting that is on unless the device says otherwise.
fn allowed(app: &AppHandle, id: &str, key: &str) -> bool {
    device(app, id).map(|d| d[key].as_bool().unwrap_or(true)).unwrap_or(true)
}

pub fn handle(app: &AppHandle, event: TandemEvent) {
    match event {
        TandemEvent::Connected { id } => {
            refresh_devices(app);
            // The other computer is told how big this screen is, so it can draw it and find its way back.
            crate::arrange::announce(app, &id);
        }
        TandemEvent::DevicesChanged | TandemEvent::CircleChanged => refresh_devices(app),
        TandemEvent::TailscaleNeeded { id } => crate::tailscale::needed(app, &id),
        TandemEvent::Disconnected { id } => {
            input::release_all();
            crate::sound::stop(&id);
            let had = app.state::<AppState>().data.lock().unwrap().players.remove(&id).is_some();
            refresh_devices(app);
            if had {
                emit_players(app);
            }
        }
        // A phone that shows its screen or camera by itself ("Show my screen on this PC") asks this PC to look.
        TandemEvent::MediaOffered { from, kind, facing } => {
            let name = device_name(app, &from);
            let camera = kind == tandem_core::ffi::TandemMediaKind::Camera;
            if let Err(reason) = crate::live::start(app, from, camera, facing, name) {
                log::warn!("could not open the window for the offered picture: {reason}");
            }
        }
        TandemEvent::Paired { id } => {
            refresh_devices(app);
            let name = device_name(app, &id);
            let _ = app.emit("paired", json!({ "id": id, "name": name }));
            toast(app, &i18n::t(app, "paired_title"), &i18n::t1(app, "paired_body", &name));
        }
        TandemEvent::RemovedFromCircle => {
            refresh_devices(app);
            say(app, &i18n::t(app, "removed_from_circle"));
        }
        TandemEvent::Clipboard { from, text, .. } => {
            if !allowed(app, &from, "clipboard") {
                return;
            }
            clip::apply(app, &text);
            crate::history::record(app, &text, &device_name(app, &from));
            say(app, &i18n::t1(app, "text_on_clipboard", &device_name(app, &from)));
        }
        TandemEvent::ShareOffered { from, offer, origin, items } => offered(app, &from, offer, origin, &items),
        TandemEvent::ShareText { from, text, is_url, .. } => {
            clip::apply(app, &text);
            let name = device_name(app, &from);
            crate::history::record(app, &text, &name);
            let key = if is_url { "link_from" } else { "text_from" };
            toast(app, &i18n::t1(app, key, &name), &text.chars().take(200).collect::<String>());
            say(app, &i18n::t1(app, "text_on_clipboard", &name));
        }
        TandemEvent::Progress { offer, index, peer, incoming, name, done, total } => {
            progress(app, offer, index, &peer, incoming, &name, done, total)
        }
        TandemEvent::Finished { offer, index, peer, incoming, name, size, location, error } => {
            if !(incoming && (crate::drag::finished(app, &peer, offer, &name, &location, &error) || crate::insert::finished(app, &peer, offer, &location, &error))) {
                finished(app, offer, index, &peer, incoming, &name, size, location, error)
            }
        }
        TandemEvent::CaptureCancelled { from, why, .. } => crate::insert::cancelled(app, &from, why),
        TandemEvent::AudioStart { from, stream, sample_rate, channels } if stream == crate::sound::STREAM => {
            crate::sound::start(app, from, sample_rate, channels)
        }
        TandemEvent::AudioStop { from, stream } if stream == crate::sound::STREAM => crate::sound::stop(&from),
        TandemEvent::Notification { from, notification } => phone_notification(app, &from, &notification),
        TandemEvent::NotificationRemoved { from, key } => {
            app.state::<AppState>()
                .data
                .lock()
                .unwrap()
                .notifications
                .retain(|n| !(n["device"] == from && n["key"] == key));
            let _ = app.emit("notification-removed", json!({ "device": from, "key": key }));
        }
        TandemEvent::MediaPlayers { from, players } => {
            app.state::<AppState>().data.lock().unwrap().players.insert(from, players);
            emit_players(app);
        }
        TandemEvent::MediaArt { key, jpeg, .. } => {
            let uri = format!("data:image/jpeg;base64,{}", base64::engine::general_purpose::STANDARD.encode(jpeg));
            {
                let state = app.state::<AppState>();
                let mut data = state.data.lock().unwrap();
                // The newest few covers are all that is on screen.
                if data.art.len() >= 24 {
                    if let Some(old) = data.art.keys().copied().find(|k| *k != key) {
                        data.art.remove(&old);
                    }
                }
                data.art.insert(key, uri.clone());
            }
            let _ = app.emit("art", json!({ "key": key.to_string(), "uri": uri }));
            media::refresh(app);
        }
        TandemEvent::Call { from, call } => incoming_call(app, &from, &call),
        TandemEvent::Input { from, input } => {
            // From the computer that has the pointer: it was allowed when the pointer came over.
            if input::shared_is(&from) {
                input::send_shared(input);
            } else {
                remote_input(app, &from, input);
            }
        }
        TandemEvent::PointerShare { from, msg } => {
            use tandem_core::ffi::TandemPointerShare as Share;
            match msg {
                Share::Enter { edge, along } => {
                    // Taken only when it can really be played here: a pointer that comes over and does nothing leaves the person
                    // on the other computer stuck, so what cannot be done is handed straight back.
                    if settings::get(app).remote_input
                        && crate::commands::input_blocked().is_none()
                        && !crate::lid::blocked(app)
                        && input::ready()
                    {
                        input::shared_enter(from, edge.into(), along);
                    } else if let Ok(engine) = app.state::<AppState>().engine() {
                        // Not allowed: the pointer goes straight back.
                        tauri::async_runtime::spawn(async move { let _ = engine.send_pointer_share(from, Share::Leave { along }).await; });
                    }
                }
                Share::Leave { along } => {
                    input::shared_end(&from);
                    crate::capture::returned(&from, Some(along));
                }
                Share::Release => {
                    input::shared_end(&from);
                    crate::capture::returned(&from, None);
                }
                // The computer that has the pointer asks whether this one is still there. Answered at once, which is also what keeps
                // the watchdog on this side quiet.
                Share::Ping => {
                    input::pinged(&from);
                    crate::capture::heard(&from, false);
                    if let Ok(engine) = app.state::<AppState>().engine() {
                        tauri::async_runtime::spawn(async move { let _ = engine.send_pointer_share(from, Share::Pong).await; });
                    }
                }
                Share::Pong => crate::capture::heard(&from, true),
                // Only the computers that take a pointer in say how big their screen is, and this one is not the main computer of those.
                Share::Size { width, height } => {
                    crate::arrange::learned(&from, width as i32, height as i32);
                    crate::capture::heard(&from, false);
                }
                Share::Carry { text } => {
                    if input::shared_is(&from) {
                        input::shared_carry(text);
                    }
                }
            }
        }
        _ => {}
    }
}

/// The trackpad and keyboard of a phone. Only when the person allowed it; otherwise they are told once in a while
/// why nothing happens, since nothing on the phone says so.
fn remote_input(app: &AppHandle, from: &str, event: tandem_core::ffi::TandemInput) {
    let blocked = crate::commands::input_blocked();
    if crate::lid::blocked(app) {
        return;
    }
    if settings::get(app).remote_input && blocked.is_none() {
        input::send(event);
        return;
    }
    static ASKED: std::sync::Mutex<Option<Instant>> = std::sync::Mutex::new(None);
    let mut asked = ASKED.lock().unwrap();
    if asked.map(|t| t.elapsed() > Duration::from_secs(120)).unwrap_or(true) {
        *asked = Some(Instant::now());
        // The system not allowing it is another thing than the person not having turned it on, and needs other words.
        let body = if blocked.is_some() { i18n::t(app, "remote_blocked_body") } else { i18n::t(app, "remote_off_body") };
        toast(app, &i18n::t1(app, "remote_off_title", &device_name(app, from)), &body);
    }
}

fn emit_players(app: &AppHandle) {
    let _ = app.emit("players", players_json(app));
    media::refresh(app);
}

pub fn players_json(app: &AppHandle) -> Value {
    let state = app.state::<AppState>();
    let data = state.data.lock().unwrap();
    let mut out = serde_json::Map::new();
    for (id, list) in &data.players {
        out.insert(id.clone(), Value::Array(list.iter().map(model::player).collect()));
    }
    Value::Object(out)
}

pub fn art_json(app: &AppHandle) -> Value {
    let state = app.state::<AppState>();
    let data = state.data.lock().unwrap();
    Value::Object(data.art.iter().map(|(k, v)| (k.to_string(), Value::String(v.clone()))).collect())
}

fn offered(app: &AppHandle, from: &str, offer: u64, origin: TandemShareOrigin, items: &[TandemShareItem]) {
    // What a phone made because this computer asked for it is taken without asking, and goes on the clipboard.
    if crate::insert::offered(app, from, offer, origin) {
        return;
    }
    // Files dropped on the edge by the computer that shares its pointer with this one: taken at once, and put on the desktop.
    if crate::drag::offered(app, from, offer, origin) {
        return;
    }
    // A device that may send without asking has its files taken by the core. Only the others wait for an answer.
    if allowed(app, from, "autoAccept") {
        return;
    }
    let name = device_name(app, from);
    let value = json!({
        "from": from,
        "fromName": name,
        "offer": offer.to_string(),
        "origin": model::origin(origin),
        "items": model::items(items),
    });
    app.state::<AppState>().data.lock().unwrap().offers.push(value.clone());
    let _ = app.emit("offer", value);
    let names = items.iter().map(|i| i.name.as_str()).collect::<Vec<_>>().join(", ");
    toast(app, &i18n::t2(app, "wants_to_send", &name, &items.len().to_string()), &names);
}

#[allow(clippy::too_many_arguments)]
fn progress(app: &AppHandle, offer: u64, index: u32, peer: &str, incoming: bool, name: &str, done: u64, total: u64) {
    let id = format!("{offer}-{index}-{}", if incoming { "in" } else { "out" });
    let state = app.state::<AppState>();
    let value = {
        let mut data = state.data.lock().unwrap();
        // A file that has started is no longer an offer.
        let offer_text = offer.to_string();
        data.offers.retain(|o| !(o["from"] == peer && o["offer"] == offer_text));
        let now = chrono_ms();
        let existing = data.transfers.iter().position(|t| t["id"] == id);
        let mut item = match existing {
            Some(at) => data.transfers.remove(at),
            None => json!({ "id": id, "peer": peer, "name": name, "incoming": incoming, "startedAt": now }),
        };
        let was = item["updatedAt"].as_u64().unwrap_or(0);
        item["done"] = json!(done);
        item["total"] = json!(total);
        item["state"] = json!("active");
        item["updatedAt"] = json!(now);
        item["speed"] = json!(speed(&item, done, was, now));
        data.transfers.insert(0, item.clone());
        data.transfers.truncate(60);
        item
    };
    // A file moves many times a second; the bar needs far fewer pictures than that.
    static LAST: std::sync::Mutex<Option<Instant>> = std::sync::Mutex::new(None);
    let mut last = LAST.lock().unwrap();
    if done >= total || last.map(|t| t.elapsed() > Duration::from_millis(120)).unwrap_or(true) {
        *last = Some(Instant::now());
        let _ = app.emit("transfer", value);
    }
}

/// Bytes per second, smoothed so the figure does not jump.
fn speed(item: &Value, done: u64, was: u64, now: u64) -> f64 {
    let before = item["done"].as_u64().unwrap_or(0);
    let old = item["speed"].as_f64().unwrap_or(0.0);
    if was == 0 || now <= was || done < before {
        return old;
    }
    let instant = (done - before) as f64 * 1000.0 / (now - was) as f64;
    if old == 0.0 { instant } else { old * 0.7 + instant * 0.3 }
}

#[allow(clippy::too_many_arguments)]
fn finished(
    app: &AppHandle,
    offer: u64,
    index: u32,
    peer: &str,
    incoming: bool,
    name: &str,
    size: u64,
    location: Option<String>,
    error: Option<String>,
) {
    let id = format!("{offer}-{index}-{}", if incoming { "in" } else { "out" });
    let state = app.state::<AppState>();
    let value = {
        let mut data = state.data.lock().unwrap();
        let existing = data.transfers.iter().position(|t| t["id"] == id);
        let now = chrono_ms();
        let mut item = match existing {
            Some(at) => data.transfers.remove(at),
            None => json!({ "id": id, "peer": peer, "name": name, "incoming": incoming, "startedAt": now, "total": size }),
        };
        item["state"] = json!(if error.is_none() { "done" } else { "failed" });
        item["error"] = json!(error);
        item["location"] = json!(location);
        item["updatedAt"] = json!(now);
        if error.is_none() {
            item["done"] = json!(size.max(item["total"].as_u64().unwrap_or(0)));
            item["total"] = json!(size.max(item["total"].as_u64().unwrap_or(0)));
        }
        data.transfers.insert(0, item.clone());
        data.transfers.truncate(60);
        item
    };
    let _ = app.emit("transfer", value);
    if incoming && error.is_none() {
        toast(app, &i18n::t1(app, "received", name), &i18n::t1(app, "received_from", &device_name(app, peer)));
    } else if let Some(reason) = error {
        toast(app, &i18n::t1(app, "failed", name), &reason);
    }
}

pub fn now_ms() -> u64 {
    chrono_ms()
}

fn chrono_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn phone_notification(app: &AppHandle, from: &str, n: &TandemNotification) {
    // Ongoing ones (music, navigation, downloads) come and go; they do not belong on a list.
    if n.ongoing || !allowed(app, from, "notifications") {
        return;
    }
    let name = device_name(app, from);
    let settings = settings::get(app);
    if settings.copy_codes {
        if let Some(code) = &n.otp {
            clip::apply(app, code);
            toast(app, &i18n::t1(app, "code_copied", code), &name);
        }
    }
    let value = model::notification(from, &name, n);
    {
        let state = app.state::<AppState>();
        let mut data = state.data.lock().unwrap();
        data.notifications.retain(|old| !(old["device"] == from && old["key"] == n.key));
        data.notifications.insert(0, value.clone());
        data.notifications.truncate(200);
    }
    let _ = app.emit("notification", value);
    if settings.phone_notifications && !n.silent {
        let body = if n.title.is_empty() { n.text.clone() } else { format!("{}\n{}", n.title, n.text) };
        toast(app, &format!("{} · {}", n.app_name, name), &body);
    }
}

fn incoming_call(app: &AppHandle, from: &str, call: &TandemCall) {
    let who = call.name.clone().or_else(|| call.number.clone()).unwrap_or_else(|| i18n::t(app, "unknown_number"));
    match call.state {
        TandemCallState::Ringing if call.incoming => toast(app, &i18n::t1(app, "calling", &device_name(app, from)), &who),
        TandemCallState::Missed => toast(app, &i18n::t(app, "missed_call"), &who),
        _ => {}
    }
}
