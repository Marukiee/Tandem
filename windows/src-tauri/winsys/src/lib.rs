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

#[cfg(windows)]
mod imp;
#[cfg(target_os = "linux")]
mod grabber;
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

    /// Nothing tells this system when the clipboard changes, so the caller has to look by itself.
    pub fn watch_clipboard(_on_change: impl Fn() + Send + Sync + 'static) -> bool {
        false
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
