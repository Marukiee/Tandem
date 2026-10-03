//! The trackpad and keyboard of a phone, as real input on this PC.
//!
//! The phone sends macOS virtual key codes (what the Mac expects), so a code is translated to the Windows
//! virtual-key code of the same key. Everything is done on one thread of its own, in the order it arrived, so a
//! key that goes down is let go after it and a burst of pointer movements never holds up the engine.

use std::collections::HashSet;
use std::sync::{Mutex, OnceLock, mpsc};

use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};
use tandem_core::ffi::{TandemInput, TandemMediaKey};

enum Msg {
    Input(TandemInput),
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
        match msg {
            Msg::ReleaseAll => {
                for b in buttons.drain() {
                    let _ = enigo.button(button(b), Direction::Release);
                }
                for k in keys.drain() {
                    let _ = enigo.key(Key::Other(k as u32), Direction::Release);
                }
                for m in held_mods.drain(..) {
                    let _ = enigo.key(m, Direction::Release);
                }
            }
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
                        let _ = enigo.key(Key::Other(u32::from(vk)), Direction::Press);
                    } else {
                        keys.remove(&vk);
                        let _ = enigo.key(Key::Other(u32::from(vk)), Direction::Release);
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
