//! The few parts of Windows that Tandem uses, so the rest of the app does not need to know their API: how full the
//! battery is, and the media controls of the system (the overlay by the volume, the lock screen, and the media keys of
//! the keyboard). On other systems they do nothing, which keeps the app compiling and testable anywhere.

use std::time::Duration;

/// The battery of this PC. A PC without one (a desktop) has no `Battery`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Battery {
    pub level: u8,
    pub charging: bool,
    pub saver: bool,
}

/// A button of the media controls of the system, pressed on the overlay or on the keyboard.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Button {
    Play,
    Pause,
    Stop,
    Next,
    Previous,
}

/// What the system asks of the player behind its media controls.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Request {
    Button(Button),
    /// Somebody dragged the bar of the overlay to this point.
    Seek(Duration),
}

/// What the media controls of the system show.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Now {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
    pub position: Option<Duration>,
    pub duration: Option<Duration>,
    pub can_prev: bool,
    pub can_next: bool,
    /// Which cover this is, and the cover (a JPEG) once it is there. The same key is not handed to the system again.
    pub art: u64,
    pub cover: Option<Vec<u8>>,
}

/// How the screen is taken for showing it to somebody.
#[derive(Clone, Copy, Debug)]
pub struct GrabOptions {
    /// The size that the viewer wants at most (0 for no limit).
    pub max: (u32, u32),
    /// The pointer of this computer is in the picture. Off for a viewer that has a pointer of its own over the picture: it would be two.
    pub cursor: bool,
    /// The most pictures a second that are wanted (0 for as many as the system makes).
    pub fps: u32,
    /// The pictures come as I420, which is what the encoder wants (only where the system can make them that way).
    pub i420: bool,
    /// Not the screen that is there but a monitor that the desktop makes for the viewer, of this size (Wayland, where the portal offers
    /// virtual monitors): another computer uses it as a second screen. `None` for the screen as it is.
    pub virtual_size: Option<(u32, u32)>,
    /// The same session also takes what the viewer touches and types, onto that screen, at the place that was touched (Wayland, where the
    /// desktop allows it; when it does not, the screen is only looked at, or the general input of this computer is used).
    pub input: bool,
}

/// What the hooks on the mouse and keyboard of this PC see.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Seen {
    /// The pointer moved: by how much, and where it is (the place it would have been, when it is being held still).
    Move { dx: i32, dy: i32, x: i32, y: i32 },
    /// 0 left, 1 right, 2 middle.
    Button { button: u8, down: bool },
    /// Notches of the wheel times twelve, positive when the content is pushed down (the wheel turned up).
    Scroll { dx: i32, dy: i32 },
    /// The Windows virtual-key code, and whether it went down.
    Key { vk: u16, down: bool },
}

pub mod hid;
#[cfg(windows)]
mod imp;
#[cfg(target_os = "linux")]
mod grabber;
#[cfg(target_os = "linux")]
pub mod ble;
#[cfg(target_os = "linux")]
mod lid;
#[cfg(target_os = "linux")]
mod mpris;
/// The portals of a Wayland desktop: keys, the pointer and the screen.
#[cfg(target_os = "linux")]
pub mod portal;
/// Programs of the system that this one starts (`gst-launch-1.0`, `curl`, `tailscale`, `ssh`) must not see the libraries, plugins and paths of
/// the AppImage it runs from, or they load the wrong ones or look only there and find nothing.
#[cfg(target_os = "linux")]
mod appenv;
/// Notifications with buttons, for what has to be seen.
#[cfg(target_os = "linux")]
pub mod notify;
/// Wayland: the mouse and keyboard of this computer, on their way to another computer.
#[cfg(target_os = "linux")]
pub mod inputcapture;
#[cfg(target_os = "linux")]
mod x11grab;
#[cfg(target_os = "linux")]
mod clipwatch;
/// Linux: a strip at the edge of the screen that catches a drag, to hand it on to the computer there.
#[cfg(target_os = "linux")]
pub mod edgecatch;

/// A side of the screen.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Side {
    Left,
    Right,
    Top,
    Bottom,
}

/// The local files in a `text/uri-list` (what a drag or a copy of files carries): one URI a line, comments start with `#`.
pub fn uri_list_paths(list: &str) -> Vec<String> {
    list.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .filter_map(|line| line.strip_prefix("file://"))
        // A host may come before the path (`file://host/path`); the path starts at the first slash.
        .filter_map(|rest| rest.find('/').map(|at| &rest[at..]))
        .map(percent_decode)
        .collect()
}

/// A `file://` URI for a path, with what may not stand in a URI escaped.
pub fn file_uri(path: &str) -> String {
    let mut uri = String::from("file://");
    for byte in path.bytes() {
        if byte.is_ascii_alphanumeric() || b"/-._~".contains(&byte) {
            uri.push(byte as char);
        } else {
            uri.push_str(&format!("%{byte:02X}"));
        }
    }
    uri
}

fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(value) = u8::from_str_radix(&text[i + 1..i + 3], 16) {
                out.push(value);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod uri_tests {
    use super::*;

    #[test]
    fn a_uri_list_gives_the_paths_of_its_files() {
        let list = "# from Files\r\nfile:///home/Mark/Downloads/20260923-DSC03038%20(1).jpg\r\nfile://laptop/tmp/a%25b.txt\r\nhttps://example.com/x\r\n";
        assert_eq!(uri_list_paths(list), vec!["/home/Mark/Downloads/20260923-DSC03038 (1).jpg".to_string(), "/tmp/a%b.txt".to_string()]);
    }

    #[test]
    fn a_path_becomes_a_uri_and_back() {
        let path = "/home/Mark/Downloads/foto (1) & co.jpg";
        assert_eq!(file_uri(path), "file:///home/Mark/Downloads/foto%20%281%29%20%26%20co.jpg");
        assert_eq!(uri_list_paths(&file_uri(path)), vec![path.to_string()]);
    }

    #[test]
    fn a_broken_escape_is_kept_as_it_is() {
        assert_eq!(uri_list_paths("file:///tmp/100%"), vec!["/tmp/100%".to_string()]);
        assert_eq!(uri_list_paths("file:///tmp/%zz"), vec!["/tmp/%zz".to_string()]);
    }
}
/// The sounds of the system, ringing until found, and the Wi-Fi this computer is on.
pub mod find;
#[cfg(not(windows))]
mod imp {
    #[cfg_attr(target_os = "linux", allow(unused_imports))]
    use super::{Battery, Now, Request};

    /// The first battery the kernel lists, on a laptop; a desktop has none. Linux says it in files, and anything that is not
    /// there reads as no battery.
    pub fn battery() -> Option<Battery> {
        let dir = std::fs::read_dir("/sys/class/power_supply").ok()?;
        for entry in dir.flatten() {
            let path = entry.path();
            let Ok(kind) = std::fs::read_to_string(path.join("type")) else { continue };
            if kind.trim() != "Battery" {
                continue;
            }
            let Some(level) = std::fs::read_to_string(path.join("capacity")).ok().and_then(|t| t.trim().parse::<u8>().ok()) else { continue };
            let status = std::fs::read_to_string(path.join("status")).unwrap_or_default();
            // Full on the charger counts as charging: the cable is in.
            let charging = matches!(status.trim(), "Charging" | "Full");
            return Some(Battery { level: level.min(100), charging, saver: false });
        }
        None
    }

    pub fn describe(_window: isize) -> String {
        String::new()
    }

    /// Linux has the X server tell it (`clipwatch.rs`); elsewhere the caller has to look by itself.
    pub fn watch_clipboard(on_change: impl Fn() + Send + Sync + 'static) -> bool {
        #[cfg(target_os = "linux")]
        {
            crate::clipwatch::watch(on_change)
        }
        #[cfg(not(target_os = "linux"))]
        {
            let _ = on_change;
            false
        }
    }


    /// Nothing sees the mouse and keyboard on this system; the pointer cannot be shared from here.
    pub struct Capture;

    impl Capture {
        pub fn start(_handler: impl Fn(super::Seen) -> bool + Send + Sync + 'static) -> Option<Capture> {
            None
        }
        pub fn hold(&self, _on: bool) {}
    }

    pub fn screen() -> (i32, i32) {
        #[cfg(target_os = "linux")]
        {
            if let Some((w, h)) = crate::x11grab::root_size() {
                return (w as i32, h as i32);
            }
        }
        (0, 0)
    }

    pub fn desktop() -> (i32, i32, i32, i32) {
        (0, 0, 0, 0)
    }

    pub fn warp(_x: i32, _y: i32) {}

    /// Whether the screen is locked, where that can be known (Linux).
    pub fn screen_locked() -> Option<bool> {
        #[cfg(target_os = "linux")]
        {
            crate::lid::locked()
        }
        #[cfg(not(target_os = "linux"))]
        {
            None
        }
    }

    /// Whether the lid of this laptop is closed, where that can be known (Linux).
    pub fn lid_closed() -> Option<bool> {
        #[cfg(target_os = "linux")]
        {
            crate::lid::closed()
        }
        #[cfg(not(target_os = "linux"))]
        {
            None
        }
    }

    /// Linux has the media controls of the desktop (`mpris.rs`); other systems have none.
    #[cfg(not(target_os = "linux"))]
    pub struct Media;

    #[cfg(not(target_os = "linux"))]
    impl Media {
        pub fn start(_on_request: impl Fn(Request) + Send + Sync + 'static) -> Media {
            Media
        }
        pub fn show(&self, _now: Now) {}
        pub fn hide(&self) {}
    }

    #[cfg(target_os = "linux")]
    pub use crate::mpris::Media;

    /// Only Windows and Linux (under X11) can take a picture of the screen here; the Mac app does it in its own way.
    #[cfg(target_os = "linux")]
    pub use crate::grabber::Grabber;

    #[cfg(not(target_os = "linux"))]
    pub struct Grabber;

    #[cfg(not(target_os = "linux"))]
    impl Grabber {
        pub fn available() -> bool {
            false
        }
        pub fn new() -> Option<Grabber> {
            None
        }
        pub fn with_options(_options: super::GrabOptions) -> Option<Grabber> {
            None
        }
        pub fn is_i420(&self) -> bool {
            false
        }
        pub fn grab_shared(&self) -> Option<(u32, u32, std::sync::Arc<Vec<u8>>)> {
            None
        }
        pub fn with_limit(_max_width: u32, _max_height: u32) -> Option<Grabber> {
            None
        }
        pub fn size(&self) -> (u32, u32) {
            (0, 0)
        }
        pub fn grab(&self) -> Option<(u32, u32, Vec<u8>)> {
            None
        }
    }
}

pub use imp::{Capture, Grabber, Media, battery, describe, desktop, lid_closed, screen, screen_locked, warp, watch_clipboard};

/// One screen as the desktop itself describes it. The window toolkit of Linux says the size of a screen in logical pixels times a
/// whole scale (2 for a screen at 125% or 150% on GNOME), which is neither the pixels of the screen nor its logical size times its
/// real scale; the desktop knows both.
#[derive(Clone, Debug, PartialEq)]
pub struct Monitor {
    /// The name of its connector (`eDP-1`), which is also the name of its Wayland output.
    pub connector: String,
    /// Where it is in the layout of the screens, in logical pixels when `logical_layout` and in pixels when not.
    pub x: i32,
    pub y: i32,
    /// The pixels of the mode in use, the way the screen stands (a screen turned on its side is taller than wide).
    pub pixels: (u32, u32),
    pub scale: f64,
    pub primary: bool,
    /// The layout is in logical pixels (GNOME on Wayland) and not in pixels (X11).
    pub logical_layout: bool,
}

impl Monitor {
    /// The size that windows are laid out in on this screen: its pixels divided by its scale.
    pub fn logical(&self) -> (u32, u32) {
        let scale = if self.scale.is_finite() && self.scale >= 0.5 { self.scale } else { 1.0 };
        ((f64::from(self.pixels.0) / scale).round() as u32, (f64::from(self.pixels.1) / scale).round() as u32)
    }
}

/// The screens of this computer as the desktop has them (GNOME); empty where it does not say.
pub fn monitors() -> Vec<Monitor> {
    #[cfg(target_os = "linux")]
    {
        portal::monitors()
    }
    #[cfg(not(target_os = "linux"))]
    {
        Vec::new()
    }
}

/// Which of `monitors` is the screen the window toolkit describes as lying at `logical_at` (in logical pixels; `pixel_at` is the same
/// place times its whole scale) with the logical size `logical`. By place first, then by size, then the only one, then the primary.
pub fn pick_monitor(monitors: &[Monitor], logical_at: (i32, i32), pixel_at: (i32, i32), logical: (u32, u32)) -> Option<Monitor> {
    let placed = monitors.iter().find(|m| (m.x, m.y) == if m.logical_layout { logical_at } else { pixel_at });
    if let Some(found) = placed {
        return Some(found.clone());
    }
    let close = |a: u32, b: u32| a.abs_diff(b) <= 1;
    let sized: Vec<&Monitor> = monitors.iter().filter(|m| close(m.logical().0, logical.0) && close(m.logical().1, logical.1)).collect();
    if let [only] = sized.as_slice() {
        return Some((*only).clone());
    }
    if let [only] = monitors {
        return Some(only.clone());
    }
    monitors.iter().find(|m| m.primary).cloned()
}

#[cfg(test)]
mod monitor_tests {
    use super::*;

    fn screen(x: i32, pixels: (u32, u32), scale: f64, primary: bool) -> Monitor {
        Monitor { connector: format!("DP-{x}"), x, y: 0, pixels, scale, primary, logical_layout: true }
    }

    #[test]
    fn a_scaled_screen_has_the_logical_size_its_desktop_draws_in() {
        assert_eq!(screen(0, (2880, 1800), 1.5, true).logical(), (1920, 1200));
        assert_eq!(screen(0, (1920, 1080), 1.25, true).logical(), (1536, 864));
        // GNOME keeps its scales as floats that are a hair off.
        assert_eq!(screen(0, (1920, 1080), 1.2500000476837158, true).logical(), (1536, 864));
        assert_eq!(screen(0, (2560, 1600), 2.0, true).logical(), (1280, 800));
        assert_eq!(screen(0, (1920, 1080), f64::NAN, true).logical(), (1920, 1080));
    }

    #[test]
    fn the_screen_of_the_window_is_found_by_place_then_by_size() {
        let laptop = screen(0, (2880, 1800), 1.5, true);
        let external = screen(1920, (3840, 2160), 1.0, false);
        let both = [laptop.clone(), external.clone()];
        // GTK says the external screen lies at 1920 (logical) and is 3840 by 2160; the laptop at 0, 1920 by 1200 logical.
        assert_eq!(pick_monitor(&both, (1920, 0), (1920, 0), (3840, 2160)), Some(external.clone()));
        assert_eq!(pick_monitor(&both, (0, 0), (0, 0), (1920, 1200)), Some(laptop.clone()));
        // A place that matches nothing: the size decides.
        assert_eq!(pick_monitor(&both, (5, 5), (10, 10), (1920, 1200)), Some(laptop.clone()));
        // Nothing matches: the only screen there is, or the primary one.
        assert_eq!(pick_monitor(&[external.clone()], (5, 5), (10, 10), (1, 1)), Some(external.clone()));
        assert_eq!(pick_monitor(&both, (5, 5), (10, 10), (1, 1)), Some(laptop.clone()));
        assert_eq!(pick_monitor(&[], (0, 0), (0, 0), (1920, 1080)), None);
        // X11 lays the screens out in pixels.
        let x11 = Monitor { x: 2880, logical_layout: false, ..external };
        assert_eq!(pick_monitor(&[laptop, x11.clone()], (1440, 0), (2880, 0), (0, 0)), Some(x11));
    }
}

/// Lets a pipe hold a megabyte instead of 64 KB, the most Linux allows without privileges.
#[cfg(target_os = "linux")]
pub fn grow_pipe(file: &impl std::os::fd::AsRawFd) {
    unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETPIPE_SZ, 1 << 20) };
}

/// Takes what points into an AppImage out of the environment of a program of the system that is about to be started (nothing on other
/// systems, where there is no such thing).
pub fn system_env(command: &mut std::process::Command) {
    #[cfg(target_os = "linux")]
    appenv::clean(command);
    #[cfg(not(target_os = "linux"))]
    let _ = command;
}

/// The same changes as a list, for a program that is started some other way than with `std::process::Command` (the terminal): the variable
/// and its new value, or nothing to take it away.
pub fn system_env_edits() -> Vec<(String, Option<String>)> {
    #[cfg(target_os = "linux")]
    {
        appenv::edits()
    }
    #[cfg(not(target_os = "linux"))]
    {
        Vec::new()
    }
}
