//! The trackpad and keyboard of a phone, as real input on this PC.
//!
//! The phone sends macOS virtual key codes (what the Mac expects), so a code is translated to the Windows
//! virtual-key code of the same key. Everything is done on one thread of its own, in the order it arrived, so a
//! key that goes down is let go after it and a burst of pointer movements never holds up the engine.

use std::collections::HashSet;
use std::sync::{Mutex, OnceLock, mpsc};

use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};
use tandem_core::ffi::{TandemInput, TandemMediaKey};
use tandem_core::pointer_share::{self, Edge, Screen};

enum Msg {
    Input(TandemInput),
    /// Input from a computer that has the pointer (see `pointer_share`): the movements come already accelerated.
    Shared(TandemInput),
    /// The pointer of such a computer comes in over `edge` of its screen, `along` of the way down.
    Enter { edge: Edge, along: f32 },
    /// The phone went away in the middle of something: let go of whatever it was holding.
    ReleaseAll,
}

static WORKER: OnceLock<Mutex<mpsc::Sender<Msg>>> = OnceLock::new();

fn worker() -> &'static Mutex<mpsc::Sender<Msg>> {
    WORKER.get_or_init(|| {
        let (tx, rx) = mpsc::channel();
        std::thread::Builder::new().name("tandem-input".into()).spawn(move || run(rx)).ok();
        Mutex::new(tx)
    })
}

/// The computer that has the pointer now, and the side of this screen it came in by.
static SHARED: Mutex<Option<(String, Edge)>> = Mutex::new(None);
static LEAVE: OnceLock<Box<dyn Fn(String, f32) + Send + Sync>> = OnceLock::new();

/// What to do when the pointer runs into the edge it came in by: say so to the computer that has it.
pub fn on_leave(tell: impl Fn(String, f32) + Send + Sync + 'static) {
    let _ = LEAVE.set(Box::new(tell));
}

/// A computer sends its pointer over: it comes in at the opposite side of this screen.
pub fn shared_enter(device: String, edge: Edge, along: f32) {
    *SHARED.lock().unwrap() = Some((device, edge.opposite()));
    let _ = worker().lock().unwrap().send(Msg::Enter { edge, along });
}

pub fn shared_end(device: &str) {
    let mut guard = SHARED.lock().unwrap();
    if guard.as_ref().is_some_and(|(d, _)| d == device) {
        *guard = None;
    }
}

pub fn shared_is(device: &str) -> bool {
    SHARED.lock().unwrap().as_ref().is_some_and(|(d, _)| d == device)
}

pub fn send_shared(input: TandemInput) {
    let _ = worker().lock().unwrap().send(Msg::Shared(input));
}

pub fn send(input: TandemInput) {
    let _ = worker().lock().unwrap().send(Msg::Input(input));
}

pub fn release_all() {
    // Nothing to let go of if nothing was ever sent.
    if let Some(tx) = WORKER.get() {
        let _ = tx.lock().unwrap().send(Msg::ReleaseAll);
    }
}

/// macOS virtual key codes to Windows virtual-key codes, for the keys a phone can send.
pub fn mac_to_vk(code: u16) -> Option<u16> {
    Some(match code {
        // Letters: the code of the letter on the keyboard is the letter itself ("A" is 0x41).
        0 => 0x41, 1 => 0x53, 2 => 0x44, 3 => 0x46, 4 => 0x48, 5 => 0x47, 6 => 0x5A, 7 => 0x58, 8 => 0x43, 9 => 0x56,
        11 => 0x42, 12 => 0x51, 13 => 0x57, 14 => 0x45, 15 => 0x52, 16 => 0x59, 17 => 0x54,
        31 => 0x4F, 32 => 0x55, 34 => 0x49, 35 => 0x50, 37 => 0x4C, 38 => 0x4A, 40 => 0x4B, 45 => 0x4E, 46 => 0x4D,
        // Digits.
        18 => 0x31, 19 => 0x32, 20 => 0x33, 21 => 0x34, 22 => 0x36, 23 => 0x35, 25 => 0x39, 26 => 0x37, 28 => 0x38, 29 => 0x30,
        // Punctuation (the OEM keys of an ANSI layout).
        24 => 0xBB, 27 => 0xBD, 30 => 0xDD, 33 => 0xDB, 39 => 0xDE, 41 => 0xBA, 42 => 0xDC, 43 => 0xBC, 44 => 0xBF,
        47 => 0xBE, 50 => 0xC0,
        // Editing and navigation.
        36 => 0x0D, 48 => 0x09, 49 => 0x20, 51 => 0x08, 53 => 0x1B, 117 => 0x2E,
        115 => 0x24, 119 => 0x23, 116 => 0x21, 121 => 0x22,
        123 => 0x25, 124 => 0x27, 125 => 0x28, 126 => 0x26,
        // Function keys.
        122 => 0x70, 120 => 0x71, 99 => 0x72, 118 => 0x73, 96 => 0x74, 97 => 0x75, 98 => 0x76, 100 => 0x77,
        101 => 0x78, 109 => 0x79, 103 => 0x7A, 111 => 0x7B,
        _ => return None,
    })
}

/// The key of the library for a Windows virtual-key code. On Windows that is the code itself; elsewhere the library wants a
/// character or a named key, because there the numbers mean something else.
#[cfg(windows)]
fn key_of(vk: u16) -> Key {
    Key::Other(u32::from(vk))
}

#[cfg(not(windows))]
fn key_of(vk: u16) -> Key {
    portable_key(vk).unwrap_or(Key::Other(u32::from(vk)))
}

/// The same keys as `mac_to_vk` gives, as the library names them on every system.
#[cfg_attr(windows, allow(dead_code))]
fn portable_key(vk: u16) -> Option<Key> {
    Some(match vk {
        0x41..=0x5A => Key::Unicode((b'a' + (vk - 0x41) as u8) as char),
        0x30..=0x39 => Key::Unicode((b'0' + (vk - 0x30) as u8) as char),
        0xBB => Key::Unicode('='),
        0xBD => Key::Unicode('-'),
        0xDD => Key::Unicode(']'),
        0xDB => Key::Unicode('['),
        0xDE => Key::Unicode('\''),
        0xBA => Key::Unicode(';'),
        0xDC => Key::Unicode('\\'),
        0xBC => Key::Unicode(','),
        0xBF => Key::Unicode('/'),
        0xBE => Key::Unicode('.'),
        0xC0 => Key::Unicode('`'),
        0x0D => Key::Return,
        0x09 => Key::Tab,
        0x20 => Key::Space,
        0x08 => Key::Backspace,
        0x1B => Key::Escape,
        0x2E => Key::Delete,
        0x24 => Key::Home,
        0x23 => Key::End,
        0x21 => Key::PageUp,
        0x22 => Key::PageDown,
        0x25 => Key::LeftArrow,
        0x27 => Key::RightArrow,
        0x28 => Key::DownArrow,
        0x26 => Key::UpArrow,
        0x70 => Key::F1,
        0x71 => Key::F2,
        0x72 => Key::F3,
        0x73 => Key::F4,
        0x74 => Key::F5,
        0x75 => Key::F6,
        0x76 => Key::F7,
        0x77 => Key::F8,
        0x78 => Key::F9,
        0x79 => Key::F10,
        0x7A => Key::F11,
        0x7B => Key::F12,
        _ => return None,
    })
}

/// The modifier keys a Key message asks for: shift 1, control 2, option 4, command 8. Command and control both
/// become Ctrl, since that is where the shortcuts of a Mac live on a PC (Cmd+C is Ctrl+C), and option is Alt.
fn modifiers(mods: u8) -> Vec<Key> {
    let mut keys = Vec::new();
    if mods & 1 != 0 {
        keys.push(Key::Shift);
    }
    if mods & (2 | 8) != 0 {
        keys.push(Key::Control);
    }
    if mods & 4 != 0 {
        keys.push(Key::Alt);
    }
    keys
}

fn media_key(key: TandemMediaKey) -> Key {
    match key {
        TandemMediaKey::PlayPause => Key::MediaPlayPause,
        TandemMediaKey::Next => Key::MediaNextTrack,
        TandemMediaKey::Previous => Key::MediaPrevTrack,
        TandemMediaKey::VolumeUp => Key::VolumeUp,
        TandemMediaKey::VolumeDown => Key::VolumeDown,
        TandemMediaKey::Mute => Key::VolumeMute,
    }
}

fn button(number: u8) -> Button {
    match number {
        1 => Button::Right,
        2 => Button::Middle,
        _ => Button::Left,
    }
}

/// How many pixels of scrolling on the phone make one click of the mouse wheel.
const PIXELS_PER_CLICK: f32 = 40.0;

fn run(rx: mpsc::Receiver<Msg>) {
    let mut enigo = match Enigo::new(&Settings::default()) {
        Ok(enigo) => enigo,
        Err(error) => {
            log::warn!("the pointer and keyboard cannot be driven: {error}");
            return;
        }
    };
    let mut buttons: HashSet<u8> = HashSet::new();
    let mut keys: HashSet<u16> = HashSet::new();
    let mut held_mods: Vec<Key> = Vec::new();
    // What is left of a scroll that did not make a whole click yet.
    let (mut rest_x, mut rest_y) = (0.0f32, 0.0f32);

    while let Ok(msg) = rx.recv() {
        let msg = match msg {
            Msg::Enter { edge, along } => {
                if let Ok((width, height)) = enigo.main_display() {
                    let (x, y) = pointer_share::enter_at(Screen { width: width as f32, height: height as f32 }, edge, along);
                    let _ = enigo.move_mouse(x as i32, y as i32, Coordinate::Abs);
                }
                continue;
            }
            Msg::Shared(TandemInput::Pointer { dx, dy }) => {
                let _ = enigo.move_mouse(i32::from(dx), i32::from(dy), Coordinate::Rel);
                // At the edge it came in by and moving out: it goes back to the computer that has the real mouse.
                let shared = SHARED.lock().unwrap().clone();
                if let (Some((device, entered_by)), Ok((x, y)), Ok((w, h))) = (shared, enigo.location(), enigo.main_display()) {
                    let (x, y, w, h) = (x as f32, y as f32, w as f32, h as f32);
                    let leaving = match entered_by {
                        Edge::Left => x <= 0.0 && dx < 0,
                        Edge::Right => x >= w - 1.0 && dx > 0,
                        Edge::Top => y <= 0.0 && dy < 0,
                        Edge::Bottom => y >= h - 1.0 && dy > 0,
                    };
                    if leaving {
                        let along = match entered_by {
                            Edge::Left | Edge::Right => y / (h - 1.0).max(1.0),
                            Edge::Top | Edge::Bottom => x / (w - 1.0).max(1.0),
                        };
                        *SHARED.lock().unwrap() = None;
                        if let Some(tell) = LEAVE.get() {
                            tell(device, along.clamp(0.0, 1.0));
                        }
                    }
                }
                continue;
            }
            Msg::Shared(other) => Msg::Input(other),
            other => other,
        };
        match msg {
            Msg::ReleaseAll => {
                for b in buttons.drain() {
                    let _ = enigo.button(button(b), Direction::Release);
                }
                for k in keys.drain() {
                    let _ = enigo.key(key_of(k), Direction::Release);
                }
                for m in held_mods.drain(..) {
                    let _ = enigo.key(m, Direction::Release);
                }
            }
            // Dealt with above.
            Msg::Shared(_) | Msg::Enter { .. } => {}
            Msg::Input(input) => match input {
                TandemInput::Pointer { dx, dy } => {
                    // A small boost for quick swipes, so the whole screen is a short stroke away.
                    let gain = |d: i16| {
                        let d = f32::from(d);
                        d * (1.0 + (d.abs() / 40.0).min(1.5))
                    };
                    let _ = enigo.move_mouse(gain(dx).round() as i32, gain(dy).round() as i32, Coordinate::Rel);
                }
                TandemInput::Scroll { dx, dy } => {
                    // The phone counts up as positive; the wheel of a mouse counts down as positive.
                    rest_y -= f32::from(dy) / PIXELS_PER_CLICK;
                    rest_x += f32::from(dx) / PIXELS_PER_CLICK;
                    let (clicks_y, clicks_x) = (rest_y.trunc(), rest_x.trunc());
                    if clicks_y != 0.0 {
                        let _ = enigo.scroll(clicks_y as i32, Axis::Vertical);
                        rest_y -= clicks_y;
                    }
                    if clicks_x != 0.0 {
                        let _ = enigo.scroll(clicks_x as i32, Axis::Horizontal);
                        rest_x -= clicks_x;
                    }
                }
                TandemInput::Button { button: b, down } => {
                    if down {
                        buttons.insert(b);
                        let _ = enigo.button(button(b), Direction::Press);
                    } else {
                        buttons.remove(&b);
                        let _ = enigo.button(button(b), Direction::Release);
                    }
                }
                TandemInput::Click { button: b, count } => {
                    for _ in 0..count.max(1) {
                        let _ = enigo.button(button(b), Direction::Click);
                    }
                }
                TandemInput::Key { code, down, mods } => {
                    let Some(vk) = mac_to_vk(code) else { continue };
                    if down {
                        for m in modifiers(mods) {
                            if !held_mods.contains(&m) {
                                let _ = enigo.key(m, Direction::Press);
                                held_mods.push(m);
                            }
                        }
                        keys.insert(vk);
                        let _ = enigo.key(key_of(vk), Direction::Press);
                    } else {
                        keys.remove(&vk);
                        let _ = enigo.key(key_of(vk), Direction::Release);
                        // The modifiers belonged to this key; they are let go with it.
                        for m in held_mods.drain(..) {
                            let _ = enigo.key(m, Direction::Release);
                        }
                    }
                }
                TandemInput::Text { text } => {
                    let _ = enigo.text(&text);
                }
                TandemInput::Media { key } => {
                    let _ = enigo.key(media_key(key), Direction::Click);
                }
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_of_the_phone_become_keys_of_windows() {
        assert_eq!(mac_to_vk(0), Some(0x41)); // a
        assert_eq!(mac_to_vk(8), Some(0x43)); // c, for Ctrl+C
        assert_eq!(mac_to_vk(9), Some(0x56)); // v
        assert_eq!(mac_to_vk(36), Some(0x0D)); // return
        assert_eq!(mac_to_vk(51), Some(0x08)); // delete is backspace
        assert_eq!(mac_to_vk(123), Some(0x25)); // left
        assert_eq!(mac_to_vk(126), Some(0x26)); // up
        assert_eq!(mac_to_vk(29), Some(0x30)); // 0
        assert_eq!(mac_to_vk(9999), None);
    }

    #[test]
    fn every_key_the_phone_sends_has_a_portable_name() {
        for code in 0u16..128 {
            if let Some(vk) = mac_to_vk(code) {
                assert!(portable_key(vk).is_some(), "mac code {code} (vk {vk:#x}) has no portable key");
            }
        }
        assert_eq!(portable_key(0x41), Some(Key::Unicode('a')));
        assert_eq!(portable_key(0x5A), Some(Key::Unicode('z')));
        assert_eq!(portable_key(0x39), Some(Key::Unicode('9')));
    }

    #[test]
    fn every_letter_and_digit_has_a_key() {
        // The phone's keyboard uses these 26 + 10 positions; none may be missing.
        let letters = [0u16, 11, 8, 2, 14, 3, 5, 4, 34, 38, 40, 37, 46, 45, 31, 35, 12, 15, 1, 17, 32, 9, 13, 7, 16, 6];
        let mut seen = HashSet::new();
        for code in letters {
            let vk = mac_to_vk(code).expect("a letter");
            assert!((0x41..=0x5A).contains(&vk));
            assert!(seen.insert(vk), "two codes for one letter");
        }
        assert_eq!(seen.len(), 26);
        let digits = [29u16, 18, 19, 20, 21, 23, 22, 26, 28, 25];
        let mut digit_keys: Vec<u16> = digits.iter().map(|c| mac_to_vk(*c).unwrap()).collect();
        digit_keys.sort_unstable();
        assert_eq!(digit_keys, (0x30..=0x39).collect::<Vec<u16>>());
    }

    #[test]
    fn command_and_control_are_both_ctrl() {
        assert_eq!(modifiers(8), vec![Key::Control]);
        assert_eq!(modifiers(2 | 8), vec![Key::Control]);
        assert_eq!(modifiers(1 | 4), vec![Key::Shift, Key::Alt]);
        assert!(modifiers(0).is_empty());
    }
}
