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
    Axis, DeviceType, KeyState, NotifyKeyboardKeysymOptions, NotifyPointerAxisDiscreteOptions, NotifyPointerButtonOptions,
    NotifyPointerMotionOptions, RemoteDesktop, SelectDevicesOptions, StartOptions,
};
use ashpd::desktop::screencast::{CursorMode, OpenPipeWireRemoteOptions, Screencast, SelectSourcesOptions, SourceType, StartCastOptions};
use enumflags2::BitFlags;

fn text<E: std::fmt::Display>(error: E) -> String {
    error.to_string()
}

/// Where the tokens that make the desktop remember its answer are kept.
static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

pub fn set_data_dir(dir: PathBuf) {
    let _ = DATA_DIR.set(dir);
}

fn token_file(name: &str) -> Option<PathBuf> {
    DATA_DIR.get().map(|dir| dir.join(name))
}

fn read_token(name: &str) -> Option<String> {
    let text = std::fs::read_to_string(token_file(name)?).ok()?;
    Some(text.trim().to_string()).filter(|t| !t.is_empty())
}

fn write_token(name: &str, token: &str) {
    if let Some(path) = token_file(name) {
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

fn portal_property(interface: &str, property: &str) -> Option<u32> {
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

/// Whether the screen can be shown here: the desktop offers monitors to share, and GStreamer can read them.
pub fn screen_available() -> bool {
    static ANSWER: OnceLock<bool> = OnceLock::new();
    *ANSWER.get_or_init(|| {
        let offered = portal_property("org.freedesktop.portal.ScreenCast", "AvailableSourceTypes").is_some_and(|types| types & 1 != 0);
        offered
            && Command::new("gst-inspect-1.0")
                .arg("pipewiresrc")
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .is_ok_and(|s| s.success())
    })
}

// ---- The size of the screen ---------------------------------------------------------------------------------------------------

/// The size of the area the pointer moves in, which on Wayland is the layout of the screens in logical pixels. GNOME says it
/// (a scaled screen is smaller than its mode), and nothing else does in a way that is the same everywhere; `None` when it is not asked.
pub fn logical_screen() -> Option<(i32, i32)> {
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
            Ok(Input { remote, session })
        })
    }

    /// The pointer moves by this much (logical pixels).
    pub fn motion(&self, dx: f64, dy: f64) {
        let _ = pollster::block_on(self.remote.notify_pointer_motion(&self.session, dx, dy, NotifyPointerMotionOptions::default()));
    }

    /// A button of the pointer by its Linux code (0x110 left, 0x111 right, 0x112 middle).
    pub fn button(&self, code: i32, down: bool) {
        let state = if down { KeyState::Pressed } else { KeyState::Released };
        let _ = pollster::block_on(self.remote.notify_pointer_button(&self.session, code, state, NotifyPointerButtonOptions::default()));
    }

    /// Notches of the wheel: positive goes down (or right).
    pub fn wheel(&self, vertical: bool, steps: i32) {
        let axis = if vertical { Axis::Vertical } else { Axis::Horizontal };
        let _ = pollster::block_on(self.remote.notify_pointer_axis_discrete(&self.session, axis, steps, NotifyPointerAxisDiscreteOptions::default()));
    }

    /// A key by its X11 keysym, which names what is printed on it, whatever the layout.
    pub fn key(&self, keysym: i32, down: bool) {
        let state = if down { KeyState::Pressed } else { KeyState::Released };
        let _ = pollster::block_on(self.remote.notify_keyboard_keysym(&self.session, keysym, state, NotifyKeyboardKeysymOptions::default()));
    }

    /// Puts the pointer against the edges given: a movement that is far more than the screen is, which the desktop stops at the edge. After
    /// this the place of the pointer is known on that axis, which a count of movements needs.
    pub fn slam(&self, left: bool, right: bool, top: bool, bottom: bool) {
        const FAR: f64 = 20_000.0;
        let dx = if left { -FAR } else if right { FAR } else { 0.0 };
        let dy = if top { -FAR } else if bottom { FAR } else { 0.0 };
        if dx != 0.0 || dy != 0.0 {
            self.motion(dx, dy);
        }
    }
}

impl Drop for Input {
    fn drop(&mut self) {
        let _ = pollster::block_on(self.session.close());
    }
}

// ---- The screen -------------------------------------------------------------------------------------------------------------------

/// The screen of a Wayland desktop as raw pictures: B, G, R, unused, line after line.
pub struct WaylandGrabber {
    size: (u32, u32),
    latest: Arc<Mutex<Option<Vec<u8>>>>,
    alive: Arc<AtomicBool>,
    child: Child,
    // Kept for as long as the pictures come: closing the session ends the sharing.
    _cast: Screencast,
    session: Session<Screencast>,
}

/// The largest even size that fits in what is wanted, without growing the screen.
fn fit(source: (u32, u32), max: (u32, u32)) -> (u32, u32) {
    let (sw, sh) = (source.0.max(2) as f64, source.1.max(2) as f64);
    let max_w = if max.0 == 0 { sw } else { max.0 as f64 };
    let max_h = if max.1 == 0 { sh } else { max.1 as f64 };
    let scale = (max_w / sw).min(max_h / sh).min(1.0);
    let even = |n: f64| (((n as u32).max(16)) & !1).max(16);
    (even(sw * scale), even(sh * scale))
}

impl WaylandGrabber {
    /// Asks for a monitor (the desktop shows a window for it the first time) and starts reading it. `max` is the size that the viewer
    /// wants at most; a bigger screen is made smaller on the way, which costs far less than doing it afterwards.
    pub fn start(max: (u32, u32)) -> Result<WaylandGrabber, String> {
        pollster::block_on(async {
            let cast = Screencast::new().await.map_err(text)?;
            let session = cast.create_session(CreateSessionOptions::default()).await.map_err(text)?;
            let token = read_token("portal-screen.token");
            cast.select_sources(
                &session,
                SelectSourcesOptions::default()
                    .set_cursor_mode(CursorMode::Embedded)
                    .set_sources(BitFlags::from_flag(SourceType::Monitor))
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
                write_token("portal-screen.token", token);
            }
            let stream = streams.streams().first().ok_or("no screen was chosen")?;
            let node = stream.pipe_wire_node_id();
            let source = stream.size().map(|(w, h)| (w.max(2) as u32, h.max(2) as u32)).unwrap_or((1920, 1080));
            let remote = cast.open_pipe_wire_remote(&session, OpenPipeWireRemoteOptions::default()).await.map_err(text)?;
            let size = fit(source, max);

            let raw = remote.as_raw_fd();
            let mut command = Command::new("gst-launch-1.0");
            command
                .args(["-q", "pipewiresrc", "fd=3"])
                .arg(format!("path={node}"))
                .args(["always-copy=true", "do-timestamp=true", "!", "videoconvert", "!", "videoscale", "!"])
                .arg(format!("video/x-raw,format=BGRx,width={},height={}", size.0, size.1))
                .args(["!", "fdsink", "fd=1", "sync=false"])
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::null());
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

            let latest: Arc<Mutex<Option<Vec<u8>>>> = Arc::new(Mutex::new(None));
            let alive = Arc::new(AtomicBool::new(true));
            let (store, flag) = (latest.clone(), alive.clone());
            let length = size.0 as usize * size.1 as usize * 4;
            std::thread::Builder::new()
                .name("tandem-wayland-screen".into())
                .spawn(move || {
                    let mut frame = vec![0u8; length];
                    while pipe.read_exact(&mut frame).is_ok() {
                        *store.lock().unwrap() = Some(frame.clone());
                    }
                    flag.store(false, Ordering::Relaxed);
                })
                .map_err(text)?;
            Ok(WaylandGrabber { size, latest, alive, child, _cast: cast, session })
        })
    }

    pub fn size(&self) -> (u32, u32) {
        self.size
    }

    /// The newest picture. The same one again while the screen has not changed.
    pub fn grab(&self) -> Option<(u32, u32, Vec<u8>)> {
        if !self.alive.load(Ordering::Relaxed) {
            return None;
        }
        self.latest.lock().unwrap().clone().map(|pixels| (self.size.0, self.size.1, pixels))
    }
}

impl Drop for WaylandGrabber {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = pollster::block_on(self.session.close());
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
        assert!(w % 2 == 0 && h % 2 == 0);
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
