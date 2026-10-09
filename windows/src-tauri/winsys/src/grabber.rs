//! The screen of a Linux computer as pictures, whichever way it can be had: from the X server (the whole screen is there to ask for), or
//! through the portal of a Wayland desktop (which asks the person first, once).

use std::sync::Arc;

use crate::{GrabOptions, portal, x11grab};

enum Source {
    X(x11grab::Grabber),
    Wayland(portal::WaylandGrabber),
}

pub struct Grabber(Source);

impl Grabber {
    /// Whether the screen can be shown at all, asked without bothering anybody (no window of the desktop opens for this).
    pub fn available() -> bool {
        if portal::is_wayland() { portal::screen_available() } else { x11grab::Grabber::new().is_some() }
    }

    pub fn new() -> Option<Grabber> {
        Grabber::with_limit(0, 0)
    }

    /// Starts on the screen. On Wayland the desktop shows a window the first time, and this waits for the answer. `max_width` and
    /// `max_height` (0 for no limit) are what the viewer wants at most.
    pub fn with_limit(max_width: u32, max_height: u32) -> Option<Grabber> {
        Grabber::with_options(GrabOptions { max: (max_width, max_height), cursor: true, fps: 0, i420: false })
    }

    /// The same with the choices that matter for showing the screen to somebody who also uses it (see [`GrabOptions`]). Under X11 the
    /// pictures are always B, G, R, unused, and the pointer is never in them.
    pub fn with_options(options: GrabOptions) -> Option<Grabber> {
        if portal::is_wayland() {
            match portal::WaylandGrabber::start(options) {
                Ok(grabber) => Some(Grabber(Source::Wayland(grabber))),
                Err(error) => {
                    log::warn!("the screen could not be shared through the desktop: {error}");
                    None
                }
            }
        } else {
            x11grab::Grabber::new().map(|g| Grabber(Source::X(g)))
        }
    }

    /// Whether the pictures come as I420 already.
    pub fn is_i420(&self) -> bool {
        match &self.0 {
            Source::X(_) => false,
            Source::Wayland(g) => g.is_i420(),
        }
    }

    /// The newest picture without a copy: its size and the bytes (B, G, R, unused, or I420 when [`Grabber::is_i420`]). The same `Arc` again
    /// while the screen has not changed, which says that there is nothing new to encode.
    pub fn grab_shared(&self) -> Option<(u32, u32, Arc<Vec<u8>>)> {
        match &self.0 {
            Source::X(g) => g.grab().map(|(w, h, pixels)| (w, h, Arc::new(pixels))),
            Source::Wayland(g) => g.grab_shared(),
        }
    }

    pub fn size(&self) -> (u32, u32) {
        match &self.0 {
            Source::X(g) => g.size(),
            Source::Wayland(g) => g.size(),
        }
    }

    /// The width, the height and the pixels as B, G, R, unused, line after line from the top.
    pub fn grab(&self) -> Option<(u32, u32, Vec<u8>)> {
        match &self.0 {
            Source::X(g) => g.grab(),
            Source::Wayland(g) => g.grab(),
        }
    }
}
