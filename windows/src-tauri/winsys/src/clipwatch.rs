//! When the clipboard of Linux changes. The X server says it when the clipboard gets a new owner, which is every copy; under Wayland
//! the desktop hands each copy on to its X compatibility layer for the old-style programs, so the same message comes there too. This
//! spares looking at the clipboard every so often, which with a picture on it means reading the whole picture each time.

use x11rb::connection::Connection;
use x11rb::protocol::Event;
use x11rb::protocol::xfixes::{ConnectionExt as _, SelectionEventMask};
use x11rb::protocol::xproto::ConnectionExt as _;

/// False when there is no X server or it cannot tell, and the caller looks by itself.
pub fn watch(on_change: impl Fn() + Send + Sync + 'static) -> bool {
    let Ok((connection, number)) = x11rb::connect(None) else { return false };
    let Some(root) = connection.setup().roots.get(number).map(|s| s.root) else { return false };
    // The extension has to be asked for its version before it is used.
    if connection.xfixes_query_version(5, 0).ok().and_then(|c| c.reply().ok()).is_none() {
        return false;
    }
    let Some(clipboard) = connection.intern_atom(false, b"CLIPBOARD").ok().and_then(|c| c.reply().ok()).map(|r| r.atom) else { return false };
    let mask = SelectionEventMask::SET_SELECTION_OWNER | SelectionEventMask::SELECTION_WINDOW_DESTROY | SelectionEventMask::SELECTION_CLIENT_CLOSE;
    if connection.xfixes_select_selection_input(root, clipboard, mask).ok().and_then(|c| c.check().ok()).is_none() {
        return false;
    }
    let _ = connection.flush();
    std::thread::Builder::new()
        .name("tandem-clipboard".into())
        .spawn(move || {
            loop {
                match connection.wait_for_event() {
                    Ok(Event::XfixesSelectionNotify(_)) => on_change(),
                    Ok(_) => {}
                    Err(error) => {
                        log::warn!("the clipboard is no longer watched: {error}");
                        return;
                    }
                }
            }
        })
        .is_ok()
}
