//! A strip at the edge of the screen that catches files dragged into it, so a drag can go on to the computer that sits there. It is a
//! window of the X server (XWayland under Wayland): a Wayland window may not say where it goes, an X window may, and the desktop passes
//! drags between its own windows and those of the X server. The strip reads what is dragged as soon as the drag reaches it, with the
//! button still down (XDND allows asking for the data before the drop), so the drag can be handed on before it is let go. It is fully
//! transparent, a few pixels wide, and only there while the caller wants it.

use std::sync::Arc;

use x11rb::connection::Connection;
use x11rb::protocol::Event;
use x11rb::protocol::xproto::{
    AtomEnum, ClientMessageEvent, ColormapAlloc, ConnectionExt as _, CreateWindowAux, EventMask, PropMode, VisualClass, WindowClass,
};
use x11rb::rust_connection::RustConnection;
use x11rb::wrapper::ConnectionExt as _;

use super::Side;

x11rb::atom_manager! {
    Atoms: AtomsCookie {
        XdndAware,
        XdndEnter,
        XdndPosition,
        XdndStatus,
        XdndLeave,
        XdndDrop,
        XdndFinished,
        XdndSelection,
        XdndActionCopy,
        UriList: b"text/uri-list",
        TANDEM_DROP,
    }
}

/// The strip. It goes when this is dropped.
pub struct Catcher {
    connection: Arc<RustConnection>,
    window: u32,
}

impl Drop for Catcher {
    fn drop(&mut self) {
        // The thread that reads the events ends on the window going.
        let _ = self.connection.destroy_window(self.window);
        let _ = self.connection.flush();
    }
}

/// A strip `thickness` pixels of the X server wide along `side` of the whole screen. `on_files` gets the paths of the files of a drag
/// that reaches it, once per drag.
pub fn catch(side: Side, thickness: u16, on_files: impl Fn(Vec<String>) + Send + 'static) -> Option<Catcher> {
    let (connection, number) = x11rb::connect(None).ok()?;
    let connection = Arc::new(connection);
    let screen = connection.setup().roots.get(number)?.clone();
    let atoms = Atoms::new(connection.as_ref()).ok()?.reply().ok()?;
    let (width, height) = (screen.width_in_pixels, screen.height_in_pixels);
    let t = thickness.max(1);
    let (x, y, w, h) = match side {
        Side::Left => (0, 0, t, height),
        Side::Right => (width.saturating_sub(t) as i16, 0, t, height),
        Side::Top => (0, 0, width, t),
        Side::Bottom => (0, height.saturating_sub(t) as i16, width, t),
    };
    // A visual with an alpha channel, so the strip can be fully see-through: nothing is drawn, nothing shows.
    let visual = screen
        .allowed_depths
        .iter()
        .find(|d| d.depth == 32)
        .and_then(|d| d.visuals.iter().find(|v| v.class == VisualClass::TRUE_COLOR))
        .map(|v| v.visual_id)?;
    let colormap = connection.generate_id().ok()?;
    connection.create_colormap(ColormapAlloc::NONE, colormap, screen.root, visual).ok()?;
    let window = connection.generate_id().ok()?;
    let aux = CreateWindowAux::new()
        .background_pixel(0)
        .border_pixel(0)
        .colormap(colormap)
        // Placed where it is asked for and above the other windows, not handled by the window manager.
        .override_redirect(1)
        .event_mask(EventMask::STRUCTURE_NOTIFY);
    connection.create_window(32, window, screen.root, x, y, w, h, 0, WindowClass::INPUT_OUTPUT, visual, &aux).ok()?;
    connection.change_property32(PropMode::REPLACE, window, atoms.XdndAware, AtomEnum::ATOM, &[5]).ok()?;
    connection.map_window(window).ok()?;
    connection.flush().ok()?;

    let reader = connection.clone();
    std::thread::Builder::new()
        .name("tandem-edge-catch".into())
        .spawn(move || listen(reader, window, atoms, on_files))
        .ok()?;
    Some(Catcher { connection, window })
}

fn listen(connection: Arc<RustConnection>, window: u32, atoms: Atoms, on_files: impl Fn(Vec<String>)) {
    // Whether the files of the drag that is over the strip were asked for, and handed on.
    let mut asked = false;
    let mut caught = false;
    let reply = |to: u32, kind: u32, data: [u32; 5]| {
        let event = ClientMessageEvent::new(32, to, kind, data);
        let _ = connection.send_event(false, to, EventMask::NO_EVENT, event);
        let _ = connection.flush();
    };
    loop {
        let Ok(event) = connection.wait_for_event() else { return };
        match event {
            Event::ClientMessage(message) if message.type_ == atoms.XdndEnter || message.type_ == atoms.XdndLeave => {
                asked = false;
                caught = false;
            }
            Event::ClientMessage(message) if message.type_ == atoms.XdndPosition => {
                let data = message.data.as_data32();
                let (source, time) = (data[0], data[3]);
                // Taken, as a copy, and more positions wanted.
                reply(source, atoms.XdndStatus, [window, 0b11, 0, 0, atoms.XdndActionCopy]);
                if !asked {
                    asked = true;
                    let _ = connection.convert_selection(window, atoms.XdndSelection, atoms.UriList, atoms.TANDEM_DROP, time);
                    let _ = connection.flush();
                }
            }
            Event::ClientMessage(message) if message.type_ == atoms.XdndDrop => {
                let data = message.data.as_data32();
                if !asked {
                    asked = true;
                    let _ = connection.convert_selection(window, atoms.XdndSelection, atoms.UriList, atoms.TANDEM_DROP, data[2]);
                }
                reply(data[0], atoms.XdndFinished, [window, 1, atoms.XdndActionCopy, 0, 0]);
            }
            Event::SelectionNotify(note) if note.property == atoms.TANDEM_DROP && !caught => {
                let value = connection
                    .get_property(true, window, atoms.TANDEM_DROP, AtomEnum::ANY, 0, 1 << 20)
                    .ok()
                    .and_then(|c| c.reply().ok())
                    .map(|r| r.value)
                    .unwrap_or_default();
                let paths = crate::uri_list_paths(&String::from_utf8_lossy(&value));
                if !paths.is_empty() {
                    caught = true;
                    on_files(paths);
                }
            }
            Event::DestroyNotify(gone) if gone.window == window => return,
            _ => {}
        }
    }
}
