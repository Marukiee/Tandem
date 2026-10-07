//! This PC as the main computer of a shared pointer (see docs/INPUT_SHARING.md in the repository): the mouse and keyboard
//! of this PC go on to another computer when the pointer runs into the edge of the screen where that computer sits.
//!
//! The hooks of Windows see everything the hands do (`tandem_winsys::Capture`). While the pointer is over there, this PC
//! holds its own pointer still in the middle of the screen, swallows the events and sends them as input to the other
//! computer. When the other computer says the pointer came back, or the keys Control, Alt and Shift with Escape are pressed,
//! the pointer returns.

use std::collections::HashMap;
use std::sync::mpsc::{self, Sender};
use std::sync::{Mutex, OnceLock};

use tandem_core::ffi::{TandemInput, TandemPointerShare};
use tandem_core::pointer_share::{self, Edge, Screen};
use tandem_winsys::{Capture, Seen};
use tauri::{AppHandle, Manager};

use crate::{input, settings, state::AppState};

enum Out {
    Input(String, TandemInput),
    Share(String, TandemPointerShare),
}

struct Inner {
    /// The computer next to this one and the side it sits on, from the settings.
    next_to: Option<(String, Edge)>,
    /// The computer that has the pointer now.
    remote: Option<(String, Edge)>,
    saved: (i32, i32),
    mods: u8,
}

static INNER: Mutex<Inner> = Mutex::new(Inner { next_to: None, remote: None, saved: (0, 0), mods: 0 });
static OUT: OnceLock<Sender<Out>> = OnceLock::new();
static HOOKS: OnceLock<Option<Capture>> = OnceLock::new();

fn edge_of(text: &str) -> Option<Edge> {
    match text {
        "left" => Some(Edge::Left),
        "right" => Some(Edge::Right),
        "top" => Some(Edge::Top),
        "bottom" => Some(Edge::Bottom),
        _ => None,
    }
}

/// Whether the pointer of this computer is on that device now.
pub fn is_remote(device: &str) -> bool {
    INNER.lock().unwrap().remote.as_ref().is_some_and(|(d, _)| d == device)
}

/// Reads the settings: which computer sits where. The hooks are put on the first time there is one, and then stay.
pub fn configure(app: &AppHandle) {
    let current = settings::get(app);
    let next_to = edge_of(&current.share_edge).filter(|_| !current.share_device.is_empty()).map(|e| (current.share_device.clone(), e));
    let wanted = next_to.is_some();
    {
        let mut inner = INNER.lock().unwrap();
        if next_to.is_none() && inner.remote.is_some() {
            drop(inner);
            come_back(None);
        } else {
            inner.next_to = next_to;
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
                while let Ok(out) = rx.recv() {
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
    }
}

fn send(out: Out) {
    if let Some(tx) = OUT.get() {
        let _ = tx.send(out);
    }
}

/// What the hands did. True when it is for the other computer and has to go no further on this one.
fn see(seen: Seen) -> bool {
    let mut inner = INNER.lock().unwrap();
    let Some((device, edge)) = inner.remote.clone() else {
        // The pointer is here: does it run into the edge where a computer sits?
        let (Seen::Move { dx, dy, x, y }, Some((device, edge))) = (seen, inner.next_to.clone()) else { return false };
        // The edge is that of all the screens together, so a second screen on the far side is no reason to leave.
        let (left, top, w, h) = tandem_winsys::desktop();
        let (w, h) = (w.max(1), h.max(1));
        let (px, py) = (x - left, y - top);
        let crossing = match edge {
            Edge::Right => px >= w - 1 && dx > 0,
            Edge::Left => px <= 0 && dx < 0,
            Edge::Top => py <= 0 && dy < 0,
            Edge::Bottom => py >= h - 1 && dy > 0,
        };
        if !crossing {
            return false;
        }
        let along = match edge {
            Edge::Left | Edge::Right => py as f32 / (h - 1).max(1) as f32,
            Edge::Top | Edge::Bottom => px as f32 / (w - 1).max(1) as f32,
        };
        inner.saved = (x, y);
        inner.remote = Some((device.clone(), edge));
        inner.mods = 0;
        drop(inner);
        if let Some(Some(hooks)) = HOOKS.get() {
            hooks.hold(true);
        }
        send(Out::Share(device, TandemPointerShare::Enter { edge: edge.into(), along: along.clamp(0.0, 1.0) }));
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
                send(Out::Share(device, TandemPointerShare::Release));
                restore(None, edge);
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

/// The pointer is back on this PC: where it left, or where the other computer says it came from.
fn restore(along: Option<f32>, edge: Edge) {
    let (left, top, w, h) = tandem_winsys::desktop();
    let target = match along {
        Some(along) => {
            let (x, y) = pointer_share::back_at(Screen { width: w as f32, height: h as f32 }, edge, along);
            (left + x as i32, top + y as i32)
        }
        None => INNER.lock().unwrap().saved,
    };
    if let Some(Some(hooks)) = HOOKS.get() {
        hooks.hold(false);
    }
    tandem_winsys::warp(target.0, target.1);
}

fn come_back(along: Option<f32>) {
    let remote = INNER.lock().unwrap().remote.take();
    if let Some((_, edge)) = remote {
        restore(along, edge);
    }
}

/// The other computer says the pointer came back, or takes it back.
pub fn returned(device: &str, along: Option<f32>) {
    let mine = INNER.lock().unwrap().remote.as_ref().is_some_and(|(d, _)| d == device);
    if mine {
        come_back(along);
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
        assert_eq!(edge_of("left"), Some(Edge::Left));
        assert_eq!(edge_of(""), None);
    }
}
