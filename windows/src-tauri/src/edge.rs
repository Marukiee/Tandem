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
    *TARGET.lock().unwrap() = Some(device.clone());
    let name = events::device_name(app, &device);
    let url = format!("edge.html?name={}", name.bytes().map(|b| if b.is_ascii_alphanumeric() { (b as char).to_string() } else { format!("%{b:02X}") }).collect::<String>());
    hide(app);
    let Ok(Some(monitor)) = app.primary_monitor() else { return };
    let (origin, size) = (monitor.position(), monitor.size());
    let scale = monitor.scale_factor();
    let thickness = (110.0 * scale) as u32;
    let long = |full: u32| ((f64::from(full) * 0.45).clamp(260.0 * scale, 520.0 * scale)) as u32;
    let (width, height, x, y) = match edge {
        Edge::Left => (thickness, long(size.height), origin.x, origin.y + (size.height as i32 - long(size.height) as i32) / 2),
        Edge::Right => (thickness, long(size.height), origin.x + size.width as i32 - thickness as i32, origin.y + (size.height as i32 - long(size.height) as i32) / 2),
        Edge::Top => (long(size.width), thickness, origin.x + (size.width as i32 - long(size.width) as i32) / 2, origin.y),
        Edge::Bottom => (long(size.width), thickness, origin.x + (size.width as i32 - long(size.width) as i32) / 2, origin.y + size.height as i32 - thickness as i32),
    };
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

fn hide(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(LABEL) {
        let _ = window.close();
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
