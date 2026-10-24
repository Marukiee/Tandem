//! Wayland: pressing keys, moving the pointer and looking at the screen all go through the portals of the desktop, because a window may do
//! none of it by itself there. The desktop asks the person once ("Allow remote interaction?", "Share this screen?") and, when the answer
//! is kept (the token that comes back is written to a file here), never asks again.
//!
//! Two things come out of it:
//! - [`Input`]: a session with the keyboard and the pointer. Pointer movements are relative, so where the pointer is has to be counted by
//!   the caller; [`Input::slam`] puts it against the edge of the screen, from where a count is exact.
//! - [`WaylandGrabber`]: pictures of the screen. The portal hands out a PipeWire connection; GStreamer (`pipewiresrc`), which every desktop
//!   with PipeWire has, turns it into raw pictures at the size that is wanted, and they are read from a pipe. That keeps the PipeWire
//!   libraries out of this program, so it does not care which version of them the system has.

use std::io::Read;
use std::os::fd::AsRawFd;
use std::os::unix::process::CommandExt;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use ashpd::desktop::{CreateSessionOptions, PersistMode, Session};
use ashpd::desktop::remote_desktop::{
    Axis, DeviceType, KeyState, NotifyKeyboardKeycodeOptions, NotifyKeyboardKeysymOptions, NotifyPointerAxisDiscreteOptions,
    NotifyPointerAxisOptions, NotifyPointerButtonOptions, NotifyPointerMotionAbsoluteOptions, NotifyPointerMotionOptions, RemoteDesktop,
    SelectDevicesOptions, StartOptions,
};
use ashpd::desktop::screencast::{CursorMode, OpenPipeWireRemoteOptions, Screencast, SelectSourcesOptions, SourceType, StartCastOptions};
use enumflags2::BitFlags;

use crate::GrabOptions;

fn text<E: std::fmt::Display>(error: E) -> String {
    error.to_string()
}

/// Where the tokens that make the desktop remember its answer are kept.
static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

pub fn set_data_dir(dir: PathBuf) {
    let _ = DATA_DIR.set(dir);
}

/// How this process was started, as the desktop sees it. What a person allowed is kept by the portals per application, and the
/// application is named after the service or scope the process runs in: starting at login, from the menu and from a terminal are three
/// different ones. A token from one is no good to another, which made the question come back each time the way of starting changed,
/// so every way keeps its own token.
fn launch_identity() -> String {
    identity_of(&std::fs::read_to_string("/proc/self/cgroup").unwrap_or_default())
}

pub(crate) fn identity_of(cgroup: &str) -> String {
    let last = cgroup.lines().filter(|l| !l.trim().is_empty()).last().and_then(|l| l.rsplit('/').next()).unwrap_or("");
    let stem = last.trim_end_matches(".scope").trim_end_matches(".service");
    // The numbers in it are a process number, different every time, and not the application.
    let kept: String = stem.chars().filter(|c| c.is_ascii_alphabetic() || *c == '-' || *c == '_').take(60).collect();
    let kept = kept.trim_matches('-').to_string();
    if kept.is_empty() { "default".to_string() } else { kept }
}

fn scoped(name: &str) -> String {
    match name.rsplit_once('.') {
        Some((stem, extension)) => format!("{stem}.{}.{extension}", launch_identity()),
        None => format!("{name}.{}", launch_identity()),
    }
}

fn token_file(name: &str) -> Option<PathBuf> {
    DATA_DIR.get().map(|dir| dir.join(name))
}

/// The token of this way of starting; when there is none yet, the one from before they were kept apart.
pub(crate) fn read_token(name: &str) -> Option<String> {
    for file in [scoped(name), name.to_string()] {
        let Some(path) = token_file(&file) else { continue };
        if let Some(token) = std::fs::read_to_string(path).ok().map(|t| t.trim().to_string()).filter(|t| !t.is_empty()) {
            return Some(token);
        }
    }
    None
}

pub(crate) fn write_token(name: &str, token: &str) {
    if let Some(path) = token_file(&scoped(name)) {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(path, token);
    }
}

/// Whether this is a Wayland session.
pub fn is_wayland() -> bool {
    std::env::var("XDG_SESSION_TYPE").map(|s| s.eq_ignore_ascii_case("wayland")).unwrap_or(false)
        || (std::env::var_os("XDG_SESSION_TYPE").is_none() && std::env::var_os("WAYLAND_DISPLAY").is_some())
}

pub(crate) fn portal_property(interface: &str, property: &str) -> Option<u32> {
    use zbus::blocking::Connection;
    use zbus::blocking::proxy::Builder;
    use zbus::proxy::CacheProperties;
    let connection = Connection::session().ok()?;
    let proxy = Builder::<zbus::blocking::Proxy>::new(&connection)
        .destination("org.freedesktop.portal.Desktop")
        .ok()?
        .path("/org/freedesktop/portal/desktop")
        .ok()?
        .interface(interface.to_string())
        .ok()?
        .cache_properties(CacheProperties::No)
        .build()
        .ok()?;
    proxy.get_property::<u32>(property).ok()
}

/// Whether the desktop lets this program press keys and move the pointer (keyboard and pointer both).
pub fn input_available() -> bool {
    static ANSWER: OnceLock<bool> = OnceLock::new();
    *ANSWER.get_or_init(|| portal_property("org.freedesktop.portal.RemoteDesktop", "AvailableDeviceTypes").is_some_and(|types| types & 0b11 == 0b11))
}

/// Whether the desktop can make a monitor for another computer to use as a second screen (a virtual source of the screen cast portal).
pub fn virtual_screen_available() -> bool {
    static ANSWER: OnceLock<bool> = OnceLock::new();
    *ANSWER.get_or_init(|| {
        screen_available() && portal_property("org.freedesktop.portal.ScreenCast", "AvailableSourceTypes").is_some_and(|types| types & 4 != 0)
    })
}

/// Whether the screen can be shown here: the desktop offers monitors to share, and GStreamer can read them.
pub fn screen_available() -> bool {
    static ANSWER: OnceLock<bool> = OnceLock::new();
    *ANSWER.get_or_init(|| {
        let offered = portal_property("org.freedesktop.portal.ScreenCast", "AvailableSourceTypes").is_some_and(|types| types & 1 != 0);
        offered
            && {
                let mut inspect = Command::new("gst-inspect-1.0");
                crate::system_env(&mut inspect);
                inspect.arg("pipewiresrc").stdout(Stdio::null()).stderr(Stdio::null()).status().is_ok_and(|s| s.success())
            }
    })
}

// ---- The size of the screen ---------------------------------------------------------------------------------------------------

/// The size of the area the pointer moves in, which on Wayland is the layout of the screens in logical pixels. GNOME says it
/// (a scaled screen is smaller than its mode), and nothing else does in a way that is the same everywhere; `None` when it is not asked.
pub fn logical_screen() -> Option<(i32, i32)> {
    // Asked for often (every few seconds, from several places) and a screen does not change that fast: the answer is kept a moment, so
    // this does not open a new connection to the bus every time.
    static KEPT: Mutex<Option<(std::time::Instant, Option<(i32, i32)>)>> = Mutex::new(None);
    let mut kept = KEPT.lock().unwrap();
    if let Some((at, answer)) = *kept {
        if at.elapsed() < std::time::Duration::from_secs(5) {
            return answer;
        }
    }
    let answer = ask_logical_screen();
    *kept = Some((std::time::Instant::now(), answer));
    answer
}

fn ask_logical_screen() -> Option<(i32, i32)> {
    use zbus::blocking::Connection;
    use zbus::zvariant::{OwnedValue, Value};
    type Mode = (String, i32, i32, f64, f64, Vec<f64>, std::collections::HashMap<String, OwnedValue>);
    type Monitor = ((String, String, String, String), Vec<Mode>, std::collections::HashMap<String, OwnedValue>);
    type Logical = (i32, i32, f64, u32, bool, Vec<(String, String, String, String)>, std::collections::HashMap<String, OwnedValue>);
    let connection = Connection::session().ok()?;
    let reply = connection
        .call_method(Some("org.gnome.Mutter.DisplayConfig"), "/org/gnome/Mutter/DisplayConfig", Some("org.gnome.Mutter.DisplayConfig"), "GetCurrentState", &())
        .ok()?;
    let (_serial, monitors, logicals, props): (u32, Vec<Monitor>, Vec<Logical>, std::collections::HashMap<String, OwnedValue>) =
        reply.body().deserialize().ok()?;
    // 1: the sizes of the logical screens are the modes divided by the scale; 2: they are the modes as they are.
    let divide = props.get("layout-mode").and_then(|v| u32::try_from(Value::from(v.try_clone().ok()?)).ok()).unwrap_or(1) == 1;
    let (mut width, mut height) = (0.0f64, 0.0f64);
    for (x, y, scale, _, _, members, _) in &logicals {
        let Some(connector) = members.first().map(|m| &m.0) else { continue };
        let Some((_, modes, _)) = monitors.iter().find(|m| &m.0.0 == connector) else { continue };
        let current = modes.iter().find(|m| m.6.get("is-current").is_some_and(|v| bool::try_from(Value::from(v.try_clone().unwrap())).unwrap_or(false)));
        let Some(mode) = current else { continue };
        let scale = if divide { scale.max(0.1) } else { 1.0 };
        width = width.max(f64::from(*x) + f64::from(mode.1) / scale);
        height = height.max(f64::from(*y) + f64::from(mode.2) / scale);
    }
    (width >= 1.0 && height >= 1.0).then_some((width.round() as i32, height.round() as i32))
}

// ---- Keys and the pointer -----------------------------------------------------------------------------------------------------

/// A session with the keyboard and the pointer of the desktop.
pub struct Input {
    remote: RemoteDesktop,
    session: Session<RemoteDesktop>,
    /// Cleared when the desktop refuses a key or a move: the session was closed (the desktop does that, for instance after the screen was
    /// locked), and nothing sent to it does anything any more. The caller starts a new one.
    healthy: AtomicBool,
}

impl Input {
    /// Starts the session. The desktop asks the person the first time (this waits for the answer) and then remembers it.
    pub fn start() -> Result<Input, String> {
        pollster::block_on(async {
            let remote = RemoteDesktop::new().await.map_err(text)?;
            let session = remote.create_session(CreateSessionOptions::default()).await.map_err(text)?;
            let token = read_token("portal-input.token");
            remote
                .select_devices(
                    &session,
                    SelectDevicesOptions::default()
                        .set_devices(DeviceType::Keyboard | DeviceType::Pointer)
                        .set_persist_mode(PersistMode::ExplicitlyRevoked)
                        .set_restore_token(token.as_deref()),
                )
                .await
                .map_err(text)?
                .response()
                .map_err(text)?;
            let started = remote.start(&session, None, StartOptions::default()).await.map_err(text)?.response().map_err(text)?;
            if !started.devices().contains(DeviceType::Pointer) {
                return Err("the pointer was not allowed".into());
            }
            if let Some(token) = started.restore_token() {
                write_token("portal-input.token", token);
            }
            Ok(Input { remote, session, healthy: AtomicBool::new(true) })
        })
    }

    /// Whether the session still takes what is sent to it.
    pub fn healthy(&self) -> bool {
        self.healthy.load(Ordering::Relaxed)
    }

    fn note<T, E: std::fmt::Display>(&self, result: Result<T, E>) -> bool {
        match result {
            Ok(_) => true,
            Err(error) => {
                if self.healthy.swap(false, Ordering::Relaxed) {
                    log::warn!("the desktop stopped taking keys and pointer moves: {error}");
                }
                false
            }
        }
    }

    /// The pointer moves by this much (logical pixels).
    pub fn motion(&self, dx: f64, dy: f64) -> bool {
        self.note(pollster::block_on(self.remote.notify_pointer_motion(&self.session, dx, dy, NotifyPointerMotionOptions::default())))
    }

    /// A button of the pointer by its Linux code (0x110 left, 0x111 right, 0x112 middle).
    pub fn button(&self, code: i32, down: bool) -> bool {
        let state = if down { KeyState::Pressed } else { KeyState::Released };
        self.note(pollster::block_on(self.remote.notify_pointer_button(&self.session, code, state, NotifyPointerButtonOptions::default())))
    }

    /// Notches of the wheel: positive goes down (or right).
    pub fn wheel(&self, vertical: bool, steps: i32) -> bool {
        let axis = if vertical { Axis::Vertical } else { Axis::Horizontal };
        self.note(pollster::block_on(self.remote.notify_pointer_axis_discrete(&self.session, axis, steps, NotifyPointerAxisDiscreteOptions::default())))
    }

    /// A key by its X11 keysym, which names what is printed on it, whatever the layout.
    pub fn key(&self, keysym: i32, down: bool) -> bool {
        let state = if down { KeyState::Pressed } else { KeyState::Released };
        self.note(pollster::block_on(self.remote.notify_keyboard_keysym(&self.session, keysym, state, NotifyKeyboardKeysymOptions::default())))
    }

    /// Puts the pointer against the edges given: a movement that is far more than the screen is, which the desktop stops at the edge. After
    /// this the place of the pointer is known on that axis, which a count of movements needs.
    pub fn slam(&self, left: bool, right: bool, top: bool, bottom: bool) -> bool {
        const FAR: f64 = 20_000.0;
        let dx = if left { -FAR } else if right { FAR } else { 0.0 };
        let dy = if top { -FAR } else if bottom { FAR } else { 0.0 };
        dx == 0.0 && dy == 0.0 || self.motion(dx, dy)
    }
}

impl Drop for Input {
    fn drop(&mut self) {
        let _ = pollster::block_on(self.session.close());
    }
}

// ---- The screen -------------------------------------------------------------------------------------------------------------------

/// The screen of a Wayland desktop as raw pictures: B, G, R, unused, line after line, or the planes of I420 when that is what is wanted.
pub struct WaylandGrabber {
    size: (u32, u32),
    /// The newest picture, shared and not copied: a viewer takes it as often as it likes and nothing is copied for it.
    latest: Arc<Mutex<Option<Arc<Vec<u8>>>>>,
    /// Whether the pictures are I420 (the planes of video, a byte and a half to the pixel) and not B, G, R, unused.
    i420: bool,
    alive: Arc<AtomicBool>,
    child: Child,
    // Kept for as long as the pictures come: closing the session ends the sharing.
    held: Held,
}

/// The portal session that the pictures come from.
enum Held {
    /// Only the screen.
    Cast { _cast: Screencast, session: Session<Screencast> },
    /// The screen and the keyboard and pointer, in one session: what the viewer does goes to the monitor that was made for it.
    Both { _cast: Screencast, input: Arc<StreamInput> },
}

/// What a start of the portal gives: the session, which stream the pictures are on, how big it says it is, and the connection to read it.
struct Started {
    held: Held,
    node: u32,
    source: (u32, u32),
    remote: std::os::fd::OwnedFd,
}

/// The keyboard and the pointer of the desktop, aimed at the monitor that was made for a viewer. It is the same session as the pictures
/// come from, which is what lets the desktop take a place on that monitor (a place on a stream) and not only a move by so much.
pub struct StreamInput {
    remote: RemoteDesktop,
    session: Session<RemoteDesktop>,
    /// The PipeWire node of the monitor's stream, which is how the desktop is told which monitor a place is on.
    stream: u32,
    /// The size of the monitor in the coordinates of the desktop, which is what a place on it is counted in.
    size: (f64, f64),
    healthy: AtomicBool,
}

impl StreamInput {
    fn note<T, E: std::fmt::Display>(&self, result: Result<T, E>) -> bool {
        match result {
            Ok(_) => true,
            Err(error) => {
                if self.healthy.swap(false, Ordering::Relaxed) {
                    log::warn!("the desktop stopped taking input for the second screen: {error}");
                }
                false
            }
        }
    }

    /// The pointer goes to this place on the monitor, as fractions of it (0.0 to 1.0, the top left corner is the origin).
    pub fn pointer_to(&self, x: f64, y: f64) -> bool {
        let (x, y) = (x.clamp(0.0, 1.0) * self.size.0, y.clamp(0.0, 1.0) * self.size.1);
        self.note(pollster::block_on(self.remote.notify_pointer_motion_absolute(
            &self.session,
            self.stream,
            x,
            y,
            NotifyPointerMotionAbsoluteOptions::default(),
        )))
    }

    /// The pointer moves by this much (logical pixels), for a viewer that uses its finger as a trackpad.
    pub fn motion(&self, dx: f64, dy: f64) -> bool {
        self.note(pollster::block_on(self.remote.notify_pointer_motion(&self.session, dx, dy, NotifyPointerMotionOptions::default())))
    }

    /// A button of the pointer by its Linux code (0x110 left, 0x111 right, 0x112 middle).
    pub fn button(&self, code: i32, down: bool) -> bool {
        let state = if down { KeyState::Pressed } else { KeyState::Released };
        self.note(pollster::block_on(self.remote.notify_pointer_button(&self.session, code, state, NotifyPointerButtonOptions::default())))
    }

    /// The wheel, smoothly: positive goes down (or right), in pixels. `finish` says that this is the last of a stroke.
    pub fn scroll(&self, dx: f64, dy: f64, finish: bool) -> bool {
        self.note(pollster::block_on(self.remote.notify_pointer_axis(&self.session, dx, dy, NotifyPointerAxisOptions::default().set_finish(finish))))
    }

    /// A key by its evdev code.
    pub fn key(&self, code: i32, down: bool) -> bool {
        let state = if down { KeyState::Pressed } else { KeyState::Released };
        self.note(pollster::block_on(self.remote.notify_keyboard_keycode(&self.session, code, state, NotifyKeyboardKeycodeOptions::default())))
    }

    /// A key by its X11 keysym, which names what is printed on it, whatever the layout.
    pub fn keysym(&self, keysym: i32, down: bool) -> bool {
        let state = if down { KeyState::Pressed } else { KeyState::Released };
        self.note(pollster::block_on(self.remote.notify_keyboard_keysym(&self.session, keysym, state, NotifyKeyboardKeysymOptions::default())))
    }

    /// Whether the session still takes what is sent to it.
    pub fn healthy(&self) -> bool {
        self.healthy.load(Ordering::Relaxed)
    }
}

/// Only the screen: a monitor of the desktop, or one that the desktop makes (`virtual_size`), looked at and nothing more.
async fn start_plain(options: &GrabOptions) -> Result<Started, String> {
    let made = options.virtual_size;
    let cast = Screencast::new().await.map_err(text)?;
    let session = cast.create_session(CreateSessionOptions::default()).await.map_err(text)?;
    // A monitor made for a viewer has an answer of its own to be remembered, apart from the one for the screen that is there.
    let token_file = if made.is_some() { "portal-display.token" } else { "portal-screen.token" };
    let token = read_token(token_file);
    cast.select_sources(
        &session,
        SelectSourcesOptions::default()
            .set_cursor_mode(if options.cursor { CursorMode::Embedded } else { CursorMode::Hidden })
            .set_sources(BitFlags::from_flag(if made.is_some() { SourceType::Virtual } else { SourceType::Monitor }))
            .set_multiple(false)
            .set_persist_mode(PersistMode::ExplicitlyRevoked)
            .set_restore_token(token.as_deref()),
    )
    .await
    .map_err(text)?
    .response()
    .map_err(text)?;
    let streams = cast.start(&session, None, StartCastOptions::default()).await.map_err(text)?.response().map_err(text)?;
    if let Some(token) = streams.restore_token() {
        write_token(token_file, token);
    }
    let stream = streams.streams().first().ok_or("no screen was chosen")?;
    let node = stream.pipe_wire_node_id();
    let source = stream.size().map(|(w, h)| (w.max(2) as u32, h.max(2) as u32)).unwrap_or((1920, 1080));
    let remote = cast.open_pipe_wire_remote(&session, OpenPipeWireRemoteOptions::default()).await.map_err(text)?;
    Ok(Started { held: Held::Cast { _cast: cast, session }, node, source, remote })
}

/// The screen (the one that is there, or a monitor made for the viewer) in the same session as the keyboard and the pointer, so that a touch
/// on the viewer is a click on that screen, at the place that was touched. The desktop asks once for both (and then remembers it).
async fn start_with_input(options: &GrabOptions) -> Result<Started, String> {
    let made = options.virtual_size.is_some();
    let remote = RemoteDesktop::new().await.map_err(text)?;
    let cast = Screencast::new().await.map_err(text)?;
    let session = remote.create_session(CreateSessionOptions::default()).await.map_err(text)?;
    let token_file = if made { "portal-display-input.token" } else { "portal-screen-input.token" };
    let token = read_token(token_file);
    // In a session that has both, the answer to be remembered is asked for with the devices, and covers the monitor too.
    remote
        .select_devices(
            &session,
            SelectDevicesOptions::default()
                .set_devices(DeviceType::Keyboard | DeviceType::Pointer)
                .set_persist_mode(PersistMode::ExplicitlyRevoked)
                .set_restore_token(token.as_deref()),
        )
        .await
        .map_err(text)?
        .response()
        .map_err(text)?;
    cast.select_sources(
        &session,
        SelectSourcesOptions::default()
            .set_cursor_mode(if options.cursor { CursorMode::Embedded } else { CursorMode::Hidden })
            .set_sources(BitFlags::from_flag(if made { SourceType::Virtual } else { SourceType::Monitor }))
            .set_multiple(false),
    )
    .await
    .map_err(text)?
    .response()
    .map_err(text)?;
    let started = remote.start(&session, None, StartOptions::default()).await.map_err(text)?.response().map_err(text)?;
    if let Some(token) = started.restore_token() {
        write_token(token_file, token);
    }
    if !started.devices().contains(DeviceType::Pointer) {
        return Err("the pointer was not allowed".into());
    }
    let stream = started.streams().first().ok_or("no screen was chosen")?;
    let node = stream.pipe_wire_node_id();
    let source = stream.size().map(|(w, h)| (w.max(2) as u32, h.max(2) as u32)).unwrap_or((1920, 1080));
    // What a place of the pointer is counted in, next to what GNOME says about the screen, to find out why a pointer lands elsewhere.
    let screens: Vec<String> = crate::monitors().iter().map(|m| format!("{} at {},{} {}x{} scale {}", m.connector, m.x, m.y, m.pixels.0, m.pixels.1, m.scale)).collect();
    log::info!(
        "screen with input: stream {}x{} at {:?} (said {:?}), places counted in that size; the screens: {}",
        source.0, source.1, stream.position(), stream.size(), screens.join("; ")
    );
    let fd = cast.open_pipe_wire_remote(&session, OpenPipeWireRemoteOptions::default()).await.map_err(text)?;
    let input = Arc::new(StreamInput { remote, session, stream: node, size: (f64::from(source.0), f64::from(source.1)), healthy: AtomicBool::new(true) });
    Ok(Started { held: Held::Both { _cast: cast, input }, node, source, remote: fd })
}

/// The largest even size that fits in what is wanted, without growing the screen.
fn fit(source: (u32, u32), max: (u32, u32)) -> (u32, u32) {
    let (sw, sh) = (source.0.max(2) as f64, source.1.max(2) as f64);
    let max_w = if max.0 == 0 { sw } else { max.0 as f64 };
    let max_h = if max.1 == 0 { sh } else { max.1 as f64 };
    let scale = (max_w / sw).min(max_h / sh).min(1.0);
    packed((sw * scale) as u32, (sh * scale) as u32)
}

/// A size whose pictures GStreamer hands over without padding, so that one picture is exactly width times height times one and a half
/// bytes in I420. It pads every row of a plane to a multiple of 4 bytes: a width that is not a multiple of 8 made the reader fall out of
/// step with the pictures (stripes and wrong colours), which happened with a monitor made in the size of a viewer, such as 2382 wide.
fn packed(width: u32, height: u32) -> (u32, u32) {
    ((width.max(16)) & !7, (height.max(16)) & !1)
}

impl WaylandGrabber {
    /// Asks for a monitor (the desktop shows a window for it the first time) and starts reading it. `max` is the size that the viewer
    /// wants at most; a bigger screen is made smaller on the way, which costs far less than doing it afterwards.
    pub fn start(options: GrabOptions) -> Result<WaylandGrabber, String> {
        let max = options.max;
        let made = options.virtual_size;
        pollster::block_on(async {
            // With input the monitor and the keyboard and pointer are one session. A desktop that does not do that for a monitor made on
            // the spot still gives the monitor, to look at.
            let started = if options.input {
                match start_with_input(&options).await {
                    Ok(started) => started,
                    Err(error) => {
                        log::warn!("a second screen that can be used could not be made, it is made to look at only: {error}");
                        start_plain(&options).await?
                    }
                }
            } else {
                start_plain(&options).await?
            };
            let Started { held, node, source, remote } = started;
            // A monitor made for the viewer is the size that is asked of the stream: the desktop gives it that shape.
            let size = made.map(|(w, h)| packed(w, h)).unwrap_or_else(|| fit(source, max));

            let raw = remote.as_raw_fd();
            let mut command = Command::new("gst-launch-1.0");
            // The libraries and plugin paths of an AppImage must not leak into GStreamer of the system.
            crate::system_env(&mut command);
            // Fewer pictures first (a picture that is thrown away should cost nothing), then the size, then the colours: the order in which
            // the work is the least.
            let format = if options.i420 { "I420" } else { "BGRx" };
            command.args(["-q", "pipewiresrc", "fd=3"]).arg(format!("path={node}")).args(["always-copy=true", "do-timestamp=true", "!"]);
            if options.fps > 0 {
                command.args(["videorate", "drop-only=true"]).arg(format!("max-rate={}", options.fps)).arg("!");
            }
            if made.is_some() {
                // Asked of the stream in the size of the monitor: the desktop makes the monitor that size, and nothing is scaled after.
                command.arg(format!("video/x-raw,width={},height={}", size.0, size.1)).arg("!");
            } else {
                command.args(["videoscale", "!"]).arg(format!("video/x-raw,width={},height={}", size.0, size.1)).arg("!");
            }
            command
                .args(["videoconvert", "!"])
                .arg(format!("video/x-raw,format={format},width={},height={}", size.0, size.1))
                .args(["!", "fdsink", "fd=1", "sync=false"])
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped());
            // The connection of the portal is handed to the child as its third file: the number it is given in `fd=3` above.
            unsafe {
                command.pre_exec(move || {
                    if libc::dup2(raw, 3) < 0 {
                        return Err(std::io::Error::last_os_error());
                    }
                    // A duplicate that is kept over exec: the copy that dup2 makes is, but it is the same number when raw already was 3.
                    libc::fcntl(3, libc::F_SETFD, 0);
                    Ok(())
                });
            }
            let mut child = command.spawn().map_err(|e| format!("GStreamer could not start: {e}"))?;
            drop(remote);
            let mut pipe = child.stdout.take().ok_or("no pipe to GStreamer")?;
            // What GStreamer says when it cannot do it (a plugin that is missing, say) goes in the log: without it a screen that never
            // appears gives nothing to go on.
            if let Some(errors) = child.stderr.take() {
                std::thread::Builder::new()
                    .name("tandem-wayland-screen-log".into())
                    .spawn(move || {
                        use std::io::BufRead;
                        for line in std::io::BufReader::new(errors).lines().map_while(Result::ok).take(20) {
                            log::warn!("gstreamer: {line}");
                        }
                    })
                    .ok();
            }

            let latest: Arc<Mutex<Option<Arc<Vec<u8>>>>> = Arc::new(Mutex::new(None));
            let alive = Arc::new(AtomicBool::new(true));
            let (store, flag) = (latest.clone(), alive.clone());
            let length = if options.i420 { size.0 as usize * size.1 as usize * 3 / 2 } else { size.0 as usize * size.1 as usize * 4 };
            std::thread::Builder::new()
                .name("tandem-wayland-screen".into())
                .spawn(move || {
                    loop {
                        let mut frame = vec![0u8; length];
                        if pipe.read_exact(&mut frame).is_err() {
                            break;
                        }
                        *store.lock().unwrap() = Some(Arc::new(frame));
                    }
                    flag.store(false, Ordering::Relaxed);
                })
                .map_err(text)?;
            Ok(WaylandGrabber { size, latest, i420: options.i420, alive, child, held })
        })
    }

    pub fn size(&self) -> (u32, u32) {
        self.size
    }

    /// The newest picture, shared. The same one again (the same `Arc`) while the screen has not changed.
    pub fn grab_shared(&self) -> Option<(u32, u32, Arc<Vec<u8>>)> {
        if !self.alive.load(Ordering::Relaxed) {
            return None;
        }
        self.latest.lock().unwrap().clone().map(|pixels| (self.size.0, self.size.1, pixels))
    }

    /// The newest picture as a copy of B, G, R, unused (only for a grabber that was not asked for I420).
    pub fn grab(&self) -> Option<(u32, u32, Vec<u8>)> {
        if self.i420 {
            return None;
        }
        self.grab_shared().map(|(w, h, pixels)| (w, h, pixels.as_ref().clone()))
    }

    pub fn is_i420(&self) -> bool {
        self.i420
    }

    /// The keyboard and the pointer aimed at the monitor that these pictures are of, when it was made together with them.
    pub fn stream_input(&self) -> Option<Arc<StreamInput>> {
        match &self.held {
            Held::Both { input, .. } => Some(input.clone()),
            Held::Cast { .. } => None,
        }
    }
}

impl Drop for WaylandGrabber {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        match &self.held {
            Held::Cast { session, .. } => {
                let _ = pollster::block_on(session.close());
            }
            Held::Both { input, .. } => {
                let _ = pollster::block_on(input.session.close());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_big_screen_is_made_smaller_to_fit_and_stays_even() {
        assert_eq!(fit((2560, 1600), (1920, 1080)), (1728, 1080));
        assert_eq!(fit((1920, 1200), (0, 0)), (1920, 1200));
        // Never larger than the screen.
        assert_eq!(fit((1280, 720), (3000, 3000)), (1280, 720));
        let (w, h) = fit((1367, 769), (1000, 1000));
        assert!(w % 8 == 0 && h % 2 == 0);
    }

    #[test]
    fn a_picture_in_i420_has_no_padding_at_any_size_that_is_asked() {
        // How GStreamer lays out I420 (video-info.c): rows of 4 bytes, planes after an even number of rows.
        fn gstreamer_size(w: u32, h: u32) -> u32 {
            let up4 = |n: u32| (n + 3) & !3;
            let up2 = |n: u32| (n + 1) & !1;
            let luma = up4(w) * up2(h);
            let chroma = up4(up2(w) / 2) * (up2(h) / 2);
            luma + 2 * chroma
        }
        for (w, h) in [(2382, 1546), (1170, 540), (2340, 1080), (1512, 982), (1366, 768), (641, 361)] {
            let (pw, ph) = packed(w, h);
            assert_eq!(gstreamer_size(pw, ph), pw * ph * 3 / 2, "{w} by {h} became {pw} by {ph}");
        }
    }

    #[test]
    fn the_way_of_starting_is_told_from_the_service_without_its_process_number() {
        assert_eq!(identity_of("0::/user.slice/user-1000.slice/user@1000.service/app.slice/tandem-app.service\n"), "tandem-app");
        assert_eq!(
            identity_of("0::/user.slice/user-1000.slice/user@1000.service/app.slice/app-gnome-Tandem-4821.scope\n"),
            identity_of("0::/user.slice/user-1000.slice/user@1000.service/app.slice/app-gnome-Tandem-99.scope\n")
        );
        assert_eq!(identity_of(""), "default");
        assert_ne!(identity_of("0::/a/app-gnome-Tandem-1.scope"), identity_of("0::/a/app-gnome-tandem-1.scope"));
    }

    #[test]
    fn a_token_file_is_read_back_without_its_line_end() {
        let dir = std::env::temp_dir().join(format!("tandem-portal-{}", std::process::id()));
        set_data_dir(dir.clone());
        write_token("t.token", "abc123\n");
        assert_eq!(read_token("t.token").as_deref(), Some("abc123"));
        assert_eq!(read_token("missing.token"), None);
        let _ = std::fs::remove_dir_all(dir);
    }
}

// ---- Where a monitor made for a viewer goes -------------------------------------------------------------------------------------------

type MonitorSpec = (String, String, String, String);
type DisplayMode = (String, i32, i32, f64, f64, Vec<f64>, std::collections::HashMap<String, zbus::zvariant::OwnedValue>);
type DisplayMonitor = (MonitorSpec, Vec<DisplayMode>, std::collections::HashMap<String, zbus::zvariant::OwnedValue>);
type LogicalMonitor = (i32, i32, f64, u32, bool, Vec<MonitorSpec>, std::collections::HashMap<String, zbus::zvariant::OwnedValue>);
type DisplayState = (u32, Vec<DisplayMonitor>, Vec<LogicalMonitor>, std::collections::HashMap<String, zbus::zvariant::OwnedValue>);

fn display_state(connection: &zbus::blocking::Connection) -> Result<DisplayState, String> {
    let reply = connection
        .call_method(Some("org.gnome.Mutter.DisplayConfig"), "/org/gnome/Mutter/DisplayConfig", Some("org.gnome.Mutter.DisplayConfig"), "GetCurrentState", &())
        .map_err(text)?;
    reply.body().deserialize::<DisplayState>().map_err(text)
}

/// The screens as GNOME has them: each logical monitor with the mode of its first monitor and its real scale (1.25, 1.5, which the
/// window toolkit rounds up to 2). Empty on another desktop.
pub fn monitors() -> Vec<crate::Monitor> {
    let Ok(connection) = zbus::blocking::Connection::session() else { return Vec::new() };
    let Ok((_, monitors, logical, props)) = display_state(&connection) else { return Vec::new() };
    // 1: the layout is in logical pixels (Wayland); 2: in pixels (X11).
    let logical_layout = props.get("layout-mode").and_then(|v| v.downcast_ref::<u32>().ok()).unwrap_or(1) == 1;
    logical
        .iter()
        .filter_map(|(x, y, scale, transform, primary, members, _)| {
            let connector = &members.first()?.0;
            let (_, modes, _) = monitors.iter().find(|m| &m.0 .0 == connector)?;
            let mode = modes.iter().find(|mode| mode.6.get("is-current").and_then(|v| v.downcast_ref::<bool>().ok()).unwrap_or(false))?;
            let (w, h) = (u32::try_from(mode.1).ok()?, u32::try_from(mode.2).ok()?);
            // A screen turned a quarter has its mode the other way round.
            let pixels = if transform % 2 == 1 { (h, w) } else { (w, h) };
            Some(crate::Monitor { connector: connector.clone(), x: *x, y: *y, pixels, scale: *scale, primary: *primary, logical_layout })
        })
        .collect()
}

/// The connectors of the monitors there are now (GNOME), to tell afterwards which one was made for a viewer.
pub fn monitor_connectors() -> Vec<String> {
    let Ok(connection) = zbus::blocking::Connection::session() else { return Vec::new() };
    display_state(&connection).map(|state| state.1.into_iter().map(|m| m.0 .0).collect()).unwrap_or_default()
}

/// Puts the monitor that was made for a viewer (the one that is new since `before`) on the left or the right of the others, for this session
/// only (GNOME forgets it when the monitor goes). GNOME puts a new monitor on the right by itself, so only the left needs a change.
pub fn place_made_monitor(before: &[String], left: bool) -> Result<(), String> {
    let connection = zbus::blocking::Connection::session().map_err(text)?;
    let state = display_state(&connection)?;
    let (serial, monitors, logical, _) = state;
    let Some(made) = monitors.iter().map(|m| m.0 .0.clone()).find(|c| !before.contains(c)) else {
        return Err("the monitor that was made is not among the monitors".into());
    };
    // The mode each monitor has now, which the new layout keeps.
    let mode_of = |connector: &str| -> Option<String> {
        monitors.iter().find(|m| m.0 .0 == connector).and_then(|m| {
            m.1.iter().find(|mode| mode.6.get("is-current").and_then(|v| v.downcast_ref::<bool>().ok()).unwrap_or(false)).map(|mode| mode.0.clone())
        })
    };
    let width_of = |l: &LogicalMonitor| -> i32 {
        let connector = &l.5.first().map(|s| s.0.clone()).unwrap_or_default();
        let mode = monitors.iter().find(|m| &m.0 .0 == connector).and_then(|m| m.1.iter().find(|mode| Some(mode.0.clone()) == mode_of(connector)));
        let pixels = mode.map(|m| if l.3 % 2 == 1 { m.2 } else { m.1 }).unwrap_or(0);
        (f64::from(pixels) / l.2.max(0.5)).round() as i32
    };
    let Some(own) = logical.iter().find(|l| l.5.iter().any(|s| s.0 == made)) else { return Err("the monitor that was made has no place".into()) };
    let own_width = width_of(own);
    let others_left = logical.iter().filter(|l| !l.5.iter().any(|s| s.0 == made)).map(|l| l.0).min().unwrap_or(0);
    let others_right = logical.iter().filter(|l| !l.5.iter().any(|s| s.0 == made)).map(|l| l.0 + width_of(l)).max().unwrap_or(0);
    let mut layout = Vec::new();
    for l in &logical {
        let is_made = l.5.iter().any(|s| s.0 == made);
        // The made one at the far left with the others moved over by its width, or at the far right of the others.
        let x = match (is_made, left) {
            (true, true) => 0,
            (true, false) => others_right - others_left,
            (false, true) => l.0 - others_left + own_width,
            (false, false) => l.0 - others_left,
        };
        let y = if is_made { 0 } else { l.1 };
        let specs: Vec<(String, String, std::collections::HashMap<String, zbus::zvariant::Value>)> = l
            .5
            .iter()
            .filter_map(|s| mode_of(&s.0).map(|mode| (s.0.clone(), mode, std::collections::HashMap::new())))
            .collect();
        layout.push((x, y, l.2, l.3, l.4, specs));
    }
    let properties: std::collections::HashMap<String, zbus::zvariant::Value> = std::collections::HashMap::new();
    // 1: for now, not kept in the settings of the desktop.
    connection
        .call_method(
            Some("org.gnome.Mutter.DisplayConfig"),
            "/org/gnome/Mutter/DisplayConfig",
            Some("org.gnome.Mutter.DisplayConfig"),
            "ApplyMonitorsConfig",
            &(serial, 1u32, layout, properties),
        )
        .map(|_| ())
        .map_err(text)
}
