//! This PC as the main computer of a shared pointer (see docs/INPUT_SHARING.md in the repository): the mouse and keyboard
//! of this PC go on to another computer when the pointer runs into the edge of the screen where that computer sits.
//!
//! The hooks of Windows see everything the hands do (`tandem_winsys::Capture`). While the pointer is over there, this PC
//! holds its own pointer still in the middle of the screen, swallows the events and sends them as input to the other
//! computer. When the other computer says the pointer came back, or the keys Control, Alt and Shift with Escape are pressed,
//! the pointer returns.

use std::collections::{BTreeSet, HashMap};
use std::sync::mpsc::{self, Sender};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use tandem_core::ffi::{TandemInput, TandemPointerShare};
use tandem_core::layout;
use tandem_core::pointer_share::{self, Edge};
use tandem_winsys::{Capture, Seen};
use tauri::{AppHandle, Manager};

use crate::{input, settings, state::AppState};

enum Out {
    Input(String, TandemInput),
    Share(String, TandemPointerShare),
}

struct Inner {
    /// The computer that has the pointer now, and the side of this screen it went out by.
    remote: Option<(String, Edge)>,
    saved: (i32, i32),
    mods: u8,
    /// When the pointer went over, so a pointer that comes straight back can be told from one that was used.
    entered: Option<Instant>,
}

static INNER: Mutex<Inner> = Mutex::new(Inner { remote: None, saved: (0, 0), mods: 0, entered: None });
/// Computers that say how they are (the capability `pointer.ready`) and say they cannot take the pointer now. The edge towards one is a
/// wall until it says it can, so the pointer does not go over and come straight back again and again.
static NOT_READY: Mutex<BTreeSet<String>> = Mutex::new(BTreeSet::new());

fn unready(device: &str) -> bool {
    NOT_READY.lock().unwrap().contains(device)
}

/// That computer says whether it can take the pointer now. Only believed from one that says so by itself, which also says when it can again.
pub fn readiness(device: &str, ready: bool) {
    let Some(app) = APP.get() else { return };
    let says = app
        .state::<AppState>()
        .engine()
        .ok()
        .is_some_and(|engine| engine.devices().iter().any(|d| d.id == device && d.caps.iter().any(|c| c == "pointer.ready")));
    if !says {
        return;
    }
    let mut set = NOT_READY.lock().unwrap();
    if ready {
        set.remove(device);
    } else {
        set.insert(device.to_string());
    }
}
/// For the settings and the sizes of the other screens, which the hooks cannot get at otherwise.
static APP: OnceLock<AppHandle> = OnceLock::new();
static OUT: OnceLock<Sender<Out>> = OnceLock::new();
/// When the computer that has the pointer last answered. A link that goes quiet gives the pointer back (see `watch`).
static HEARD: Mutex<Option<Instant>> = Mutex::new(None);
/// Whether the computer that has the pointer has answered a ping since it got it. One that never does is an older Tandem, which says nothing
/// while the pointer is held still, so its silence means nothing.
static PONGED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
static HOOKS: OnceLock<Option<Capture>> = OnceLock::new();

/// Whether the pointer of this computer is on that device now.
pub fn is_remote(device: &str) -> bool {
    INNER.lock().unwrap().remote.as_ref().is_some_and(|(d, _)| d == device)
}

/// Reads the settings: which screens sit where. The hooks are put on the first time there is one, and then stay.
pub fn configure(app: &AppHandle) {
    let _ = APP.set(app.clone());
    let current = settings::get(app);
    let wanted = !current.layout.is_empty();
    {
        // With no screen next to this one left, a pointer that is over there comes home.
        let away = INNER.lock().unwrap().remote.is_some();
        if !wanted && away {
            come_back(None);
        }
    }
    if wanted && HOOKS.get().is_none() {
        let handle = app.clone();
        let (tx, rx) = mpsc::channel::<Out>();
        let _ = OUT.set(tx);
        std::thread::Builder::new()
            .name("tandem-share".into())
            .spawn(move || {
                // One thread, in order: a key that goes down must not be overtaken by the one that lets go.
                let mut held_back: Option<Out> = None;
                loop {
                    let out = match held_back.take() {
                        Some(out) => out,
                        None => match rx.recv() {
                            Ok(out) => out,
                            Err(_) => break,
                        },
                    };
                    // A mouse says where it went a few hundred times a second, and each of those is a message that has to be sent before the
                    // next one. When they pile up (the link has a hiccup) they are added together into one move instead of arriving late
                    // one by one, which is what makes the pointer on the other computer stutter. With nothing waiting, each goes at once.
                    let out = match out {
                        Out::Input(device, TandemInput::Pointer { dx, dy }) => {
                            let (mut sum_x, mut sum_y) = (i32::from(dx), i32::from(dy));
                            while let Ok(next) = rx.try_recv() {
                                match next {
                                    Out::Input(other, TandemInput::Pointer { dx, dy }) if other == device => {
                                        sum_x += i32::from(dx);
                                        sum_y += i32::from(dy);
                                    }
                                    other => {
                                        held_back = Some(other);
                                        break;
                                    }
                                }
                            }
                            let clamp = |v: i32| v.clamp(i16::MIN as i32, i16::MAX as i32) as i16;
                            Out::Input(device, TandemInput::Pointer { dx: clamp(sum_x), dy: clamp(sum_y) })
                        }
                        other => other,
                    };
                    let Ok(engine) = handle.state::<AppState>().engine() else { continue };
                    tauri::async_runtime::block_on(async {
                        match out {
                            Out::Input(device, input) => {
                                let _ = engine.send_input(device, input).await;
                            }
                            Out::Share(device, msg) => {
                                let _ = engine.send_pointer_share(device, msg).await;
                            }
                        }
                    });
                }
            })
            .ok();
        let _ = HOOKS.set(Capture::start(see));
        watch();
    }
    #[cfg(target_os = "linux")]
    linux::configure(app);
}

/// Whether the mouse of this computer can be used on the others from here: Windows always can, Linux when the desktop offers to hand it
/// over (Wayland).
pub fn can_share() -> bool {
    #[cfg(target_os = "linux")]
    {
        linux::supported()
    }
    #[cfg(not(target_os = "linux"))]
    {
        cfg!(windows)
    }
}

/// How the capture of the mouse is doing, for the settings: `ready`, `starting` (the desktop is asking the person), or `failed`.
pub fn status() -> serde_json::Value {
    #[cfg(target_os = "linux")]
    {
        linux::status()
    }
    #[cfg(not(target_os = "linux"))]
    {
        serde_json::json!({ "state": "ready", "reason": "" })
    }
}

/// Asks the computer that has the pointer, twice a second, whether it is still there. When it does not answer for two seconds (its
/// lid closed, the network went) the pointer comes back, so this PC can be used again without waiting for a connection to time out.
fn watch() {
    std::thread::Builder::new()
        .name("tandem-share-ping".into())
        .spawn(|| loop {
            std::thread::sleep(Duration::from_millis(pointer_share::PING_EVERY_MS));
            let Some((device, _)) = INNER.lock().unwrap().remote.clone() else { continue };
            let quiet = PONGED.load(std::sync::atomic::Ordering::Relaxed)
                && HEARD.lock().unwrap().map_or(true, |t| t.elapsed() > Duration::from_millis(pointer_share::PING_PATIENCE_MS));
            if quiet {
                come_back(None);
                send(Out::Share(device, TandemPointerShare::Release));
            } else {
                send(Out::Share(device, TandemPointerShare::Ping));
            }
        })
        .ok();
}

/// Something came in from that computer, so it is still there. `answered` is for a pong, which is what proves it keeps up with pings.
pub fn heard(device: &str, answered: bool) {
    if is_remote(device) {
        *HEARD.lock().unwrap() = Some(Instant::now());
        if answered {
            PONGED.store(true, std::sync::atomic::Ordering::Relaxed);
        }
    }
}

fn send(out: Out) {
    if let Some(tx) = OUT.get() {
        let _ = tx.send(out);
    }
}

/// The pointer went over to `device`, through `edge` of this screen, `along` of the way down it: it is held still here and the other
/// computer is told to take it in.
fn started(device: String, edge: Edge, along: f32) {
    if let Some(Some(hooks)) = HOOKS.get() {
        hooks.hold(true);
    }
    *HEARD.lock().unwrap() = Some(Instant::now());
    PONGED.store(false, std::sync::atomic::Ordering::Relaxed);
    send(Out::Share(device, TandemPointerShare::Enter { edge: edge.into(), along: along.clamp(0.0, 1.0) }));
}

/// What the hands did. True when it is for the other computer and has to go no further on this one.
fn see(seen: Seen) -> bool {
    let mut inner = INNER.lock().unwrap();
    let Some((device, edge)) = inner.remote.clone() else {
        // The pointer is here: does it run into an edge where a screen sits?
        let (Seen::Move { dx, dy, x, y }, Some(app)) = (seen, APP.get()) else { return false };
        // The edge is that of all the screens together, so a second screen on the far side is no reason to leave.
        let (left, top, w, h) = tandem_winsys::desktop();
        let (w, h) = (w.max(1), h.max(1));
        let (px, py) = (x - left, y - top);
        let mut pushes: Vec<(Edge, i32)> = Vec::new();
        if px >= w - 1 && dx > 0 { pushes.push((Edge::Right, py)); }
        if px <= 0 && dx < 0 { pushes.push((Edge::Left, py)); }
        if py <= 0 && dy < 0 { pushes.push((Edge::Top, px)); }
        if py >= h - 1 && dy > 0 { pushes.push((Edge::Bottom, px)); }
        if pushes.is_empty() {
            return false;
        }
        // Only where a screen really is next to this one: along the rest of the edge there is a wall.
        let list = crate::arrange::neighbours(app, true);
        let Some((edge, found)) = pushes.into_iter().find_map(|(edge, position)| layout::cross(edge, position, &list).map(|(index, along)| (edge, (list[index].id.clone(), along)))) else {
            return false;
        };
        let (device, along) = found;
        if unready(&device) {
            return false;
        }
        inner.saved = (x, y);
        inner.remote = Some((device.clone(), edge));
        inner.mods = 0;
        inner.entered = Some(Instant::now());
        drop(inner);
        started(device, edge, along);
        return true;
    };
    let _ = edge;
    match seen {
        Seen::Move { dx, dy, .. } => {
            let clamp = |v: i32| v.clamp(i16::MIN as i32, i16::MAX as i32) as i16;
            send(Out::Input(device, TandemInput::Pointer { dx: clamp(dx), dy: clamp(dy) }));
        }
        Seen::Button { button, down } => send(Out::Input(device, TandemInput::Button { button, down })),
        Seen::Scroll { dx, dy } => {
            let clamp = |v: i32| v.clamp(i16::MIN as i32, i16::MAX as i32) as i16;
            send(Out::Input(device, TandemInput::Scroll { dx: clamp(dx), dy: clamp(dy) }));
        }
        Seen::Key { vk, down } => {
            // Control, Alt and Shift with Escape: the way back.
            if down && vk == 0x1B && inner.mods & 0b111 == 0b111 {
                inner.remote = None;
                drop(inner);
                send(Out::Share(device.clone(), TandemPointerShare::Release));
                restore(None, &device, edge);
                return true;
            }
            let bit = match vk {
                0x10 | 0xA0 | 0xA1 => 1u8, // Shift
                0x11 | 0xA2 | 0xA3 => 2u8, // Control, which is Command on a Mac
                0x12 | 0xA4 | 0xA5 => 4u8, // Alt, which is Option
                0x5B | 0x5C => 8u8,        // the Windows key, which is Control
                _ => 0,
            };
            if bit != 0 {
                if down { inner.mods |= bit } else { inner.mods &= !bit }
            }
            let mac_mods = mac_mods(inner.mods);
            drop(inner);
            if let Some(code) = mac_code(vk) {
                send(Out::Input(device, TandemInput::Key { code, down, mods: mac_mods }));
            }
        }
    }
    true
}

/// The modifiers in the order of a Mac keyboard (shift 1, control 2, option 4, command 8) for the ones held here (shift 1,
/// control 2, alt 4, windows key 8). The keys stand where they do on a Mac, so Control becomes Command and Alt Option, which
/// is where the shortcuts live: Ctrl+C here copies there.
fn mac_mods(held: u8) -> u8 {
    (held & 1) | if held & 2 != 0 { 8 } else { 0 } | (held & 4) | if held & 8 != 0 { 2 } else { 0 }
}

/// The code of a Mac virtual key for a Windows one: the table of the other direction, turned round.
fn mac_code(vk: u16) -> Option<u16> {
    static TABLE: OnceLock<HashMap<u16, u16>> = OnceLock::new();
    TABLE.get_or_init(|| (0u16..128).filter_map(|code| input::mac_to_vk(code).map(|vk| (vk, code))).collect()).get(&vk).copied()
}

/// The pointer is back on this PC: where it left, or where the other computer says it came from (`along` its own edge, which is
/// worked out to the place on this screen where the two touch).
fn restore(along: Option<f32>, device: &str, edge: Edge) {
    let (left, top, w, h) = desktop_box();
    let target = match along {
        Some(along) => {
            let size = crate::arrange::size_of(device).unwrap_or((w, h));
            let placement = APP.get().and_then(|app| crate::arrange::neighbours(app, false).into_iter().find(|n| n.id == device).map(|n| n.placement));
            let main_len = if matches!(edge, Edge::Left | Edge::Right) { h } else { w };
            let position = match placement {
                Some(p) => layout::back(main_len, p, size, along),
                None => (along.clamp(0.0, 1.0) * (main_len - 1).max(0) as f32) as i32,
            };
            // Where the desktop watches the edge for the pointer (Wayland), a pointer put on the edge would be taken over again by the
            // first touch of the mouse, so it is put a little way in.
            let inset = if cfg!(target_os = "linux") { 4 } else { 0 };
            match edge {
                Edge::Left => (left + inset, top + position),
                Edge::Right => (left + w - 1 - inset, top + position),
                Edge::Top => (left + position, top + inset),
                Edge::Bottom => (left + position, top + h - 1 - inset),
            }
        }
        None => INNER.lock().unwrap().saved,
    };
    if let Some(Some(hooks)) = HOOKS.get() {
        hooks.hold(false);
    }
    #[cfg(target_os = "linux")]
    linux::release(target);
    #[cfg(not(target_os = "linux"))]
    tandem_winsys::warp(target.0, target.1);
}

/// All the screens of this computer together: where they start, and how big they are.
fn desktop_box() -> (i32, i32, i32, i32) {
    if cfg!(target_os = "linux") {
        let (w, h) = crate::arrange::own_size();
        (0, 0, w, h)
    } else {
        tandem_winsys::desktop()
    }
}

fn come_back(along: Option<f32>) {
    let remote = INNER.lock().unwrap().remote.take();
    if let Some((device, edge)) = remote {
        restore(along, &device, edge);
    }
}

/// The other computer says the pointer came back, or takes it back.
pub fn returned(device: &str, along: Option<f32>) {
    let (mine, straight_back) = {
        let inner = INNER.lock().unwrap();
        (
            inner.remote.as_ref().is_some_and(|(d, _)| d == device),
            inner.entered.is_some_and(|t| t.elapsed() < Duration::from_millis(1500)),
        )
    };
    if mine {
        // Handed back at once: it could not take it. One that says how it is will say when it can.
        if straight_back && along.is_some() {
            readiness(device, false);
        }
        come_back(along);
    }
}

/// Linux (Wayland): the desktop watches the edges where another computer sits and hands over the mouse and keyboard when the pointer runs
/// into one (see `tandem_winsys::inputcapture`). What comes then goes the same way as what the hooks of Windows see.
#[cfg(target_os = "linux")]
mod linux {
    use super::*;
    use tandem_winsys::inputcapture::{self, Captured, Side, State, Stretch, WaylandCapture};

    static CAPTURE: OnceLock<WaylandCapture> = OnceLock::new();
    /// Which stretch of an edge is which computer: (stretch, computer, edge).
    static STRETCHES: Mutex<Vec<(u32, String, Edge)>> = Mutex::new(Vec::new());

    pub fn supported() -> bool {
        static ANSWER: OnceLock<bool> = OnceLock::new();
        *ANSWER.get_or_init(|| tandem_winsys::portal::is_wayland() && inputcapture::available())
    }

    pub fn status() -> serde_json::Value {
        // A locked screen is the commonest reason that the desktop will not hand anything over, and the one to say first.
        if tandem_winsys::screen_locked() == Some(true) {
            return serde_json::json!({ "state": "locked", "reason": "" });
        }
        match CAPTURE.get() {
            Some(capture) => {
                let state = match capture.state() {
                    State::Ready => "ready",
                    State::Starting => "starting",
                    State::Failed => "failed",
                };
                serde_json::json!({ "state": state, "reason": capture.reason() })
            }
            None => serde_json::json!({ "state": "idle", "reason": "" }),
        }
    }

    /// Starts the capture when there is a screen next to this one, and keeps the barriers where those screens are.
    pub fn configure(app: &AppHandle) {
        if !supported() || settings::get(app).layout.is_empty() {
            if let Some(capture) = CAPTURE.get() {
                capture.set_stretches(Vec::new());
            }
            return;
        }
        if CAPTURE.get().is_none() {
            let _ = CAPTURE.set(WaylandCapture::start(handle));
            // The computers come and go and their screens are measured when they say hello, so the barriers follow.
            let watcher = app.clone();
            std::thread::Builder::new()
                .name("tandem-barriers".into())
                .spawn(move || loop {
                    std::thread::sleep(Duration::from_secs(2));
                    sync(&watcher);
                })
                .ok();
        }
        sync(app);
    }

    fn sync(app: &AppHandle) {
        let Some(capture) = CAPTURE.get() else { return };
        let (w, h) = crate::arrange::own_size();
        let mut known = Vec::new();
        let mut stretches = Vec::new();
        for (index, neighbour) in crate::arrange::neighbours(app, true).into_iter().enumerate() {
            let id = index as u32 + 1;
            let edge = neighbour.placement.edge;
            let (main_len, len) = match edge {
                Edge::Left | Edge::Right => (h, neighbour.height),
                Edge::Top | Edge::Bottom => (w, neighbour.width),
            };
            let (from, to) = (neighbour.placement.offset.max(0), (neighbour.placement.offset + len).min(main_len));
            if to <= from {
                continue;
            }
            let side = match edge {
                Edge::Left => Side::Left,
                Edge::Right => Side::Right,
                Edge::Top => Side::Top,
                Edge::Bottom => Side::Bottom,
            };
            stretches.push(Stretch { id, side, from, to });
            known.push((id, neighbour.id, edge));
        }
        *STRETCHES.lock().unwrap() = known;
        capture.set_stretches(stretches);
    }

    /// The edge of the screens a place is on, for when the desktop does not say which barrier it was.
    fn edge_at(x: i32, y: i32) -> Edge {
        let (w, h) = crate::arrange::own_size();
        let gaps = [(Edge::Left, x), (Edge::Right, w - 1 - x), (Edge::Top, y), (Edge::Bottom, h - 1 - y)];
        gaps.into_iter().min_by_key(|(_, gap)| gap.abs()).map(|(edge, _)| edge).unwrap_or(Edge::Right)
    }

    fn handle(captured: Captured) {
        match captured {
            Captured::Activated { id, x, y } => activated(id, x, y),
            Captured::Deactivated => {
                // The desktop took the mouse back itself (the screen was locked, say): the other computer is told it has no pointer any more.
                let remote = INNER.lock().unwrap().remote.take();
                if let Some((device, _)) = remote {
                    send(Out::Share(device, TandemPointerShare::Release));
                }
            }
            Captured::Seen(seen) => {
                let away = INNER.lock().unwrap().remote.is_some();
                if away {
                    see(seen);
                }
            }
        }
    }

    fn activated(id: u32, x: i32, y: i32) {
        let give_back = || release((x, y));
        // Another computer has the pointer of this one now, and it was put at an edge of this screen: it is that pointer that ran into the
        // barrier, not the person here. Taking it would send the pointer back and forth between the two computers.
        if crate::input::shared_any() {
            log::info!("pointer: the barrier was run into by the pointer of another computer, not taken");
            return give_back();
        }
        let Some(app) = APP.get() else { return give_back() };
        let edge = STRETCHES.lock().unwrap().iter().find(|(s, _, _)| *s == id).map(|(_, _, edge)| *edge).unwrap_or_else(|| edge_at(x, y));
        let position = if matches!(edge, Edge::Left | Edge::Right) { y } else { x };
        let list = crate::arrange::neighbours(app, true);
        // The place on the edge says which computer it is, as for the hooks of Windows.
        let Some((index, along)) = layout::cross(edge, position, &list) else { return give_back() };
        let device = list[index].id.clone();
        if unready(&device) {
            return give_back();
        }
        {
            let mut inner = INNER.lock().unwrap();
            inner.saved = (x, y);
            inner.remote = Some((device.clone(), edge));
            inner.mods = 0;
            inner.entered = Some(Instant::now());
        }
        started(device, edge, along);
    }

    /// Gives the mouse and keyboard back to this computer, with the pointer at this place.
    pub fn release(at: (i32, i32)) {
        if let Some(capture) = CAPTURE.get() {
            capture.release(at.0, at.1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_go_back_to_the_codes_of_a_mac() {
        assert_eq!(mac_code(0x41), Some(0)); // a
        assert_eq!(mac_code(0x1B), Some(53)); // escape
        assert_eq!(mac_code(0x25), Some(123)); // left arrow
        assert_eq!(mac_code(0xFF), None);
    }

    #[test]
    fn control_becomes_command_and_the_windows_key_control() {
        assert_eq!(mac_mods(2), 8);
        assert_eq!(mac_mods(4), 4);
        assert_eq!(mac_mods(1 | 2), 1 | 8);
        assert_eq!(mac_mods(8), 2);
        assert_eq!(mac_mods(0), 0);
    }

    #[test]
    fn the_sides_are_read_from_the_settings() {
        assert_eq!(crate::arrange::edge_of("left"), Some(Edge::Left));
        assert_eq!(crate::arrange::edge_of(""), None);
    }
}
