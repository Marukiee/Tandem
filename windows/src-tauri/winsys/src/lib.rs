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

#[cfg(windows)]
mod imp;
#[cfg(not(windows))]
mod imp {
    use super::{Battery, Now, Request};

    pub fn battery() -> Option<Battery> {
        None
    }

    pub struct Media;

    impl Media {
        pub fn start(_window: isize, _on_request: impl Fn(Request) + Send + Sync + 'static) -> Media {
            Media
        }
        pub fn show(&self, _now: Now) {}
        pub fn hide(&self) {}
    }
}

pub use imp::{Media, battery};
