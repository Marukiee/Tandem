//! Dragging files over the edge of the screen to the computer that is using the pointer of this one, the way a drag goes on to the next
//! screen. While another computer has its pointer here (see `pointer_share` and `input.rs`), the edge where that computer sits catches
//! files that are dragged into it, with nothing to see: on Linux a strip of the X server a few pixels wide, only there while the left
//! button is down (`tandem_winsys::edgecatch`), on Windows a see-through window of the same width. The moment a drag reaches it, what
//! is dragged is known, the drag here is called off, the pointer goes back with the button still down, and the files follow: the other
//! computer starts a drag of them under its pointer, so they are let go wherever the person wants.

use std::path::PathBuf;
use std::sync::Mutex;

use tandem_core::ffi::TandemShareOrigin;
use tandem_core::pointer_share::Edge;
use tauri::{AppHandle, Manager, State};
#[cfg(not(target_os = "linux"))]
use tauri::{DragDropEvent, PhysicalPosition, PhysicalSize, WebviewUrl, WebviewWindowBuilder, WindowEvent};

use crate::state::AppState;
use crate::{commands, input};

#[cfg(not(target_os = "linux"))]
const LABEL: &str = "edge-zone";
/// The device that the edge hands drags to, and the edge of this screen where it sits.
static TARGET: Mutex<Option<(String, Edge)>> = Mutex::new(None);
#[cfg(target_os = "linux")]
static CATCHER: Mutex<Option<tandem_winsys::edgecatch::Catcher>> = Mutex::new(None);

/// Called when another computer starts or stops using the pointer of this one.
pub fn changed(app: &AppHandle, now: Option<(String, Edge)>) {
    *TARGET.lock().unwrap() = now.clone();
    #[cfg(target_os = "linux")]
    if now.is_none() {
        CATCHER.lock().unwrap().take();
    }
    #[cfg(not(target_os = "linux"))]
    {
        let handle = app.clone();
        let _ = app.run_on_main_thread(move || match now {
            Some((_, edge)) => show(&handle, edge),
            None => hide(&handle),
        });
    }
    #[cfg(target_os = "linux")]
    let _ = app;
}

/// The left button of the shared mouse went down: a drag may start, so the edge gets ready (Linux; Windows has its window already).
pub fn left_down(app: &AppHandle) {
    #[cfg(target_os = "linux")]
    {
        let Some((_, edge)) = TARGET.lock().unwrap().clone() else { return };
        let side = match edge {
            Edge::Left => tandem_winsys::Side::Left,
            Edge::Right => tandem_winsys::Side::Right,
            Edge::Top => tandem_winsys::Side::Top,
            Edge::Bottom => tandem_winsys::Side::Bottom,
        };
        let handle = app.clone();
        // Pixels of the X server, which counts in the pixels of the screen even where the desktop scales: about two points.
        let caught = tandem_winsys::edgecatch::catch(side, 4, move |paths| hand_over(&handle, paths.into_iter().map(PathBuf::from).collect()));
        if caught.is_none() {
            log::info!("pointer: no strip at the edge to catch a drag (no X server here?)");
        }
        *CATCHER.lock().unwrap() = caught;
    }
    #[cfg(not(target_os = "linux"))]
    let _ = app;
}

/// The left button came up: the strip goes (Linux).
pub fn left_up() {
    #[cfg(target_os = "linux")]
    CATCHER.lock().unwrap().take();
}

/// A drag reached the edge: the pointer goes back with it and the files are sent, with the drag origin, to the computer that has the
/// pointer. That computer takes them without asking and starts a drag of them where its pointer is.
fn hand_over(app: &AppHandle, paths: Vec<PathBuf>) {
    let Some((device, _)) = TARGET.lock().unwrap().clone() else { return };
    let files: Vec<PathBuf> = paths.into_iter().filter(|p| p.is_file()).collect();
    if files.is_empty() {
        return;
    }
    log::info!("pointer: a drag of {} file(s) goes on to the computer that has the pointer", files.len());
    input::shared_hand_over();
    #[cfg(target_os = "linux")]
    CATCHER.lock().unwrap().take();
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let state = app.state::<AppState>();
        if let Err(error) = commands::send_paths_with(&state, vec![device], files, TandemShareOrigin::Drag).await {
            log::info!("pointer: the dragged files could not be sent: {error}");
        }
    });
}

#[cfg(not(target_os = "linux"))]
fn show(app: &AppHandle, edge: Edge) {
    hide(app);
    let Ok(Some(monitor)) = app.primary_monitor() else { return };
    let (x, y, width, height) = zone_rect((monitor.position().x, monitor.position().y), (monitor.size().width, monitor.size().height), monitor.scale_factor(), edge);
    let built = WebviewWindowBuilder::new(app, LABEL, WebviewUrl::App("edge.html".into()))
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
            // What is dragged is told as soon as the drag comes over the window, before it is let go.
            let handle = app.clone();
            window.on_window_event(move |event| {
                if let WindowEvent::DragDrop(DragDropEvent::Enter { paths, .. }) = event {
                    hand_over(&handle, paths.clone());
                }
            });
            let _ = window.show();
        }
        Err(error) => log::warn!("the edge could not be made to catch drags: {error}"),
    }
}

/// Where the catching window sits on a screen: (x, y, width, height) in pixels. A strip of two points along the whole edge.
#[cfg_attr(target_os = "linux", allow(dead_code))]
fn zone_rect(origin: (i32, i32), size: (u32, u32), scale: f64, edge: Edge) -> (i32, i32, u32, u32) {
    let thickness = (2.0 * scale).round().max(1.0) as u32;
    let (w, h) = (size.0 as i32, size.1 as i32);
    match edge {
        Edge::Left => (origin.0, origin.1, thickness, size.1),
        Edge::Right => (origin.0 + w - thickness as i32, origin.1, thickness, size.1),
        Edge::Top => (origin.0, origin.1, size.0, thickness),
        Edge::Bottom => (origin.0, origin.1 + h - thickness as i32, size.0, thickness),
    }
}

#[cfg(not(target_os = "linux"))]
fn hide(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(LABEL) {
        // Gone at once, so a new one can take the name straight away.
        let _ = window.destroy();
    }
}

/// Files that were let go of on the edge window before it heard of them (Windows).
#[tauri::command]
pub async fn edge_send(state: State<'_, AppState>, paths: Vec<String>) -> Result<(), String> {
    let Some((device, _)) = TARGET.lock().unwrap().clone() else { return Ok(()) };
    commands::send_paths_with(&state, vec![device], paths.into_iter().map(Into::into).collect(), TandemShareOrigin::Drag)
        .await
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_edge_window_is_a_thin_strip_along_the_whole_edge() {
        assert_eq!(zone_rect((0, 0), (1920, 1080), 1.0, Edge::Right), (1918, 0, 2, 1080));
        assert_eq!(zone_rect((0, 0), (1920, 1080), 1.0, Edge::Left), (0, 0, 2, 1080));
        assert_eq!(zone_rect((0, 0), (1920, 1080), 1.0, Edge::Top), (0, 0, 1920, 2));
        assert_eq!(zone_rect((0, 0), (1920, 1080), 1.0, Edge::Bottom), (0, 1078, 1920, 2));
    }

    #[test]
    fn the_strip_follows_a_screen_that_does_not_start_at_the_origin_and_a_high_density() {
        assert_eq!(zone_rect((-2560, 0), (2560, 1440), 2.0, Edge::Right), (-4, 0, 4, 1440));
    }
}
