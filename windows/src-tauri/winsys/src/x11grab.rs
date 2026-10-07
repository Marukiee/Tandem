//! A picture of the screen on Linux under X11, for showing it to another device. The whole root window is asked for from the X
//! server, which is slower than shared memory but needs nothing besides the connection. Under Wayland an application may not
//! look at the screen without asking the desktop (a portal and a permission dialog), and that is not done here: the connection
//! then shows only the old-style windows, and `new` says no.

use x11rb::connection::Connection;
use x11rb::protocol::xproto::{ConnectionExt, ImageFormat};
use x11rb::rust_connection::RustConnection;

pub struct Grabber {
    connection: RustConnection,
    root: u32,
    width: u16,
    height: u16,
}

impl Grabber {
    pub fn new() -> Option<Grabber> {
        // A Wayland session has a connection to the X compatibility layer, which does not show the screen.
        if std::env::var("XDG_SESSION_TYPE").map(|s| s.eq_ignore_ascii_case("wayland")).unwrap_or(false) {
            return None;
        }
        let (connection, number) = x11rb::connect(None).ok()?;
        let screen = connection.setup().roots.get(number)?;
        let (root, width, height) = (screen.root, screen.width_in_pixels, screen.height_in_pixels);
        Some(Grabber { connection, root, width, height })
    }

    pub fn size(&self) -> (u32, u32) {
        (u32::from(self.width), u32::from(self.height))
    }

    /// The width, the height and the pixels as B, G, R, unused, line after line from the top.
    pub fn grab(&self) -> Option<(u32, u32, Vec<u8>)> {
        let reply = self
            .connection
            .get_image(ImageFormat::Z_PIXMAP, self.root, 0, 0, self.width, self.height, u32::MAX)
            .ok()?
            .reply()
            .ok()?;
        let (w, h) = (usize::from(self.width), usize::from(self.height));
        // 24 bit colour is stored in four bytes per pixel by every server that is in use.
        if reply.data.len() != w * h * 4 {
            return None;
        }
        Some((u32::from(self.width), u32::from(self.height), reply.data))
    }
}
