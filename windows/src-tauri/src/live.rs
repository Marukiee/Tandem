//! The screen or the camera of a phone, in a window of its own. The core delivers the pictures as H.264 access units in
//! Annex B. They go on, untouched, to the window, where the browser engine of the window decodes them (WebCodecs) and draws
//! them. Where that engine cannot (the web view of Linux), the app decodes them itself (`video.rs`, the feature `native-video`)
//! and the window gets JPEG pictures instead. This file keeps the sessions, the window that belongs to each, and the way the
//! frames get there.

use std::collections::HashMap;
use std::sync::atomic::AtomicBool;
#[cfg(feature = "native-video")]
use std::sync::atomic::Ordering;
use std::sync::{Arc, LazyLock, Mutex};

use serde_json::{json, Value};
use tandem_core::ffi::{
    TandemMediaAccept, TandemMediaCodec, TandemMediaEnd, TandemMediaFacing, TandemMediaKind, TandemMediaUpdate,
    TandemMediaViewer, TandemMediaWant,
};
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};

use crate::state::AppState;

type Reply<T> = Result<T, String>;

/// How many frames wait for a window that is still loading. The newest keyframe and what follows it is all that matters.
const BACKLOG: usize = 90;

struct Session {
    label: String,
    name: String,
    kind: &'static str,
    channel: Option<Channel<InvokeResponseBody>>,
    backlog: Vec<Vec<u8>>,
    accepted: Option<Value>,
    rotation: u16,
    /// What is shown is a computer (a remote desktop), not a phone.
    computer: bool,
    /// That computer is a Mac: Control stands for Command there, which is where the shortcuts of a Mac live.
    mac: bool,
    /// The decoder of this app, when the window cannot decode (the feature `native-video`).
    #[cfg(feature = "native-video")]
    decode: Option<std::sync::mpsc::SyncSender<Frame>>,
    /// Set when a frame was dropped because the decoder was behind: it waits for the next full picture.
    #[cfg_attr(not(feature = "native-video"), allow(dead_code))]
    lost: Arc<AtomicBool>,
}

#[cfg(feature = "native-video")]
struct Frame {
    pts_us: u64,
    keyframe: bool,
    discontinuity: bool,
    data: Vec<u8>,
}

static SESSIONS: LazyLock<Mutex<HashMap<u64, Session>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// One frame as the window gets it: a byte of flags (1 keyframe, 2 decoder to be flushed first), the time in microseconds
/// as eight bytes big endian, and the access unit.
fn pack(pts_us: u64, keyframe: bool, discontinuity: bool, data: Vec<u8>) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + data.len());
    out.push(u8::from(keyframe) | (u8::from(discontinuity) << 1));
    out.extend_from_slice(&pts_us.to_be_bytes());
    out.extend_from_slice(&data);
    out
}

/// A decoded picture as the window gets it: the flag 4, the time, and the JPEG.
#[cfg(feature = "native-video")]
fn pack_jpeg(pts_us: u64, jpeg: Vec<u8>) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + jpeg.len());
    out.push(4);
    out.extend_from_slice(&pts_us.to_be_bytes());
    out.extend_from_slice(&jpeg);
    out
}

/// Gives a packet to the window, or keeps it for the window that is still loading.
fn deliver(session: u64, packet: Vec<u8>, keyframe: bool) {
    let mut sessions = SESSIONS.lock().unwrap();
    let Some(s) = sessions.get_mut(&session) else { return };
    if let Some(channel) = &s.channel {
        if channel.send(InvokeResponseBody::Raw(packet)).is_err() {
            // The window is gone: nothing more to send it.
            s.channel = None;
        }
        return;
    }
    if keyframe {
        s.backlog.clear();
    }
    if s.backlog.len() < BACKLOG {
        s.backlog.push(packet);
    }
}

/// The thread that decodes the pictures of one session. A frame that cannot be decoded in time is dropped and the next
/// full picture is waited for, so the window never falls behind what the phone is showing.
#[cfg(feature = "native-video")]
fn spawn_decoder(app: &AppHandle, session: u64, lost: Arc<AtomicBool>) -> Option<std::sync::mpsc::SyncSender<Frame>> {
    use std::time::{Duration, Instant};
    let (tx, rx) = std::sync::mpsc::sync_channel::<Frame>(8);
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-video".into())
        .spawn(move || {
            let Some(mut decoder) = crate::video::Decoder::new() else { return };
            let mut waiting = true;
            let mut asked = Instant::now() - Duration::from_secs(10);
            while let Ok(frame) = rx.recv() {
                if frame.discontinuity {
                    if let Some(fresh) = crate::video::Decoder::new() {
                        decoder = fresh;
                    }
                    waiting = true;
                }
                if lost.swap(false, Ordering::Relaxed) {
                    waiting = true;
                }
                if frame.keyframe {
                    waiting = false;
                }
                if waiting {
                    // Nothing to build on: ask for a full picture, not more than once in a while.
                    if asked.elapsed() > Duration::from_millis(700) {
                        asked = Instant::now();
                        if let Ok(engine) = app.state::<AppState>().engine() {
                            let _ = engine.media_request_keyframe(session);
                        }
                    }
                    continue;
                }
                if let Some(jpeg) = decoder.picture(&frame.data) {
                    // Only the newest picture is worth keeping for a window that is not there yet.
                    deliver(session, pack_jpeg(frame.pts_us, jpeg), true);
                }
            }
        })
        .ok()?;
    Some(tx)
}

pub struct Viewer {
    pub app: AppHandle,
}

impl Viewer {
    fn tell(&self, session: u64, event: &str, payload: Value) {
        let label = SESSIONS.lock().unwrap().get(&session).map(|s| s.label.clone());
        if let Some(label) = label {
            let _ = self.app.emit_to(label, event, payload);
        }
    }
}

impl TandemMediaViewer for Viewer {
    fn on_accepted(&self, session: u64, accept: TandemMediaAccept) {
        let state = json!({ "width": accept.width, "height": accept.height, "fps": accept.fps, "control": accept.control });
        if let Some(s) = SESSIONS.lock().unwrap().get_mut(&session) {
            s.accepted = Some(state.clone());
        }
        self.tell(session, "live-accepted", state);
    }

    fn on_update(&self, session: u64, update: TandemMediaUpdate) {
        if let Some(rotation) = update.rotation {
            if let Some(s) = SESSIONS.lock().unwrap().get_mut(&session) {
                s.rotation = rotation;
            }
        }
        self.tell(session, "live-update", json!({ "width": update.width, "height": update.height, "rotation": update.rotation, "control": update.control }));
    }

    fn on_frame(&self, session: u64, pts_us: u64, keyframe: bool, discontinuity: bool, data: Vec<u8>) {
        #[cfg(feature = "native-video")]
        {
            let sessions = SESSIONS.lock().unwrap();
            if let Some(Some(decode)) = sessions.get(&session).map(|s| s.decode.as_ref()) {
                if decode.try_send(Frame { pts_us, keyframe, discontinuity, data }).is_err() {
                    // The decoder is behind: this picture is lost, and what follows it builds on it.
                    if let Some(s) = sessions.get(&session) {
                        s.lost.store(true, Ordering::Relaxed);
                    }
                }
                return;
            }
        }
        deliver(session, pack(pts_us, keyframe, discontinuity, data), keyframe);
    }

    fn on_ended(&self, session: u64, reason: TandemMediaEnd) {
        // The window goes with the session, whatever the reason: a grey window that says nothing useful is in the way. What went wrong,
        // when something did, is said in the main window and in a notification, with what there is to do about it.
        let Some(ended) = SESSIONS.lock().unwrap().remove(&session) else { return };
        let key = match reason {
            TandemMediaEnd::Declined => Some("live_end_declined"),
            TandemMediaEnd::Policy => Some("live_end_policy"),
            TandemMediaEnd::Unavailable => Some("live_end_unavailable"),
            TandemMediaEnd::Unsupported => Some("live_end_unsupported"),
            TandemMediaEnd::Timeout => Some("live_end_timeout"),
            TandemMediaEnd::Busy => Some("live_end_busy"),
            TandemMediaEnd::PeerGone => Some("live_end_lost"),
            _ => None,
        };
        if let Some(key) = key {
            let text = crate::i18n::t1(&self.app, key, &ended.name);
            crate::events::say(&self.app, &text);
            crate::events::toast(&self.app, &ended.name, &text);
        }
        if let Some(window) = self.app.get_webview_window(&ended.label) {
            let _ = window.destroy();
        }
    }
}

/// Asks a phone for its screen or its camera and opens the window that shows it.
#[tauri::command]
pub async fn live_start(app: AppHandle, id: String, kind: String, name: String, computer: Option<bool>, mac: Option<bool>) -> Reply<String> {
    let session = start(&app, id, kind == "camera", TandemMediaFacing::Any, name, computer.unwrap_or(false))?;
    if computer.unwrap_or(false) {
        if let Some(s) = SESSIONS.lock().unwrap().get_mut(&session) {
            s.computer = true;
            s.mac = mac.unwrap_or(false);
        }
    }
    Ok(session.to_string())
}

/// The request and the window. Also what a phone that starts the sharing itself ends up in.
pub fn start(app: &AppHandle, id: String, camera: bool, facing: TandemMediaFacing, name: String, computer: bool) -> Reply<u64> {
    let engine = app.state::<AppState>().engine()?;
    let want = TandemMediaWant {
        kind: if camera { TandemMediaKind::Camera } else { TandemMediaKind::Screen },
        codecs: vec![TandemMediaCodec::H264],
        // The whole screen as it is, up to what a big monitor has: scaled down the text of a screen turns soft.
        max_width: if camera { 1920 } else { 3840 },
        max_height: if camera { 1080 } else { 2160 },
        max_fps: if camera { 30 } else { 60 },
        max_bitrate: 0,
        // The picture of a phone can be clicked on from here, when the phone allows it (its accessibility service, see live.js).
        control: !camera,
        facing: if camera { facing } else { TandemMediaFacing::Any },
    };
    let session = engine.media_request(id, want).map_err(|e| e.to_string())?;
    let label = format!("live-{session}");
    let lost = Arc::new(AtomicBool::new(false));
    SESSIONS.lock().unwrap().insert(
        session,
        Session {
            label: label.clone(),
            name: name.clone(),
            kind: if camera { "camera" } else { "screen" },
            channel: None,
            backlog: Vec::new(),
            accepted: None,
            rotation: 0,
            computer: false,
            mac: false,
            #[cfg(feature = "native-video")]
            decode: spawn_decoder(app, session, lost.clone()),
            lost,
        },
    );
    // The shape before the first picture says it (see `live_fit`): wide for a camera or the screen of a computer, tall for a phone.
    let (width, height) = if camera || computer { (860.0, 560.0) } else { (440.0, 820.0) };
    let built = WebviewWindowBuilder::new(app, &label, WebviewUrl::App(format!("live.html?session={session}").into()))
        .title(name)
        .inner_size(width, height)
        .min_inner_size(240.0, 240.0)
        // Linux draws no frame of its own: the page does, over the picture (see ui/js/chrome.js).
        .decorations(cfg!(windows))
        .build();
    if let Err(e) = built {
        SESSIONS.lock().unwrap().remove(&session);
        let _ = engine.media_stop(session);
        return Err(e.to_string());
    }
    Ok(session)
}

/// The number of a session as the windows say it. It is a random 64 bit number, and a number in a web page keeps 53 bits of it, so
/// the windows hold it as text: a session whose number was rounded on the way is a session that "is over".
fn session_of(text: &str) -> Reply<u64> {
    text.trim().parse::<u64>().map_err(|_| "that is not a session".to_string())
}

/// The first picture is there: the window takes the shape of what it shows (a computer is wide, a phone is tall), as big as is
/// comfortable on this screen, so the picture does not sit in the middle of a black window.
#[tauri::command]
pub fn live_fit(app: AppHandle, session: String, width: f64, height: f64) {
    let Ok(session) = session_of(&session) else { return };
    if width < 2.0 || height < 2.0 {
        return;
    }
    let Some(window) = app.get_webview_window(&format!("live-{session}")) else { return };
    let (max_w, max_h) = window
        .current_monitor()
        .ok()
        .flatten()
        .map(|m| (m.size().width as f64 / m.scale_factor() * 0.85, m.size().height as f64 / m.scale_factor() * 0.85))
        .unwrap_or((1200.0, 800.0));
    let aspect = width / height;
    let (mut w, mut h) = if aspect >= 1.0 { (1000.0, 1000.0 / aspect) } else { (440.0, 440.0 / aspect) };
    if h > max_h {
        h = max_h;
        w = h * aspect;
    }
    if w > max_w {
        w = max_w;
        h = w / aspect;
    }
    let _ = window.set_size(tauri::LogicalSize::new(w.round().max(240.0), h.round().max(240.0)));
}

/// The window is ready for frames. What it missed while it loaded is sent first, from the newest keyframe on.
#[tauri::command]
pub fn live_attach(session: String, on_frame: Channel<InvokeResponseBody>) -> Reply<Value> {
    let session = session_of(&session)?;
    let mut sessions = SESSIONS.lock().unwrap();
    let s = sessions.get_mut(&session).ok_or("this session is over")?;
    for packet in std::mem::take(&mut s.backlog) {
        let _ = on_frame.send(InvokeResponseBody::Raw(packet));
    }
    s.channel = Some(on_frame);
    Ok(json!({
        "name": s.name, "kind": s.kind, "accepted": s.accepted, "rotation": s.rotation,
        "native": cfg!(feature = "native-video"), "platform": if cfg!(windows) { "windows" } else { "linux" }, "computer": s.computer, "mac": s.mac,
    }))
}

/// A click, a key or a scroll on the picture, for the phone. The window sends them as small objects: `pointer` (x and y as
/// fractions of the picture), `button`, `scroll`, `key` (a USB HID usage, with the modifiers held: shift 1, control 2, alt 4, meta 8) and `text`.
#[tauri::command]
pub fn live_input(state: State<'_, AppState>, session: String, input: Value) -> Reply<()> {
    use tandem_core::ffi::TandemMediaInput as Input;
    let session = session_of(&session)?;
    let number = |name: &str| input[name].as_f64().unwrap_or(0.0);
    let event = match input["t"].as_str().unwrap_or_default() {
        "pointer" => Input::PointerAbs { x: number("x").clamp(0.0, 1.0) as f32, y: number("y").clamp(0.0, 1.0) as f32 },
        "button" => Input::Button {
            button: number("button").clamp(0.0, 4.0) as u8,
            down: input["down"].as_bool().unwrap_or(false),
            clicks: number("clicks").clamp(1.0, 3.0) as u8,
        },
        "scroll" => Input::Scroll { dx: number("dx").clamp(-2000.0, 2000.0) as i16, dy: number("dy").clamp(-2000.0, 2000.0) as i16 },
        "key" => Input::Key {
            code: number("code") as u32,
            down: input["down"].as_bool().unwrap_or(true),
            mods: number("mods").clamp(0.0, 15.0) as u16,
            text: String::new(),
        },
        "text" => Input::Text { text: input["text"].as_str().unwrap_or_default().chars().take(200).collect() },
        _ => return Err("not an input".into()),
    };
    state.engine()?.media_send_input(session, event).map_err(|e| e.to_string())
}

/// The decoder lost the thread of the picture.
#[tauri::command]
pub fn live_keyframe(state: State<'_, AppState>, session: String) {
    let Ok(session) = session_of(&session) else { return };
    if let Ok(engine) = state.engine() {
        let _ = engine.media_request_keyframe(session);
    }
}

/// The window is closing or the person pressed stop.
#[tauri::command]
pub fn live_stop(app: AppHandle, state: State<'_, AppState>, session: String) {
    let Ok(session) = session_of(&session) else { return };
    SESSIONS.lock().unwrap().remove(&session);
    // The window goes with the session, wherever the stop came from, also when the session was gone already.
    if let Some(window) = app.get_webview_window(&format!("live-{session}")) {
        let _ = window.destroy();
    }
    if let Ok(engine) = state.engine() {
        let _ = engine.media_stop(session);
    }
}

/// Keeps the window above the others, or lets it go.
#[tauri::command]
pub fn live_pin(app: AppHandle, session: String, on: bool) {
    let Ok(session) = session_of(&session) else { return };
    if let Some(window) = app.get_webview_window(&format!("live-{session}")) {
        let _ = window.set_always_on_top(on);
    }
}

/// What this computer lets another device do: show the screen and use the mouse and keyboard, each as `ask`, `always` or `never`.
#[tauri::command]
pub fn media_policy(state: State<'_, AppState>, id: String) -> Reply<Value> {
    let policy = state.engine()?.media_policy(id).map_err(|e| e.to_string())?;
    Ok(json!({ "screen": permission_text(policy.screen), "control": permission_text(policy.control) }))
}

#[tauri::command]
pub fn media_policy_set(state: State<'_, AppState>, id: String, screen: Option<String>, control: Option<String>) -> Reply<Value> {
    let engine = state.engine()?;
    let mut policy = engine.media_policy(id.clone()).map_err(|e| e.to_string())?;
    if let Some(text) = screen {
        policy.screen = permission_of(&text);
    }
    if let Some(text) = control {
        policy.control = permission_of(&text);
    }
    engine.set_media_policy(id, policy.clone()).map_err(|e| e.to_string())?;
    Ok(json!({ "screen": permission_text(policy.screen), "control": permission_text(policy.control) }))
}

fn permission_text(p: tandem_core::ffi::TandemMediaPermission) -> &'static str {
    use tandem_core::ffi::TandemMediaPermission as P;
    match p {
        P::Ask => "ask",
        P::Always => "always",
        P::Never => "never",
    }
}

fn permission_of(text: &str) -> tandem_core::ffi::TandemMediaPermission {
    use tandem_core::ffi::TandemMediaPermission as P;
    match text {
        "always" => P::Always,
        "never" => P::Never,
        _ => P::Ask,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_session_number_survives_the_trip_as_text() {
        // A number this big loses its last digits in a web page, which is how a session came to be "over" the moment it started.
        let big = u64::MAX - 12;
        assert_eq!(session_of(&big.to_string()), Ok(big));
        assert_eq!(session_of(" 42 "), Ok(42));
        assert!(session_of("not a number").is_err());
        assert!(session_of("").is_err());
    }
}
