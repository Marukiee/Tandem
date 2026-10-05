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
#[cfg(not(windows))]
mod imp {
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
        (0, 0)
    }

    pub fn desktop() -> (i32, i32, i32, i32) {
        (0, 0, 0, 0)
    }

    pub fn warp(_x: i32, _y: i32) {}

    pub struct Media;

    impl Media {
        pub fn start(_on_request: impl Fn(Request) + Send + Sync + 'static) -> Media {
            Media
        }
        pub fn show(&self, _now: Now) {}
        pub fn hide(&self) {}
    }
}

pub use imp::{Capture, Media, battery, describe, desktop, screen, warp, watch_clipboard};
