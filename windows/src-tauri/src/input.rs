//! The trackpad and keyboard of a phone, as real input on this PC.
//!
//! The phone sends macOS virtual key codes (what the Mac expects), so a code is translated to the Windows
//! virtual-key code of the same key. Everything is done on one thread of its own, in the order it arrived, so a
//! key that goes down is let go after it and a burst of pointer movements never holds up the engine.

use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Mutex, Once, OnceLock, mpsc};
use std::time::{Duration, Instant};

use enigo::{Axis, Button, Coordinate, Direction, Key};

use crate::hands::Driver;
use tandem_core::ffi::{TandemInput, TandemMediaInput, TandemMediaKey, TandemPointerShare};
use tandem_core::pointer_share::{Controlled, Edge, Screen};

enum Msg {
    Input(TandemInput),
    /// Input from a computer that has the pointer (see `pointer_share`): the movements come already accelerated.
    Shared(TandemInput),
    /// The pointer of such a computer comes in over `edge` of its screen, `along` of the way down.
    Enter { edge: Edge, along: f32 },
    /// Text that was being dragged over: it is let go here when the left button comes up.
    Carry(String),
    /// The shared pointer is gone (it went back, the link broke, the lid closed): let go of everything it held.
    End,
    /// What a device that looks at this screen does with its mouse and keyboard (see `host.rs`).
    Media(TandemMediaInput),
    /// The phone went away in the middle of something: let go of whatever it was holding.
    ReleaseAll,
    /// Try to get the desktop to take input again (it was refused or the question went unanswered).
    Retry,
}

static WORKER: OnceLock<Mutex<mpsc::Sender<Msg>>> = OnceLock::new();
/// Whether the library could connect to what plays the input on this system: 0 not known yet, 1 yes, 2 no.
static STATE: AtomicU8 = AtomicU8::new(0);

/// Gets the thing that plays input ready, so what it has to ask the person (a Wayland desktop does) is asked at a quiet moment.
pub fn warm_up() {
    let _ = worker();
    if STATE.load(Ordering::Relaxed) == 2 {
        let _ = try_again();
    }
}

/// Whether this system lets the app play pointer and keyboard input. A computer that cannot has to hand a pointer that comes
/// over straight back, or the person who sent it would be stuck on a screen that does not move.
pub fn ready() -> bool {
    let _ = worker();
    let mut asked_again = false;
    for _ in 0..40 {
        match STATE.load(Ordering::Relaxed) {
            1 => return true,
            2 => {
                // The desktop said no before (a locked screen, say): it is asked again now, and told a moment later whether it still says no.
                if asked_again || !try_again() {
                    return false;
                }
                asked_again = true;
            }
            _ => {}
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    false
}

/// Whether a pointer that came over now would be played, as far as is known without asking the desktop anything: only once it has said
/// yes, not while it is being asked and not after it said no.
pub fn could_take() -> bool {
    STATE.load(Ordering::Relaxed) == 1
}

/// The screen was locked and is not now. The desktop may have said no only because of that, so it is asked again.
pub fn unlocked() {
    if STATE.load(Ordering::Relaxed) == 2 {
        let _ = try_again();
    }
}

/// Whether the desktop was asked and did not let the app play input (yet): the person has something to do, and is told what.
pub fn needs_permission() -> bool {
    STATE.load(Ordering::Relaxed) == 2
}

/// Asks the desktop again, at most once in a few seconds: the question may have gone unseen, or been answered with no by mistake, or
/// the screen was locked and is not now.
fn try_again() -> bool {
    static LAST: Mutex<Option<Instant>> = Mutex::new(None);
    let mut last = LAST.lock().unwrap();
    if last.is_none_or(|t| t.elapsed() > Duration::from_secs(3)) {
        *last = Some(Instant::now());
        // Not known again until the desktop has answered.
        STATE.store(0, Ordering::Relaxed);
        let _ = worker().lock().unwrap().send(Msg::Retry);
        true
    } else {
        false
    }
}

static TROUBLE: OnceLock<Box<dyn Fn() + Send + Sync>> = OnceLock::new();

/// What to do when the desktop does not let this app play input although a computer wants to use it: the person is told what to allow.
pub fn on_trouble(told: impl Fn() + Send + Sync + 'static) {
    let _ = TROUBLE.set(Box::new(told));
}

pub fn trouble() {
    static LAST: Mutex<Option<Instant>> = Mutex::new(None);
    let mut last = LAST.lock().unwrap();
    if last.is_none_or(|t| t.elapsed() > Duration::from_secs(300)) {
        *last = Some(Instant::now());
        if let Some(told) = TROUBLE.get() {
            told();
        }
    }
}

fn worker() -> &'static Mutex<mpsc::Sender<Msg>> {
    WORKER.get_or_init(|| {
        let (tx, rx) = mpsc::channel();
        std::thread::Builder::new().name("tandem-input".into()).spawn(move || run(rx)).ok();
        Mutex::new(tx)
    })
}

/// The computer that has the pointer now, and the side of this screen it came in by.
struct Share {
    device: String,
    entered_by: Edge,
    /// When something was last heard from that computer. A link that goes quiet (a lid that closed, a network that went) must not
    /// keep the pointer of this computer in the hands of one that is not there any more.
    seen: Instant,
    /// Whether that computer has asked whether this one is still there. Only one that does can be judged by its silence: an older one
    /// says nothing while the person keeps the mouse still.
    pings: bool,
}

static SHARED: Mutex<Option<Share>> = Mutex::new(None);
/// Whether the left button of the shared mouse is down now. While it is, the pointer does not leave: a drag that runs into the edge
/// ends on the drop zone there, and a drag of text or files that came over ends where it is let go.
static LEFT_DOWN: AtomicBool = AtomicBool::new(false);

type Say = Box<dyn Fn(String, TandemPointerShare) + Send + Sync>;
static SAY: OnceLock<Say> = OnceLock::new();

/// What to do to tell the computer that has the pointer something: that the pointer ran into the edge it came in by, how big this
/// screen is, that the link is still there.
pub fn on_say(tell: impl Fn(String, TandemPointerShare) + Send + Sync + 'static) {
    let _ = SAY.set(Box::new(tell));
}

fn say(device: String, msg: TandemPointerShare) {
    if matches!(msg, TandemPointerShare::Leave { .. }) {
        log::info!("pointer: the pointer goes back to the computer that sent it");
    }
    if let Some(tell) = SAY.get() {
        tell(device, msg);
    }
}

static CHANGED: OnceLock<Box<dyn Fn(Option<(String, Edge)>) + Send + Sync>> = OnceLock::new();

/// What to do when another computer starts or stops using the pointer of this one: the computer and the edge of this screen it came in
/// by, or nothing. The drop zone at that edge shows and goes with it.
pub fn on_shared_change(told: impl Fn(Option<(String, Edge)>) + Send + Sync + 'static) {
    let _ = CHANGED.set(Box::new(told));
}

fn changed(now: Option<(String, Edge)>) {
    if let Some(told) = CHANGED.get() {
        told(now);
    }
}

static LEFT_UP: OnceLock<Box<dyn Fn() + Send + Sync>> = OnceLock::new();

/// What to do when the left button of the shared mouse comes up (files that came over with a drag are put down then).
pub fn on_left_up(told: impl Fn() + Send + Sync + 'static) {
    let _ = LEFT_UP.set(Box::new(told));
}

/// Whether the left button of the shared mouse is down.
pub fn left_down() -> bool {
    LEFT_DOWN.load(Ordering::Relaxed)
}

/// How long the computer that has the pointer may stay silent. It asks twice a second whether this one is still there.
const LOST_AFTER: Duration = Duration::from_millis(3500);

fn start_watchdog() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        std::thread::Builder::new()
            .name("tandem-share-watch".into())
            .spawn(|| loop {
                std::thread::sleep(Duration::from_millis(500));
                let lost = {
                    let mut guard = SHARED.lock().unwrap();
                    if guard.as_ref().is_some_and(|s| s.pings && s.seen.elapsed() > LOST_AFTER) {
                        guard.take()
                    } else {
                        None
                    }
                };
                if lost.is_some() {
                    changed(None);
                    let _ = worker().lock().unwrap().send(Msg::End);
                }
            })
            .ok();
    });
}

/// That computer asked whether this one is still there: from now on its silence counts.
pub fn pinged(device: &str) {
    if let Some(share) = SHARED.lock().unwrap().as_mut().filter(|s| s.device == device) {
        share.seen = Instant::now();
        share.pings = true;
    }
}

/// A computer sends its pointer over: it comes in at the opposite side of this screen.
pub fn shared_enter(device: String, edge: Edge, along: f32) {
    let entered_by = edge.opposite();
    *SHARED.lock().unwrap() = Some(Share { device: device.clone(), entered_by, seen: Instant::now(), pings: false });
    start_watchdog();
    let _ = worker().lock().unwrap().send(Msg::Enter { edge, along });
    changed(Some((device, entered_by)));
}

pub fn shared_end(device: &str) {
    let ended = {
        let mut guard = SHARED.lock().unwrap();
        if guard.as_ref().is_some_and(|s| s.device == device) {
            *guard = None;
            true
        } else {
            false
        }
    };
    if ended {
        changed(None);
        let _ = worker().lock().unwrap().send(Msg::End);
    }
}

/// This computer cannot be used from outside now (the lid was closed): the pointer goes back to the computer that has it.
pub fn shared_stop_here() {
    let ended = SHARED.lock().unwrap().take();
    if let Some(share) = ended {
        changed(None);
        say(share.device, TandemPointerShare::Leave { along: 0.5 });
        let _ = worker().lock().unwrap().send(Msg::End);
    }
}

pub fn shared_is(device: &str) -> bool {
    SHARED.lock().unwrap().as_ref().is_some_and(|s| s.device == device)
}

/// Text that was being dragged on the computer that has the pointer: it is let go here when the left button comes up.
pub fn shared_carry(text: String) {
    let _ = worker().lock().unwrap().send(Msg::Carry(text));
}

pub fn send_shared(input: TandemInput) {
    if let Some(share) = SHARED.lock().unwrap().as_mut() {
        share.seen = Instant::now();
    }
    let _ = worker().lock().unwrap().send(Msg::Shared(input));
}

pub fn send(input: TandemInput) {
    let _ = worker().lock().unwrap().send(Msg::Input(input));
}

/// The mouse or the keyboard of a device that looks at the screen of this computer.
pub fn send_media(input: TandemMediaInput) {
    let _ = worker().lock().unwrap().send(Msg::Media(input));
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

/// A key by its USB HID usage on the keyboard page, as the viewers of a screen send it. Letters, digits and the keys that are not
/// printed come from here; what is printed is typed as text.
fn hid_key(code: u32) -> Option<Key> {
    Some(match code {
        0x04..=0x1D => Key::Unicode((b'a' + (code - 0x04) as u8) as char),
        0x1E..=0x26 => Key::Unicode((b'1' + (code - 0x1E) as u8) as char),
        0x27 => Key::Unicode('0'),
        0x28 => Key::Return,
        0x29 => Key::Escape,
        0x2A => Key::Backspace,
        0x2B => Key::Tab,
        0x2C => Key::Space,
        0x2D => Key::Unicode('-'),
        0x2E => Key::Unicode('='),
        0x2F => Key::Unicode('['),
        0x30 => Key::Unicode(']'),
        0x31 => Key::Unicode('\\'),
        0x33 => Key::Unicode(';'),
        0x34 => Key::Unicode('\''),
        0x35 => Key::Unicode('`'),
        0x36 => Key::Unicode(','),
        0x37 => Key::Unicode('.'),
        0x38 => Key::Unicode('/'),
        0x39 => Key::CapsLock,
        0x3A => Key::F1,
        0x3B => Key::F2,
        0x3C => Key::F3,
        0x3D => Key::F4,
        0x3E => Key::F5,
        0x3F => Key::F6,
        0x40 => Key::F7,
        0x41 => Key::F8,
        0x42 => Key::F9,
        0x43 => Key::F10,
        0x44 => Key::F11,
        0x45 => Key::F12,
        0x4A => Key::Home,
        0x4B => Key::PageUp,
        0x4C => Key::Delete,
        0x4D => Key::End,
        0x4E => Key::PageDown,
        0x4F => Key::RightArrow,
        0x50 => Key::LeftArrow,
        0x51 => Key::DownArrow,
        0x52 => Key::UpArrow,
        _ => return None,
    })
}

/// The modifiers of a media key message: shift 1, control 2, alt 4, meta 8.
fn hid_modifiers(mods: u16) -> Vec<Key> {
    let mut keys = Vec::new();
    if mods & 1 != 0 {
        keys.push(Key::Shift);
    }
    if mods & 2 != 0 {
        keys.push(Key::Control);
    }
    if mods & 4 != 0 {
        keys.push(Key::Alt);
    }
    if mods & 8 != 0 {
        keys.push(Key::Meta);
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

/// The size of the screen the pointer moves on. On Linux it is asked from the X server itself: the library reports the first mode the
/// screen has, which is often not the one in use, and then the edges would be in the wrong place.
fn screen_size(enigo: &Driver) -> Option<(i32, i32)> {
    #[cfg(target_os = "linux")]
    {
        // On a Wayland desktop the driver knows (the layout of the screens); on X the server does.
        if !enigo.knows_location() {
            return enigo.main_display().ok();
        }
        let (w, h) = tandem_winsys::screen();
        if w > 0 && h > 0 {
            return Some((w, h));
        }
    }
    enigo.main_display().ok()
}

/// Moves the pointer for a movement of the shared mouse, and says where it ran into the edge it came in by, if it did (`along` of the
/// way down that side).
///
/// On Linux the pointer is put at the place that is counted, because the real place cannot be trusted there: a Wayland desktop shows
/// the X server only the part of the screen with old-style windows, and the speed of a move is changed by the X server. Counting and
/// placing from the same numbers keeps what is drawn and what is counted together.
#[cfg(target_os = "linux")]
fn shared_move(enigo: &mut Driver, tracker: Option<&mut Controlled>, dx: i16, dy: i16) -> Option<f32> {
    let Some(tracker) = tracker else {
        let _ = enigo.move_mouse(i32::from(dx), i32::from(dy), Coordinate::Rel);
        return None;
    };
    let leaving = tracker.moved(f32::from(dx), f32::from(dy));
    if leaving.is_none() {
        let (x, y) = tracker.at();
        let _ = enigo.move_mouse(x as i32, y as i32, Coordinate::Abs);
    }
    leaving
}

/// The same on Windows, where the real place of the pointer is known and the move is the one the person made.
#[cfg(not(target_os = "linux"))]
fn shared_move(enigo: &mut Driver, tracker: Option<&mut Controlled>, dx: i16, dy: i16) -> Option<f32> {
    let _ = enigo.move_mouse(i32::from(dx), i32::from(dy), Coordinate::Rel);
    let counted = tracker.and_then(|t| t.moved(f32::from(dx), f32::from(dy)));
    let entered_by = SHARED.lock().unwrap().as_ref().map(|s| s.entered_by)?;
    let (Ok((x, y)), Some((w, h))) = (enigo.location(), screen_size(enigo)) else { return counted };
    let (x, y, w, h) = (x as f32, y as f32, w as f32, h as f32);
    let leaving = match entered_by {
        Edge::Left => x <= 0.0 && dx < 0,
        Edge::Right => x >= w - 1.0 && dx > 0,
        Edge::Top => y <= 0.0 && dy < 0,
        Edge::Bottom => y >= h - 1.0 && dy > 0,
    };
    leaving.then(|| match entered_by {
        Edge::Left | Edge::Right => y / (h - 1.0).max(1.0),
        Edge::Top | Edge::Bottom => x / (w - 1.0).max(1.0),
    })
}

/// Text that was dragged over is let go at the pointer. On Linux the selection that a middle click pastes is set to it and the middle
/// button is clicked, which puts the text where the pointer is in nearly every text field and terminal. Elsewhere it goes by a click
/// to put the cursor there and a paste.
fn paste_here(enigo: &mut Driver, text: &str) {
    #[cfg(target_os = "linux")]
    {
        use arboard::{Clipboard, LinuxClipboardKind, SetExtLinux};
        // The selection is served by the clipboard that set it, so that one stays.
        static PRIMARY: Mutex<Option<Clipboard>> = Mutex::new(None);
        let mut held = PRIMARY.lock().unwrap();
        if held.is_none() {
            *held = Clipboard::new().ok();
        }
        if let Some(clipboard) = held.as_mut() {
            if clipboard.set().clipboard(LinuxClipboardKind::Primary).text(text.to_string()).is_ok() {
                let _ = enigo.button(Button::Middle, Direction::Click);
            }
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        if let Ok(mut clipboard) = arboard::Clipboard::new() {
            if clipboard.set_text(text.to_string()).is_ok() {
                let _ = enigo.button(Button::Left, Direction::Click);
                std::thread::sleep(Duration::from_millis(60));
                let _ = enigo.key(Key::Control, Direction::Press);
                let _ = enigo.key(key_of(0x56), Direction::Click);
                let _ = enigo.key(Key::Control, Direction::Release);
            }
        }
    }
}

fn run(rx: mpsc::Receiver<Msg>) {
    let mut enigo = loop {
        match Driver::new() {
            Ok(driver) => break driver,
            Err(error) => {
                log::warn!("the pointer and keyboard cannot be driven: {error}");
                STATE.store(2, Ordering::Relaxed);
                // Until there is a reason to ask again, what arrives is not played.
                loop {
                    match rx.recv() {
                        Ok(Msg::Retry) => break,
                        Ok(Msg::Enter { .. }) => trouble(),
                        Ok(_) => {}
                        Err(_) => return,
                    }
                }
            }
        }
    };
    STATE.store(1, Ordering::Relaxed);
    let mut buttons: HashSet<u8> = HashSet::new();
    let mut keys: HashSet<u16> = HashSet::new();
    let mut held_mods: Vec<Key> = Vec::new();
    // What is left of a scroll that did not make a whole click yet.
    let (mut rest_x, mut rest_y) = (0.0f32, 0.0f32);

    // Where the pointer is on this screen while another computer has it, counted from where it came in. On a system where the real
    // place cannot be asked (or is not worth trusting), this is what says the pointer ran into the edge it came in by.
    let mut tracker: Option<Controlled> = None;
    // Text that came over with a drag and waits for the button to come up.
    let mut carried: Option<String> = None;

    while let Ok(msg) = rx.recv() {
        let msg = match msg {
            Msg::Enter { edge, along } => {
                tracker = None;
                if !enigo.alive() {
                    // The desktop closed the session and would not open another: the pointer goes back at once.
                    if let Some(share) = SHARED.lock().unwrap().take() {
                        changed(None);
                        say(share.device, TandemPointerShare::Leave { along });
                    }
                    trouble();
                    continue;
                }
                if let Some((width, height)) = screen_size(&enigo) {
                    let screen = Screen { width: width as f32, height: height as f32 };
                    let counting = Controlled::enter(screen, edge, along);
                    let (x, y) = counting.at();
                    let _ = enigo.move_mouse(x as i32, y as i32, Coordinate::Abs);
                    tracker = Some(counting);
                    // The other computer follows where its pointer is over here with this, and takes it home by itself if it must.
                    if let Some(device) = SHARED.lock().unwrap().as_ref().map(|s| s.device.clone()) {
                        say(device, TandemPointerShare::Size { width: width.max(1) as u32, height: height.max(1) as u32 });
                    }
                }
                continue;
            }
            Msg::Carry(text) => {
                carried = Some(text);
                continue;
            }
            Msg::End => {
                tracker = None;
                carried = None;
                LEFT_DOWN.store(false, Ordering::Relaxed);
                Msg::ReleaseAll
            }
            Msg::Shared(TandemInput::Pointer { dx, dy }) => {
                if !enigo.alive() {
                    // Moves that do nothing leave the person stuck on a screen that does not answer: the pointer goes home.
                    if let Some(share) = SHARED.lock().unwrap().take() {
                        changed(None);
                        say(share.device, TandemPointerShare::Leave { along: 0.5 });
                        tracker = None;
                        carried = None;
                        buttons.clear();
                        LEFT_DOWN.store(false, Ordering::Relaxed);
                        trouble();
                    }
                    continue;
                }
                let leaving = shared_move(&mut enigo, tracker.as_mut(), dx, dy);
                if let Some(along) = leaving {
                    // A drag runs into the edge: it stays there, over the drop zone, until the button comes up.
                    if !buttons.contains(&0) {
                        let ended = SHARED.lock().unwrap().take();
                        if let Some(share) = ended {
                            changed(None);
                            say(share.device, TandemPointerShare::Leave { along: along.clamp(0.0, 1.0) });
                            tracker = None;
                            carried = None;
                            for b in buttons.drain() {
                                let _ = enigo.button(button(b), Direction::Release);
                            }
                            LEFT_DOWN.store(false, Ordering::Relaxed);
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
                LEFT_DOWN.store(false, Ordering::Relaxed);
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
            Msg::Shared(_) | Msg::Enter { .. } | Msg::Carry(_) | Msg::End | Msg::Retry => {}
            Msg::Media(media) => match media {
                TandemMediaInput::PointerAbs { x, y } => {
                    if let Some((width, height)) = screen_size(&enigo) {
                        let _ = enigo.move_mouse((x * width as f32) as i32, (y * height as f32) as i32, Coordinate::Abs);
                    }
                }
                TandemMediaInput::PointerRel { dx, dy } => {
                    let _ = enigo.move_mouse(i32::from(dx), i32::from(dy), Coordinate::Rel);
                }
                TandemMediaInput::Button { button: b, down, .. } => {
                    let which = match b {
                        1 => Button::Right,
                        2 => Button::Middle,
                        3 => Button::Back,
                        4 => Button::Forward,
                        _ => Button::Left,
                    };
                    if down {
                        buttons.insert(b);
                        let _ = enigo.button(which, Direction::Press);
                    } else {
                        buttons.remove(&b);
                        let _ = enigo.button(which, Direction::Release);
                    }
                }
                TandemMediaInput::Scroll { dx, dy } => {
                    // Pixels in the natural direction: the content follows the fingers, so up with the fingers is down with the wheel.
                    rest_y -= f32::from(dy) / PIXELS_PER_CLICK;
                    rest_x -= f32::from(dx) / PIXELS_PER_CLICK;
                    let (clicks_y, clicks_x) = (rest_y.trunc(), rest_x.trunc());
                    if clicks_y != 0.0 {
                        let _ = enigo.scroll(-(clicks_y as i32), Axis::Vertical);
                        rest_y -= clicks_y;
                    }
                    if clicks_x != 0.0 {
                        let _ = enigo.scroll(-(clicks_x as i32), Axis::Horizontal);
                        rest_x -= clicks_x;
                    }
                }
                TandemMediaInput::Key { code, down, mods, .. } => {
                    let Some(key) = hid_key(code) else { continue };
                    let wanted = hid_modifiers(mods);
                    if down {
                        for m in &wanted {
                            if !held_mods.contains(m) {
                                let _ = enigo.key(*m, Direction::Press);
                                held_mods.push(*m);
                            }
                        }
                        let _ = enigo.key(key, Direction::Press);
                    } else {
                        let _ = enigo.key(key, Direction::Release);
                        for m in held_mods.drain(..) {
                            let _ = enigo.key(m, Direction::Release);
                        }
                    }
                }
                TandemMediaInput::Text { text } => {
                    let _ = enigo.text(&text);
                }
            },
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
                    if b == 0 {
                        LEFT_DOWN.store(down, Ordering::Relaxed);
                        if !down {
                            // What was dragged over is let go where the pointer is.
                            if let Some(text) = carried.take() {
                                paste_here(&mut enigo, &text);
                            }
                            if let Some(told) = LEFT_UP.get() {
                                told();
                            }
                        }
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
    fn a_hid_usage_gives_the_key_that_is_printed_on_it() {
        assert_eq!(hid_key(0x04), Some(Key::Unicode('a')));
        assert_eq!(hid_key(0x1D), Some(Key::Unicode('z')));
        assert_eq!(hid_key(0x1E), Some(Key::Unicode('1')));
        assert_eq!(hid_key(0x27), Some(Key::Unicode('0')));
        assert_eq!(hid_key(0x28), Some(Key::Return));
        assert_eq!(hid_key(0x52), Some(Key::UpArrow));
        assert_eq!(hid_key(0x03), None);
    }

    #[test]
    fn modifiers_follow_the_bits() {
        assert_eq!(hid_modifiers(0), Vec::<Key>::new());
        assert_eq!(hid_modifiers(1 | 8), vec![Key::Shift, Key::Meta]);
    }

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
