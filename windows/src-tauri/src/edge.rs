//! Dragging files over the edge of the screen to the computer that is using the pointer of this one. While another computer has its
//! pointer here (see `pointer_share` and `input.rs`), a drop zone shows at the edge where that computer sits, with its name on it.
//! Letting go of files on it sends them there, and the other computer puts them where its person dropped them. The zone is a window
//! like any other, so the drag and the drop are the ones of the system.

use std::sync::Mutex;

use tandem_core::ffi::TandemShareOrigin;
use tandem_core::pointer_share::Edge;
use tauri::{AppHandle, Manager, PhysicalPosition, PhysicalSize, State, WebviewUrl, WebviewWindowBuilder};

use crate::state::AppState;
use crate::{commands, events};

const LABEL: &str = "edge-zone";
/// The device that the zone sends to.
static TARGET: Mutex<Option<String>> = Mutex::new(None);

/// Called when another computer starts or stops using the pointer of this one.
pub fn changed(app: &AppHandle, now: Option<(String, Edge)>) {
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || match now {
        Some((device, edge)) => show(&handle, device, edge),
        None => hide(&handle),
    });
}

fn show(app: &AppHandle, device: String, edge: Edge) {
    // A Wayland desktop does not let a window say where it goes, so a zone at an edge cannot be put there.
    #[cfg(target_os = "linux")]
    if tandem_winsys::portal::is_wayland() {
        return;
    }
    // First the old zone away (which also forgets its target), then the new target.
    hide(app);
    *TARGET.lock().unwrap() = Some(device.clone());
    let name = events::device_name(app, &device);
    let url = format!("edge.html?name={}", name.bytes().map(|b| if b.is_ascii_alphanumeric() { (b as char).to_string() } else { format!("%{b:02X}") }).collect::<String>());
    let Ok(Some(monitor)) = app.primary_monitor() else { return };
    let (x, y, width, height) = zone_rect((monitor.position().x, monitor.position().y), (monitor.size().width, monitor.size().height), monitor.scale_factor(), edge);
    let built = WebviewWindowBuilder::new(app, LABEL, WebviewUrl::App(url.into()))
        .decorations(false)
        .transparent(true)
        .always_on_top(true)
        .skip_taskbar(true)
        .resizable(false)
        .shadow(false)
        .focused(false)
        .visible(false)
        .build();
    match built {
        Ok(window) => {
            let _ = window.set_size(PhysicalSize::new(width, height));
            let _ = window.set_position(PhysicalPosition::new(x, y));
            let _ = window.show();
        }
        Err(error) => log::warn!("the drop zone could not be made: {error}"),
    }
}

/// Where the zone sits on a screen: (x, y, width, height) in pixels. It is a strip at the edge, about half as long as the edge.
fn zone_rect(origin: (i32, i32), size: (u32, u32), scale: f64, edge: Edge) -> (i32, i32, u32, u32) {
    let thickness = (110.0 * scale) as u32;
    let long = |full: u32| ((f64::from(full) * 0.45).clamp(260.0 * scale, 520.0 * scale)) as u32;
    let (w, h) = (size.0 as i32, size.1 as i32);
    match edge {
        Edge::Left => (origin.0, origin.1 + (h - long(size.1) as i32) / 2, thickness, long(size.1)),
        Edge::Right => (origin.0 + w - thickness as i32, origin.1 + (h - long(size.1) as i32) / 2, thickness, long(size.1)),
        Edge::Top => (origin.0 + (w - long(size.0) as i32) / 2, origin.1, long(size.0), thickness),
        Edge::Bottom => (origin.0 + (w - long(size.0) as i32) / 2, origin.1 + h - thickness as i32, long(size.0), thickness),
    }
}

fn hide(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(LABEL) {
        // Gone at once, so a new zone can take the name straight away.
        let _ = window.destroy();
    }
    *TARGET.lock().unwrap() = None;
}

/// Files that were let go of on the zone.
#[tauri::command]
pub async fn edge_send(state: State<'_, AppState>, paths: Vec<String>) -> Result<(), String> {
    let Some(device) = TARGET.lock().unwrap().clone() else { return Ok(()) };
    commands::send_paths_with(&state, vec![device], paths.into_iter().map(Into::into).collect(), TandemShareOrigin::Drag)
        .await
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_zone_sits_in_the_middle_of_the_edge_it_is_for() {
        // A 1920 by 1080 screen at the origin: 110 thick, 45 percent of the edge long but never over 520.
        assert_eq!(zone_rect((0, 0), (1920, 1080), 1.0, Edge::Right), (1810, 297, 110, 486));
        assert_eq!(zone_rect((0, 0), (1920, 1080), 1.0, Edge::Left), (0, 297, 110, 486));
        assert_eq!(zone_rect((0, 0), (1920, 1080), 1.0, Edge::Top), (700, 0, 520, 110));
        assert_eq!(zone_rect((0, 0), (1920, 1080), 1.0, Edge::Bottom), (700, 970, 520, 110));
    }

    #[test]
    fn the_zone_follows_a_screen_that_does_not_start_at_the_origin_and_a_high_density() {
        // A second screen to the left of the main one, at twice the density.
        let (x, y, w, h) = zone_rect((-2560, 0), (2560, 1440), 2.0, Edge::Right);
        assert_eq!((x, w), (-2560 + 2560 - 220, 220));
        // 45 percent of 1440 is 648, between the 520 and 1040 that the density allows.
        assert_eq!(h, 648);
        assert_eq!(y, (1440 - 648) / 2);
    }
}
