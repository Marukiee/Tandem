//! Linux: a card under the pointer with files that were just put down, which the person can pick up and drag on. Like the strip of
//! `edgecatch` it is a window of the X server (XWayland under Wayland), the one kind of window that may say where it goes; a Wayland
//! window may not. The drag is the X one (XDND) with this window as its source; GNOME hands it on to Wayland windows itself, as it
//! does for any old-style app, because it starts with a press of the person.
//!
//! The card goes by itself after a few seconds without being touched, once its files were dropped somewhere, or with Escape during the
//! drag (it then slides back). Every step is written to the log, since none of this can be seen anywhere but on a real desktop.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use x11rb::connection::Connection;
use x11rb::protocol::Event;
use x11rb::protocol::xproto::{
    AtomEnum, ClientMessageEvent, ColormapAlloc, ConfigureWindowAux, ConnectionExt as _, CreateGCAux, CreateWindowAux, EventMask,
    GrabMode, ImageFormat, MapState, PropMode, SelectionNotifyEvent, SelectionRequestEvent, VisualClass, WindowClass,
};
use x11rb::rust_connection::RustConnection;
use x11rb::wrapper::ConnectionExt as _;
use x11rb::{CURRENT_TIME, NONE};

x11rb::atom_manager! {
    Atoms: AtomsCookie {
        XdndAware,
        XdndProxy,
        XdndEnter,
        XdndPosition,
        XdndStatus,
        XdndLeave,
        XdndDrop,
        XdndFinished,
        XdndSelection,
        XdndActionCopy,
        TARGETS,
        UriList: b"text/uri-list",
    }
}

/// How long a card that nobody touches stays.
const STAYS: Duration = Duration::from_secs(6);
/// How far the pointer moves with the button down before it is a drag and not a click.
const DRAG_AFTER: i32 = 4;
/// The key that ends a drag without dropping (Escape on every keyboard layout of the X server).
const ESCAPE: u8 = 9;

/// New pixels and files for a card that is up.
struct Replace {
    pixels: Vec<u8>,
    uris: Vec<String>,
}

struct Live {
    id: u64,
    tx: mpsc::Sender<Replace>,
    /// Picked up: what comes in from now on gets a card of its own.
    taken: Arc<AtomicBool>,
}

static LIVE: Mutex<Option<Live>> = Mutex::new(None);
static NEXT: AtomicU64 = AtomicU64::new(1);

/// Whether a card is up and not picked up yet, so new files can go on it.
pub fn open() -> bool {
    LIVE.lock().unwrap().as_ref().is_some_and(|l| !l.taken.load(Ordering::SeqCst))
}

/// Shows a card of `size` by `size` pixels (blue, green, red, alpha, premultiplied) for `uris`, centred on the place `at` (parts of the
/// width and height of the screen). A card that is up and not picked up gets these instead. False when there is no X server or it
/// would not make the window.
pub fn show(pixels: Vec<u8>, size: u16, at: (f32, f32), uris: Vec<String>) -> bool {
    if pixels.len() != size as usize * size as usize * 4 || uris.is_empty() {
        return false;
    }
    {
        let live = LIVE.lock().unwrap();
        if let Some(live) = live.as_ref().filter(|l| !l.taken.load(Ordering::SeqCst)) {
            if live.tx.send(Replace { pixels: pixels.clone(), uris: uris.clone() }).is_ok() {
                return true;
            }
        }
    }
    let card = match Card::open(size, at) {
        Ok(card) => card,
        Err(why) => {
            log::info!("dragcard: no card ({why})");
            return false;
        }
    };
    let (tx, rx) = mpsc::channel();
    let taken = Arc::new(AtomicBool::new(false));
    let id = NEXT.fetch_add(1, Ordering::SeqCst);
    *LIVE.lock().unwrap() = Some(Live { id, tx, taken: taken.clone() });
    let started = std::thread::Builder::new()
        .name("tandem-dragcard".into())
        .spawn(move || {
            card.run(pixels, uris, rx, &taken);
            let mut live = LIVE.lock().unwrap();
            if live.as_ref().is_some_and(|l| l.id == id) {
                *live = None;
            }
        })
        .is_ok();
    if !started {
        *LIVE.lock().unwrap() = None;
    }
    started
}

/// Where on the X screen the card goes: centred on the pointer, kept inside the screen.
fn place(at: (f32, f32), root: (u16, u16), size: u16) -> (i16, i16) {
    let (x, y) = (at.0.clamp(0.0, 1.0) * root.0 as f32, at.1.clamp(0.0, 1.0) * root.1 as f32);
    let half = size as f32 / 2.0;
    let max_x = (root.0 as f32 - size as f32).max(0.0);
    let max_y = (root.1 as f32 - size as f32).max(0.0);
    ((x - half).clamp(0.0, max_x) as i16, (y - half).clamp(0.0, max_y) as i16)
}

/// The `text/uri-list` that a window that takes the drop asks for: one URI a line, each line ended the way the list wants.
fn uri_list(uris: &[String]) -> String {
    uris.iter().map(|u| format!("{u}\r\n")).collect()
}

/// The pixels with their alpha (and so, premultiplied, their colours) taken down to `k`.
fn faded(pixels: &[u8], k: f32) -> Vec<u8> {
    if k >= 1.0 {
        return pixels.to_vec();
    }
    pixels.iter().map(|&v| (v as f32 * k.max(0.0)).round() as u8).collect()
}

/// A window that may take the drop: the one the messages are about, the one they go to (another one when it has a proxy), and the
/// version of XDND it speaks.
#[derive(Clone, Copy, PartialEq)]
struct Target {
    window: u32,
    to: u32,
    version: u32,
}

enum State {
    Resting { since: Instant },
    Pressed { time: u32, x: i16, y: i16, offset: (i16, i16) },
    Dragging(Drag),
    Dropped { since: Instant },
}

struct Drag {
    time: u32,
    offset: (i16, i16),
    target: Option<Target>,
    accepted: bool,
    /// A position was sent and its answer has not come yet; the next one waits (XDND asks for that).
    waiting: bool,
    pending: Option<(i16, i16, u32)>,
}

struct Card {
    connection: RustConnection,
    window: u32,
    gc: u32,
    root: u32,
    size: u16,
    home: (i16, i16),
    atoms: Atoms,
}

impl Card {
    fn open(size: u16, at: (f32, f32)) -> Result<Card, String> {
        let (connection, number) = x11rb::connect(None).map_err(|e| format!("no X server: {e}"))?;
        let screen = connection.setup().roots.get(number).ok_or("no screen")?.clone();
        let atoms = Atoms::new(&connection).map_err(|e| e.to_string())?.reply().map_err(|e| e.to_string())?;
        let visual = screen
            .allowed_depths
            .iter()
            .find(|d| d.depth == 32)
            .and_then(|d| d.visuals.iter().find(|v| v.class == VisualClass::TRUE_COLOR))
            .map(|v| v.visual_id)
            .ok_or("no visual with alpha")?;
        let colormap = connection.generate_id().map_err(|e| e.to_string())?;
        connection.create_colormap(ColormapAlloc::NONE, colormap, screen.root, visual).map_err(|e| e.to_string())?;
        let home = place(at, (screen.width_in_pixels, screen.height_in_pixels), size);
        let window = connection.generate_id().map_err(|e| e.to_string())?;
        let aux = CreateWindowAux::new()
            .background_pixel(0)
            .border_pixel(0)
            .colormap(colormap)
            .override_redirect(1)
            .event_mask(
                EventMask::EXPOSURE
                    | EventMask::BUTTON_PRESS
                    | EventMask::BUTTON_RELEASE
                    | EventMask::POINTER_MOTION
                    | EventMask::ENTER_WINDOW
                    | EventMask::LEAVE_WINDOW
                    | EventMask::KEY_PRESS,
            );
        connection
            .create_window(32, window, screen.root, home.0, home.1, size, size, 0, WindowClass::INPUT_OUTPUT, visual, &aux)
            .map_err(|e| e.to_string())?;
        let gc = connection.generate_id().map_err(|e| e.to_string())?;
        connection.create_gc(gc, window, &CreateGCAux::new()).map_err(|e| e.to_string())?;
        connection.map_window(window).map_err(|e| e.to_string())?;
        connection.flush().map_err(|e| e.to_string())?;
        log::info!("dragcard: up at {home:?} on a screen of {}x{}", screen.width_in_pixels, screen.height_in_pixels);
        Ok(Card { connection, window, gc, root: screen.root, size, home, atoms })
    }

    fn draw(&self, pixels: &[u8], k: f32) {
        let _ = self.connection.put_image(ImageFormat::Z_PIXMAP, self.window, self.gc, self.size, self.size, 0, 0, 0, 32, &faded(pixels, k));
        let _ = self.connection.flush();
    }

    fn fade(&self, pixels: &[u8], from: f32, to: f32) {
        const FRAMES: u32 = 10;
        for frame in 1..=FRAMES {
            let t = frame as f32 / FRAMES as f32;
            // Eased out: quick at first, settling at the end.
            let eased = 1.0 - (1.0 - t) * (1.0 - t);
            self.draw(pixels, from + (to - from) * eased);
            std::thread::sleep(Duration::from_millis(22));
        }
    }

    fn move_to(&self, x: i16, y: i16) {
        let _ = self.connection.configure_window(self.window, &ConfigureWindowAux::new().x(i32::from(x)).y(i32::from(y)));
        let _ = self.connection.flush();
    }

    /// Slides back to where it was, after a drag that was not dropped anywhere.
    fn slide_home(&self, from: (i16, i16)) {
        const FRAMES: i32 = 12;
        for frame in 1..=FRAMES {
            let t = frame as f32 / FRAMES as f32;
            let eased = 1.0 - (1.0 - t).powi(3);
            let x = from.0 as f32 + (self.home.0 - from.0) as f32 * eased;
            let y = from.1 as f32 + (self.home.1 - from.1) as f32 * eased;
            self.move_to(x as i16, y as i16);
            std::thread::sleep(Duration::from_millis(16));
        }
    }

    fn send(&self, target: Target, kind: u32, data: [u32; 5]) {
        let event = ClientMessageEvent::new(32, target.window, kind, data);
        let _ = self.connection.send_event(false, target.to, EventMask::NO_EVENT, event);
        let _ = self.connection.flush();
    }

    /// The window under `(x, y)` that takes drops, leaving out this card, which lies under the pointer itself.
    fn target_at(&self, x: i16, y: i16) -> Option<Target> {
        let tree = self.connection.query_tree(self.root).ok()?.reply().ok()?;
        // Children come bottom first.
        let top = tree.children.iter().rev().copied().filter(|&w| w != self.window).find(|&w| {
            let viewable = self
                .connection
                .get_window_attributes(w)
                .ok()
                .and_then(|c| c.reply().ok())
                .is_some_and(|a| a.map_state == MapState::VIEWABLE);
            viewable
                && self.connection.get_geometry(w).ok().and_then(|c| c.reply().ok()).is_some_and(|g| {
                    let (gx, gy) = (i32::from(g.x), i32::from(g.y));
                    let (px, py) = (i32::from(x), i32::from(y));
                    px >= gx && py >= gy && px < gx + i32::from(g.width) && py < gy + i32::from(g.height)
                })
        })?;
        let mut window = top;
        for _ in 0..10 {
            if let Some(target) = self.aware(window) {
                return Some(target);
            }
            let inside = self.connection.translate_coordinates(self.root, window, x, y).ok()?.reply().ok()?;
            if inside.child == NONE {
                return None;
            }
            window = inside.child;
        }
        None
    }

    fn aware(&self, window: u32) -> Option<Target> {
        let read = |w: u32, name: u32, kind: AtomEnum| -> Option<u32> {
            let reply = self.connection.get_property(false, w, name, kind, 0, 1).ok()?.reply().ok()?;
            reply.value32()?.next()
        };
        let proxy = read(window, self.atoms.XdndProxy, AtomEnum::WINDOW);
        let version = read(proxy.unwrap_or(window), self.atoms.XdndAware, AtomEnum::ATOM).or_else(|| read(window, self.atoms.XdndAware, AtomEnum::ATOM))?;
        (version >= 3).then_some(Target { window, to: proxy.unwrap_or(window), version: version.min(5) })
    }

    fn position(&self, drag: &mut Drag, x: i16, y: i16, time: u32) {
        let Some(target) = drag.target else { return };
        if drag.waiting {
            drag.pending = Some((x, y, time));
            return;
        }
        let at = (u32::from(x as u16) << 16) | u32::from(y as u16);
        self.send(target, self.atoms.XdndPosition, [self.window, 0, at, time, self.atoms.XdndActionCopy]);
        drag.waiting = true;
        drag.pending = None;
    }

    fn answer(&self, request: &SelectionRequestEvent, uris: &[String]) {
        let property = if request.property == NONE { request.target } else { request.property };
        let given = if request.target == self.atoms.TARGETS {
            self.connection.change_property32(PropMode::REPLACE, request.requestor, property, AtomEnum::ATOM, &[self.atoms.TARGETS, self.atoms.UriList]).is_ok()
        } else if request.target == self.atoms.UriList {
            log::info!("dragcard: the files were asked for");
            self.connection.change_property8(PropMode::REPLACE, request.requestor, property, request.target, uri_list(uris).as_bytes()).is_ok()
        } else {
            false
        };
        let note = SelectionNotifyEvent {
            response_type: x11rb::protocol::xproto::SELECTION_NOTIFY_EVENT,
            sequence: 0,
            time: request.time,
            requestor: request.requestor,
            selection: request.selection,
            target: request.target,
            property: if given { property } else { NONE },
        };
        let _ = self.connection.send_event(false, request.requestor, EventMask::NO_EVENT, note);
        let _ = self.connection.flush();
    }

    fn run(self, mut pixels: Vec<u8>, mut uris: Vec<String>, more: mpsc::Receiver<Replace>, taken: &AtomicBool) {
        self.fade(&pixels, 0.0, 1.0);
        let mut state = State::Resting { since: Instant::now() };
        let mut over = false;
        let born = Instant::now();
        loop {
            if let Ok(replace) = more.try_recv() {
                pixels = replace.pixels;
                uris = replace.uris;
                self.draw(&pixels, 1.0);
                if let State::Resting { since } = &mut state {
                    *since = Instant::now();
                }
            }
            // Of a run of moves only the last one counts.
            let mut events = Vec::new();
            loop {
                match self.connection.poll_for_event() {
                    Ok(Some(event)) => events.push(event),
                    Ok(None) => break,
                    Err(_) => return,
                }
            }
            let last_motion = events.iter().rposition(|e| matches!(e, Event::MotionNotify(_)));
            for (i, event) in events.into_iter().enumerate() {
                match event {
                    Event::Expose(_) => self.draw(&pixels, 1.0),
                    Event::EnterNotify(_) => over = true,
                    Event::LeaveNotify(_) => over = false,
                    Event::ButtonPress(press) if press.detail == 1 && matches!(state, State::Resting { .. }) => {
                        log::info!("dragcard: pressed");
                        taken.store(true, Ordering::SeqCst);
                        let _ = self.connection.grab_keyboard(false, self.window, press.time, GrabMode::ASYNC, GrabMode::ASYNC);
                        let _ = self.connection.flush();
                        state = State::Pressed { time: press.time, x: press.root_x, y: press.root_y, offset: (press.event_x, press.event_y) };
                    }
                    Event::MotionNotify(motion) if Some(i) == last_motion => {
                        if let State::Pressed { time, x, y, offset } = state {
                            let moved = (i32::from(motion.root_x) - i32::from(x)).abs() + (i32::from(motion.root_y) - i32::from(y)).abs();
                            if moved < DRAG_AFTER {
                                continue;
                            }
                            // The press of the person is what lets the desktop take this drag on to its own windows.
                            let _ = self.connection.set_selection_owner(self.window, self.atoms.XdndSelection, time);
                            let owner = self.connection.get_selection_owner(self.atoms.XdndSelection).ok().and_then(|c| c.reply().ok()).map(|r| r.owner);
                            if owner != Some(self.window) {
                                log::info!("dragcard: the drag could not start (owner {owner:?})");
                                state = State::Resting { since: Instant::now() };
                                continue;
                            }
                            log::info!("dragcard: drag started");
                            state = State::Dragging(Drag { time, offset, target: None, accepted: false, waiting: false, pending: None });
                        }
                        if let State::Dragging(drag) = &mut state {
                            self.move_to(motion.root_x - drag.offset.0, motion.root_y - drag.offset.1);
                            let found = self.target_at(motion.root_x, motion.root_y);
                            if found != drag.target {
                                if let Some(old) = drag.target.take() {
                                    self.send(old, self.atoms.XdndLeave, [self.window, 0, 0, 0, 0]);
                                }
                                if let Some(new) = found {
                                    log::info!("dragcard: over window {:#x} (XDND {})", new.window, new.version);
                                    self.send(new, self.atoms.XdndEnter, [self.window, new.version << 24, self.atoms.UriList, 0, 0]);
                                }
                                drag.target = found;
                                drag.accepted = false;
                                drag.waiting = false;
                            }
                            self.position(drag, motion.root_x, motion.root_y, motion.time);
                        }
                    }
                    Event::ClientMessage(message) if message.type_ == self.atoms.XdndStatus => {
                        if let State::Dragging(drag) = &mut state {
                            let data = message.data.as_data32();
                            if drag.target.is_some_and(|t| t.window == data[0]) {
                                let accepted = data[1] & 1 == 1;
                                if accepted != drag.accepted {
                                    log::info!("dragcard: {}", if accepted { "the window takes it" } else { "the window does not take it" });
                                }
                                drag.accepted = accepted;
                                drag.waiting = false;
                                if let Some((x, y, time)) = drag.pending {
                                    self.position(drag, x, y, time);
                                }
                            }
                        }
                    }
                    Event::ButtonRelease(release) if release.detail == 1 => {
                        let _ = self.connection.ungrab_keyboard(release.time);
                        match std::mem::replace(&mut state, State::Resting { since: Instant::now() }) {
                            State::Dragging(drag) => match drag.target {
                                Some(target) if drag.accepted => {
                                    log::info!("dragcard: dropped");
                                    self.send(target, self.atoms.XdndDrop, [self.window, 0, release.time, 0, 0]);
                                    state = State::Dropped { since: Instant::now() };
                                }
                                other => {
                                    if let Some(target) = other {
                                        self.send(target, self.atoms.XdndLeave, [self.window, 0, 0, 0, 0]);
                                    }
                                    log::info!("dragcard: let go where nothing takes it, back");
                                    self.slide_home((release.root_x - drag.offset.0, release.root_y - drag.offset.1));
                                    let _ = self.connection.set_selection_owner(NONE, self.atoms.XdndSelection, drag.time);
                                    taken.store(false, Ordering::SeqCst);
                                }
                            },
                            // A click and not a drag: the card stays.
                            _ => taken.store(false, Ordering::SeqCst),
                        }
                        let _ = self.connection.flush();
                    }
                    Event::KeyPress(key) if key.detail == ESCAPE => {
                        if let State::Dragging(drag) = std::mem::replace(&mut state, State::Resting { since: Instant::now() }) {
                            if let Some(target) = drag.target {
                                self.send(target, self.atoms.XdndLeave, [self.window, 0, 0, 0, 0]);
                            }
                            log::info!("dragcard: Escape, back");
                            let _ = self.connection.ungrab_keyboard(CURRENT_TIME);
                            let at = self.connection.query_pointer(self.root).ok().and_then(|c| c.reply().ok());
                            let from = at.map(|p| (p.root_x - drag.offset.0, p.root_y - drag.offset.1)).unwrap_or(self.home);
                            self.slide_home(from);
                            let _ = self.connection.set_selection_owner(NONE, self.atoms.XdndSelection, drag.time);
                            taken.store(false, Ordering::SeqCst);
                        }
                    }
                    Event::SelectionRequest(request) if request.selection == self.atoms.XdndSelection => self.answer(&request, &uris),
                    Event::ClientMessage(message) if message.type_ == self.atoms.XdndFinished => {
                        let data = message.data.as_data32();
                        log::info!("dragcard: finished, {}", if data[1] & 1 == 1 { "taken" } else { "not taken" });
                        self.fade(&pixels, 1.0, 0.0);
                        return;
                    }
                    _ => {}
                }
            }
            let gone = match &state {
                State::Resting { since } => !over && since.elapsed() > STAYS,
                // Some windows never say they are done: the files were asked for, that is enough.
                State::Dropped { since } => since.elapsed() > Duration::from_secs(4),
                State::Pressed { .. } | State::Dragging(_) => false,
            };
            // However it was left, a card does not stay for good.
            if gone || born.elapsed() > Duration::from_secs(120) {
                log::info!("dragcard: gone after {:.1} s", born.elapsed().as_secs_f32());
                self.fade(&pixels, 1.0, 0.0);
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }
}

impl Drop for Card {
    fn drop(&mut self) {
        let _ = self.connection.destroy_window(self.window);
        let _ = self.connection.flush();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_card_is_centred_on_the_pointer_and_kept_on_the_screen() {
        // The middle of a screen that XWayland shows at its logical size.
        assert_eq!(place((0.5, 0.5), (1920, 1200), 112), (904, 544));
        // At the edges it stays whole.
        assert_eq!(place((1.0, 0.0), (1920, 1200), 112), (1808, 0));
        assert_eq!(place((-0.2, 1.4), (1920, 1200), 112), (0, 1088));
    }

    #[test]
    fn the_list_has_one_uri_a_line() {
        let list = uri_list(&["file:///home/m/Downloads/a%20b.png".into(), "file:///home/m/Downloads/c.pdf".into()]);
        assert_eq!(list, "file:///home/m/Downloads/a%20b.png\r\nfile:///home/m/Downloads/c.pdf\r\n");
        assert_eq!(crate::uri_list_paths(&list), vec!["/home/m/Downloads/a b.png", "/home/m/Downloads/c.pdf"]);
    }

    #[test]
    fn fading_takes_colour_and_alpha_down_together() {
        assert_eq!(faded(&[200, 100, 50, 255], 0.5), vec![100, 50, 25, 128]);
        assert_eq!(faded(&[200, 100, 50, 255], 1.0), vec![200, 100, 50, 255]);
        assert_eq!(faded(&[200, 100, 50, 255], 0.0), vec![0, 0, 0, 0]);
    }
}
