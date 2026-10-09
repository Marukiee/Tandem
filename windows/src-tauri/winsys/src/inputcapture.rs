//! Wayland: the mouse and keyboard of this computer on their way to another computer.
//!
//! The desktop watches the pointer at the places on the edges of the screens where another computer sits (the barriers of the
//! InputCapture portal). When the pointer runs into one, the desktop stops giving the mouse and keyboard to its windows and hands
//! them to this program instead, through libei (which `reis` speaks). [`WaylandCapture::release`] gives them back, and says where
//! the pointer is to be put. The person is asked once, and the desktop remembers the answer (a token in a file).

use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use ashpd::desktop::PersistMode;
use ashpd::desktop::input_capture::{
    ActivatedBarrier, Barrier, Capabilities, ConnectToEISOptions, DisableOptions, EnableOptions, GetZonesOptions, InputCapture, ReleaseOptions,
    SetPointerBarriersOptions, StartOptions,
};
use futures_util::StreamExt;
use reis::ei;
use reis::event::{DeviceCapability, EiEvent};

use crate::Seen;

/// A side of the screens.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Side {
    Left,
    Right,
    Top,
    Bottom,
}

/// The part of an edge of the screens, taken together, where another computer sits: from `from` to `to` along that edge (pixels, from
/// the start of the edge). `id` is the caller's own number for it, given back when the pointer runs into it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Stretch {
    pub id: u32,
    pub side: Side,
    pub from: i32,
    pub to: i32,
}

/// What the desktop says.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Captured {
    /// The pointer ran into the edge at the stretch `id` (0 when that is not known), at this place of the screens.
    Activated { id: u32, x: i32, y: i32 },
    /// The desktop took the mouse and keyboard back by itself.
    Deactivated,
    /// What the mouse and keyboard do while they are handed over.
    Seen(Seen),
}

/// Whether the desktop lets this program capture the mouse and keyboard, as far as is known.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    /// Being set up, which includes the desktop asking the person.
    Starting,
    Ready,
    /// The desktop said no, or cannot do it.
    Failed,
}

struct Shared {
    state: AtomicU8,
    reason: Mutex<String>,
    /// The box of all the screens: left, top, right, bottom (the right and bottom edge not included).
    zone: Mutex<(i32, i32, i32, i32)>,
    activation: Mutex<Option<u32>>,
    stretches: Mutex<Vec<Stretch>>,
}

enum Cmd {
    /// The stretches changed.
    Changed,
    Release { x: f64, y: f64 },
    Stop,
}

pub struct WaylandCapture {
    cmd: async_channel::Sender<Cmd>,
    shared: Arc<Shared>,
}

/// Whether the desktop offers to capture the mouse and keyboard.
pub fn available() -> bool {
    crate::portal::portal_property("org.freedesktop.portal.InputCapture", "SupportedCapabilities").is_some_and(|caps| caps & 0b11 != 0)
}

impl WaylandCapture {
    /// Sets the capture up on a thread of its own. The first time the desktop asks the person; this does not wait for that.
    pub fn start(handler: impl Fn(Captured) + Send + Sync + 'static) -> WaylandCapture {
        let (cmd, rx) = async_channel::unbounded();
        let shared = Arc::new(Shared {
            state: AtomicU8::new(0),
            reason: Mutex::new(String::new()),
            zone: Mutex::new((0, 0, 0, 0)),
            activation: Mutex::new(None),
            stretches: Mutex::new(Vec::new()),
        });
        let handler: Arc<dyn Fn(Captured) + Send + Sync> = Arc::new(handler);
        let inner = shared.clone();
        std::thread::Builder::new()
            .name("tandem-capture".into())
            .spawn(move || run(rx, handler, inner))
            .ok();
        WaylandCapture { cmd, shared }
    }

    pub fn state(&self) -> State {
        match self.shared.state.load(Ordering::Relaxed) {
            1 => State::Ready,
            2 => State::Failed,
            _ => State::Starting,
        }
    }

    /// Why it did not work, in the words of the desktop.
    pub fn reason(&self) -> String {
        self.shared.reason.lock().unwrap().clone()
    }

    /// The box of all the screens, as the desktop counts them: left, top, right, bottom. Empty until the desktop has said.
    pub fn zone(&self) -> (i32, i32, i32, i32) {
        *self.shared.zone.lock().unwrap()
    }

    /// Where on the edges of the screens another computer sits now. Called again when that changes.
    pub fn set_stretches(&self, stretches: Vec<Stretch>) {
        {
            let mut current = self.shared.stretches.lock().unwrap();
            if *current == stretches {
                return;
            }
            *current = stretches;
        }
        let _ = self.cmd.try_send(Cmd::Changed);
    }

    /// Gives the mouse and keyboard back to this computer, with the pointer at this place of the screens.
    pub fn release(&self, x: i32, y: i32) {
        let _ = self.cmd.try_send(Cmd::Release { x: f64::from(x), y: f64::from(y) });
    }
}

impl Drop for WaylandCapture {
    fn drop(&mut self) {
        let _ = self.cmd.try_send(Cmd::Stop);
    }
}

// ---- The barriers ------------------------------------------------------------------------------------------------------------------

/// How many barriers one stretch can be made of (one for every screen that touches the edge it is on).
const PER_STRETCH: u32 = 16;

/// The barriers for the stretches, given the screens (x, y, width, height): one for every screen the stretch passes along, in the
/// coordinates of the desktop, as (barrier id, stretch id, x1, y1, x2, y2). A barrier lies on the line between the last pixel of a screen
/// and where the next would be: for the right and bottom edge that is one further than the last pixel, because GNOME turns down a line
/// that overlaps the screen ("Line overlaps with monitor region").
fn barriers_for(regions: &[(i32, i32, i32, i32)], stretches: &[Stretch]) -> Vec<(u32, u32, (i32, i32, i32, i32))> {
    if regions.is_empty() {
        return Vec::new();
    }
    let left = regions.iter().map(|r| r.0).min().unwrap_or(0);
    let top = regions.iter().map(|r| r.1).min().unwrap_or(0);
    let right = regions.iter().map(|r| r.0 + r.2).max().unwrap_or(0);
    let bottom = regions.iter().map(|r| r.1 + r.3).max().unwrap_or(0);
    let mut out = Vec::new();
    for stretch in stretches {
        for (index, &(x, y, w, h)) in regions.iter().enumerate().take(PER_STRETCH as usize - 1) {
            let id = stretch.id * PER_STRETCH + index as u32 + 1;
            // The part of the stretch that lies on this screen, if the screen is on that edge at all.
            let line = match stretch.side {
                Side::Left if x == left => {
                    let (a, b) = (top + stretch.from, top + stretch.to - 1);
                    (a.max(y) <= b.min(y + h - 1)).then(|| (left, a.max(y), left, b.min(y + h - 1)))
                }
                Side::Right if x + w == right => {
                    let (a, b) = (top + stretch.from, top + stretch.to - 1);
                    (a.max(y) <= b.min(y + h - 1)).then(|| (right, a.max(y), right, b.min(y + h - 1)))
                }
                Side::Top if y == top => {
                    let (a, b) = (left + stretch.from, left + stretch.to - 1);
                    (a.max(x) <= b.min(x + w - 1)).then(|| (a.max(x), top, b.min(x + w - 1), top))
                }
                Side::Bottom if y + h == bottom => {
                    let (a, b) = (left + stretch.from, left + stretch.to - 1);
                    (a.max(x) <= b.min(x + w - 1)).then(|| (a.max(x), bottom, b.min(x + w - 1), bottom))
                }
                _ => None,
            };
            if let Some(line) = line {
                out.push((id, stretch.id, line));
            }
        }
    }
    out
}

// ---- The session ---------------------------------------------------------------------------------------------------------------------

enum Wake {
    Activated(ashpd::desktop::input_capture::Activated),
    Deactivated,
    Disabled,
    Zones,
    Cmd(Cmd),
}

fn run(rx: async_channel::Receiver<Cmd>, handler: Arc<dyn Fn(Captured) + Send + Sync>, shared: Arc<Shared>) {
    loop {
        shared.state.store(0, Ordering::Relaxed);
        match pollster::block_on(session(&rx, &handler, &shared)) {
            Ok(()) => return,
            Err(error) => {
                log::warn!("capturing the mouse and keyboard did not work: {error}");
                *shared.reason.lock().unwrap() = error;
                shared.state.store(2, Ordering::Relaxed);
                // Another try now and then: the person may have changed their mind about the question.
                for _ in 0..30 {
                    std::thread::sleep(Duration::from_secs(1));
                    while let Ok(cmd) = rx.try_recv() {
                        if matches!(cmd, Cmd::Stop) {
                            return;
                        }
                    }
                }
            }
        }
    }
}

fn text<E: std::fmt::Display>(error: E) -> String {
    error.to_string()
}

async fn session(rx: &async_channel::Receiver<Cmd>, handler: &Arc<dyn Fn(Captured) + Send + Sync>, shared: &Arc<Shared>) -> Result<(), String> {
    let capture = InputCapture::new().await.map_err(text)?;
    let caps = Capabilities::Keyboard | Capabilities::Pointer;
    let token = crate::portal::read_token("portal-capture.token");
    let session = match capture.create_session2(Default::default()).await {
        Ok(session) => {
            let started = capture
                .start(
                    &session,
                    None,
                    StartOptions::default().set_capabilities(caps).set_restore_token(token).set_persist_mode(PersistMode::ExplicitlyRevoked),
                )
                .await
                .map_err(text)?
                .response()
                .map_err(text)?;
            if let Some(token) = started.restore_token() {
                crate::portal::write_token("portal-capture.token", token);
            }
            session
        }
        // An older portal has one call for both.
        Err(ashpd::Error::RequiresVersion(..)) => {
            let options = ashpd::desktop::input_capture::CreateSessionOptions::default().set_capabilities(caps);
            capture.create_session(None, options).await.map_err(text)?.0
        }
        Err(error) => return Err(text(error)),
    };

    // What the mouse and keyboard do comes over the connection of libei, which only carries anything while they are handed over.
    let fd = capture.connect_to_eis(&session, ConnectToEISOptions::default()).await.map_err(text)?;
    let stream = std::os::unix::net::UnixStream::from(fd);
    let seen = handler.clone();
    std::thread::Builder::new().name("tandem-capture-ei".into()).spawn(move || listen(stream, seen)).map_err(text)?;

    let mut wakes = futures_util::stream::select_all(vec![
        capture.receive_activated().await.map_err(text)?.map(Wake::Activated).boxed_local(),
        capture.receive_deactivated().await.map_err(text)?.map(|_| Wake::Deactivated).boxed_local(),
        capture.receive_disabled().await.map_err(text)?.map(|_| Wake::Disabled).boxed_local(),
        capture.receive_zones_changed().await.map_err(text)?.map(|_| Wake::Zones).boxed_local(),
        rx.clone().map(Wake::Cmd).boxed_local(),
    ]);

    // Which barrier belongs to which stretch.
    let mut placed = apply(&capture, &session, shared).await?;
    shared.state.store(1, Ordering::Relaxed);
    log::info!("capturing the mouse and keyboard is ready, with {} barrier(s)", placed.len());

    while let Some(wake) = wakes.next().await {
        match wake {
            Wake::Activated(activated) => {
                *shared.activation.lock().unwrap() = activated.activation_id();
                let (left, top, _, _) = *shared.zone.lock().unwrap();
                let (x, y) = activated.cursor_position().map(|(x, y)| (x as i32 - left, y as i32 - top)).unwrap_or((0, 0));
                let id = match activated.barrier_id() {
                    Some(ActivatedBarrier::Barrier(barrier)) => placed.iter().find(|(b, _)| *b == barrier.get()).map(|(_, s)| *s).unwrap_or(0),
                    _ => 0,
                };
                log::info!("the pointer ran into a barrier (stretch {id}) at {x},{y}");
                handler(Captured::Activated { id, x, y });
            }
            Wake::Deactivated => {
                *shared.activation.lock().unwrap() = None;
                log::info!("the desktop took the mouse and keyboard back");
                handler(Captured::Deactivated);
            }
            Wake::Disabled | Wake::Zones | Wake::Cmd(Cmd::Changed) => {
                // Zones that changed or a session that was switched off have no barriers any more.
                placed = apply(&capture, &session, shared).await?;
                log::info!("the barriers were put again: {}", placed.len());
            }
            Wake::Cmd(Cmd::Release { x, y }) => {
                let (left, top, _, _) = *shared.zone.lock().unwrap();
                let activation = shared.activation.lock().unwrap().take();
                let options = ReleaseOptions::default().set_activation_id(activation).set_cursor_position((x + f64::from(left), y + f64::from(top)));
                if let Err(error) = capture.release(&session, options).await {
                    log::warn!("giving the mouse and keyboard back did not work: {error}");
                }
                // The barriers work again once the capture is enabled again.
                let _ = capture.enable(&session, EnableOptions::default()).await;
            }
            Wake::Cmd(Cmd::Stop) => {
                let _ = session.close().await;
                return Ok(());
            }
        }
    }
    Err("the connection to the desktop was closed".into())
}

/// Puts the barriers where the stretches are, and switches the capture on. Gives which barrier is for which stretch.
async fn apply(
    capture: &InputCapture,
    session: &ashpd::desktop::Session<InputCapture>,
    shared: &Shared,
) -> Result<Vec<(u32, u32)>, String> {
    // The desktop only takes barriers while the capture is off ("Session already enabled" otherwise).
    let _ = capture.disable(session, DisableOptions::default()).await;
    let zones = capture.zones(session, GetZonesOptions::default()).await.map_err(text)?.response().map_err(text)?;
    let regions: Vec<(i32, i32, i32, i32)> =
        zones.regions().iter().map(|r| (r.x_offset(), r.y_offset(), r.width() as i32, r.height() as i32)).collect();
    if !regions.is_empty() {
        *shared.zone.lock().unwrap() = (
            regions.iter().map(|r| r.0).min().unwrap_or(0),
            regions.iter().map(|r| r.1).min().unwrap_or(0),
            regions.iter().map(|r| r.0 + r.2).max().unwrap_or(0),
            regions.iter().map(|r| r.1 + r.3).max().unwrap_or(0),
        );
    }
    let stretches = shared.stretches.lock().unwrap().clone();
    let built = barriers_for(&regions, &stretches);
    let list: Vec<Barrier> = built.iter().filter_map(|(id, _, line)| Some(Barrier::new(std::num::NonZeroU32::new(*id)?, *line))).collect();
    let response = match capture.set_pointer_barriers(session, &list, zones.zone_set(), SetPointerBarriersOptions::default()).await {
        Ok(request) => request.response().map(|r| r.failed_barriers().iter().map(|b| b.get()).collect::<Vec<u32>>()).map_err(text),
        Err(error) => Err(text(error)),
    };
    let failed: Vec<u32> = match response {
        Ok(failed) => failed,
        // With nowhere to go (no other computer is there now) the desktop may find an empty list odd; that is no reason to give up.
        Err(error) if list.is_empty() => {
            log::info!("the desktop did not take an empty list of barriers: {error}");
            Vec::new()
        }
        Err(error) => return Err(error),
    };
    if !failed.is_empty() {
        log::info!("the desktop did not take these barriers: {failed:?}");
    }
    capture.enable(session, EnableOptions::default()).await.map_err(text)?;
    Ok(built.into_iter().filter(|(id, _, _)| !failed.contains(id)).map(|(id, stretch, _)| (id, stretch)).collect())
}

// ---- What the mouse and keyboard do ---------------------------------------------------------------------------------------------

/// The Windows key of an evdev key (what libei names the keys by), which is what the rest of the program reads keys as.
pub fn evdev_to_vk(code: u32) -> Option<u16> {
    Some(match code {
        1 => 0x1B,
        2..=10 => 0x31 + (code as u16 - 2),
        11 => 0x30,
        12 => 0xBD,
        13 => 0xBB,
        14 => 0x08,
        15 => 0x09,
        // Q W E R T Y U I O P
        16 => 0x51,
        17 => 0x57,
        18 => 0x45,
        19 => 0x52,
        20 => 0x54,
        21 => 0x59,
        22 => 0x55,
        23 => 0x49,
        24 => 0x4F,
        25 => 0x50,
        26 => 0xDB,
        27 => 0xDD,
        28 => 0x0D,
        29 => 0xA2,
        // A S D F G H J K L
        30 => 0x41,
        31 => 0x53,
        32 => 0x44,
        33 => 0x46,
        34 => 0x47,
        35 => 0x48,
        36 => 0x4A,
        37 => 0x4B,
        38 => 0x4C,
        39 => 0xBA,
        40 => 0xDE,
        41 => 0xC0,
        42 => 0xA0,
        43 => 0xDC,
        // Z X C V B N M
        44 => 0x5A,
        45 => 0x58,
        46 => 0x43,
        47 => 0x56,
        48 => 0x42,
        49 => 0x4E,
        50 => 0x4D,
        51 => 0xBC,
        52 => 0xBE,
        53 => 0xBF,
        54 => 0xA1,
        56 => 0xA4,
        57 => 0x20,
        58 => 0x14,
        // F1 to F10
        59..=68 => 0x70 + (code as u16 - 59),
        87 => 0x7A,
        88 => 0x7B,
        97 => 0xA3,
        100 => 0xA5,
        102 => 0x24,
        103 => 0x26,
        104 => 0x21,
        105 => 0x25,
        106 => 0x27,
        107 => 0x23,
        108 => 0x28,
        109 => 0x22,
        110 => 0x2D,
        111 => 0x2E,
        125 => 0x5B,
        126 => 0x5C,
        _ => return None,
    })
}

/// Reads what the desktop hands over, until the connection ends.
fn listen(stream: std::os::unix::net::UnixStream, handler: Arc<dyn Fn(Captured) + Send + Sync>) {
    let Ok(context) = ei::Context::new(stream) else { return };
    let Ok((_connection, events)) = context.handshake_blocking("tandem", ei::handshake::ContextType::Receiver) else { return };
    // A move comes in fractions, and is passed on in whole pixels with what is left over added to the next.
    let (mut rest_x, mut rest_y) = (0.0f32, 0.0f32);
    // A scroll is told once at the end of its frame, as the wheel steps when there are any and as the smooth movement otherwise.
    let (mut smooth, mut steps) = ((0.0f32, 0.0f32), (0i32, 0i32));
    for event in events {
        let Ok(event) = event else { break };
        match event {
            EiEvent::SeatAdded(added) => {
                added.seat.bind_capabilities(
                    DeviceCapability::Pointer | DeviceCapability::PointerAbsolute | DeviceCapability::Keyboard | DeviceCapability::Scroll | DeviceCapability::Button,
                );
                let _ = context.flush();
            }
            EiEvent::PointerMotion(motion) => {
                let (x, y) = (rest_x + motion.dx, rest_y + motion.dy);
                let (whole_x, whole_y) = (x.trunc(), y.trunc());
                rest_x = x - whole_x;
                rest_y = y - whole_y;
                if whole_x != 0.0 || whole_y != 0.0 {
                    handler(Captured::Seen(Seen::Move { dx: whole_x as i32, dy: whole_y as i32, x: 0, y: 0 }));
                }
            }
            EiEvent::Button(button) => {
                let which = match button.button {
                    0x110 => 0,
                    0x111 => 1,
                    0x112 => 2,
                    _ => continue,
                };
                handler(Captured::Seen(Seen::Button { button: which, down: button.state == ei::button::ButtonState::Press }));
            }
            EiEvent::ScrollDelta(delta) => {
                smooth.0 += delta.dx;
                smooth.1 += delta.dy;
            }
            EiEvent::ScrollDiscrete(discrete) => {
                steps.0 += discrete.discrete_dx;
                steps.1 += discrete.discrete_dy;
            }
            EiEvent::Frame(_) => {
                // A step of the wheel is 120; the program counts twelve to a step and turns the other way (down is positive here).
                let (dx, dy) = if steps != (0, 0) {
                    (-steps.0 / 10, -steps.1 / 10)
                } else {
                    // Smooth movement is in pixels, of which about fifteen are a step.
                    ((-smooth.0 * 0.8) as i32, (-smooth.1 * 0.8) as i32)
                };
                smooth = (0.0, 0.0);
                steps = (0, 0);
                if dx != 0 || dy != 0 {
                    handler(Captured::Seen(Seen::Scroll { dx, dy }));
                }
            }
            EiEvent::KeyboardKey(key) => {
                if let Some(vk) = evdev_to_vk(key.key) {
                    handler(Captured::Seen(Seen::Key { vk, down: key.state == ei::keyboard::KeyState::Press }));
                }
            }
            EiEvent::Disconnected(_) => break,
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_stretch_on_the_right_edge_runs_along_the_screens_that_touch_it() {
        // Two screens side by side, the right one a little lower.
        let regions = [(0, 0, 1000, 600), (1000, 100, 800, 500)];
        let stretch = Stretch { id: 1, side: Side::Right, from: 50, to: 400 };
        let barriers = barriers_for(&regions, &[stretch]);
        // Only the right screen is on that edge, and it starts at 100, so the stretch begins there.
        assert_eq!(barriers.len(), 1);
        assert_eq!(barriers[0].2, (1800, 100, 1800, 399));
        // The first screen is not on the right edge of the whole: nothing goes there.
        assert_eq!(barriers_for(&regions, &[Stretch { id: 2, side: Side::Left, from: 0, to: 600 }]).len(), 1);
    }

    #[test]
    fn a_stretch_that_misses_the_screen_makes_no_barrier() {
        let regions = [(0, 0, 1000, 600)];
        assert!(barriers_for(&regions, &[Stretch { id: 1, side: Side::Top, from: 2000, to: 2400 }]).is_empty());
        assert!(barriers_for(&[], &[Stretch { id: 1, side: Side::Top, from: 0, to: 100 }]).is_empty());
    }

    #[test]
    fn the_top_and_bottom_edges_are_along_x() {
        let regions = [(0, 0, 1000, 600)];
        let top = barriers_for(&regions, &[Stretch { id: 1, side: Side::Top, from: 100, to: 300 }]);
        assert_eq!(top[0].2, (100, 0, 299, 0));
        let bottom = barriers_for(&regions, &[Stretch { id: 1, side: Side::Bottom, from: 0, to: 1000 }]);
        assert_eq!(bottom[0].2, (0, 600, 999, 600));
        // A barrier id says which stretch it is for.
        assert_eq!(bottom[0].0, PER_STRETCH + 1);
    }

    #[test]
    fn keys_are_the_ones_of_windows() {
        assert_eq!(evdev_to_vk(30), Some(0x41)); // a
        assert_eq!(evdev_to_vk(1), Some(0x1B)); // escape
        assert_eq!(evdev_to_vk(2), Some(0x31)); // 1
        assert_eq!(evdev_to_vk(11), Some(0x30)); // 0
        assert_eq!(evdev_to_vk(59), Some(0x70)); // F1
        assert_eq!(evdev_to_vk(68), Some(0x79)); // F10
        assert_eq!(evdev_to_vk(105), Some(0x25)); // left arrow
        assert_eq!(evdev_to_vk(999), None);
    }
}
