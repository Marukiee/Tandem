//! The screen or the camera of a phone, in a window of its own. The core delivers the pictures as H.264 access units in
//! Annex B; they go on, untouched, to the window, where the browser engine of the window decodes them (WebCodecs) and draws
//! them. This file keeps the sessions, the window that belongs to each, and the way the frames get there.

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

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
    ended: Option<String>,
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
        self.tell(session, "live-update", json!({ "width": update.width, "height": update.height, "rotation": update.rotation }));
    }

    fn on_frame(&self, session: u64, pts_us: u64, keyframe: bool, discontinuity: bool, data: Vec<u8>) {
        let packet = pack(pts_us, keyframe, discontinuity, data);
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

    fn on_ended(&self, session: u64, reason: TandemMediaEnd) {
        let why = format!("{reason:?}");
        if let Some(s) = SESSIONS.lock().unwrap().get_mut(&session) {
            s.ended = Some(why.clone());
        }
        self.tell(session, "live-ended", json!({ "reason": why }));
    }
}

/// Asks a phone for its screen or its camera and opens the window that shows it.
#[tauri::command]
pub async fn live_start(app: AppHandle, id: String, kind: String, name: String) -> Reply<u64> {
    start(&app, id, kind == "camera", TandemMediaFacing::Any, name)
}

/// The request and the window. Also what a phone that starts the sharing itself ends up in.
pub fn start(app: &AppHandle, id: String, camera: bool, facing: TandemMediaFacing, name: String) -> Reply<u64> {
    let engine = app.state::<AppState>().engine()?;
    let want = TandemMediaWant {
        kind: if camera { TandemMediaKind::Camera } else { TandemMediaKind::Screen },
        codecs: vec![TandemMediaCodec::H264],
        max_width: 1920,
        max_height: 1080,
        max_fps: if camera { 30 } else { 60 },
        max_bitrate: 0,
        control: false,
        facing: if camera { facing } else { TandemMediaFacing::Any },
    };
    let session = engine.media_request(id, want).map_err(|e| e.to_string())?;
    let label = format!("live-{session}");
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
            ended: None,
        },
    );
    let (width, height) = if camera { (720.0, 560.0) } else { (440.0, 820.0) };
    let built = WebviewWindowBuilder::new(app, &label, WebviewUrl::App(format!("live.html?session={session}").into()))
        .title(name)
        .inner_size(width, height)
        .min_inner_size(240.0, 240.0)
        .build();
    if let Err(e) = built {
        SESSIONS.lock().unwrap().remove(&session);
        let _ = engine.media_stop(session);
        return Err(e.to_string());
    }
    Ok(session)
}

/// The window is ready for frames. What it missed while it loaded is sent first, from the newest keyframe on.
#[tauri::command]
pub fn live_attach(session: u64, on_frame: Channel<InvokeResponseBody>) -> Reply<Value> {
    let mut sessions = SESSIONS.lock().unwrap();
    let s = sessions.get_mut(&session).ok_or("this session is over")?;
    for packet in std::mem::take(&mut s.backlog) {
        let _ = on_frame.send(InvokeResponseBody::Raw(packet));
    }
    s.channel = Some(on_frame);
    Ok(json!({ "name": s.name, "kind": s.kind, "accepted": s.accepted, "rotation": s.rotation, "ended": s.ended }))
}

/// The decoder lost the thread of the picture.
#[tauri::command]
pub fn live_keyframe(state: State<'_, AppState>, session: u64) {
    if let Ok(engine) = state.engine() {
        let _ = engine.media_request_keyframe(session);
    }
}

/// The window is closing or the person pressed stop.
#[tauri::command]
pub fn live_stop(state: State<'_, AppState>, session: u64) {
    SESSIONS.lock().unwrap().remove(&session);
    if let Ok(engine) = state.engine() {
        let _ = engine.media_stop(session);
    }
}

/// Keeps the window above the others, or lets it go.
#[tauri::command]
pub fn live_pin(app: AppHandle, session: u64, on: bool) {
    if let Some(window) = app.get_webview_window(&format!("live-{session}")) {
        let _ = window.set_always_on_top(on);
    }
}
