//! The icon by the clock and the small panel that opens from it. The panel is a window of its own with no frame: it
//! opens above the icon that was clicked, and closes when it loses focus.

use std::time::{Duration, Instant};

use tauri::image::Image;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, LogicalSize, Manager, PhysicalPosition, PhysicalSize, Size};

use crate::{i18n, state::AppState};

pub fn build(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", i18n::t(app, "open_tandem"), true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", i18n::t(app, "quit_tandem"), true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &quit])?;
    TrayIconBuilder::with_id("tandem")
        .icon(Image::from_bytes(include_bytes!("../icons/tray.png"))?)
        .tooltip("Tandem")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "quit" => quit_app(app),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, position, .. } = event {
                toggle_panel(tray.app_handle(), position);
            }
        })
        .build(app)?;
    Ok(())
}

/// Sizes the main window to the screen: on a small laptop screen the usual size would hang behind the taskbar. Done
/// once, before the window is first shown.
pub fn fit_main(app: &AppHandle) {
    let Some(window) = app.get_webview_window("main") else { return };
    let Ok(Some(screen)) = app.primary_monitor() else { return };
    let scale = screen.scale_factor();
    let (width, height) = (screen.size().width as f64 / scale, screen.size().height as f64 / scale);
    let wanted = LogicalSize::new((width * 0.9).clamp(860.0, 1040.0), (height * 0.82).clamp(560.0, 720.0));
    let _ = window.set_size(Size::Logical(wanted));
    let _ = window.center();
}

pub fn show_main(app: &AppHandle) {
    if let Some(panel) = app.get_webview_window("panel") {
        let _ = panel.hide();
    }
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
    describe_later(app, "main");
}

/// What Windows says about a window a moment after it was shown, in the log. It is how a window that is shown but cannot
/// be seen gets understood from far away.
#[cfg(windows)]
fn describe_later(app: &AppHandle, label: &'static str) {
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(1500));
        if let Some(window) = app.get_webview_window(label) {
            if let Ok(hwnd) = window.hwnd() {
                log::info!("window {label}: {}", tandem_winsys::describe(hwnd.0 as isize));
            }
        }
    });
}

#[cfg(not(windows))]
fn describe_later(_app: &AppHandle, _label: &'static str) {}

pub fn quit_app(app: &AppHandle) {
    // Close the connections politely, so the other devices see this one go instead of time out.
    if let Ok(engine) = app.state::<AppState>().engine() {
        tauri::async_runtime::block_on(engine.shutdown());
    }
    app.exit(0);
}

/// Opens the panel in the lower right corner of the main screen, where the icon is, for a start with `--panel`. It is
/// how the panel is looked at when nobody is there to click the icon. Nothing was clicked, so there is no click to
/// settle, and the panel stays until it is clicked away from or the icon is clicked.
pub fn show_panel_in_corner(app: &AppHandle) {
    let Ok(Some(screen)) = app.primary_monitor() else {
        log::warn!("the panel was asked for, but there is no main screen");
        return;
    };
    let (left, top) = (screen.position().x as f64, screen.position().y as f64);
    let (width, height) = (screen.size().width as f64, screen.size().height as f64);
    toggle_panel(app, PhysicalPosition::new(left + width - 120.0, top + height - 20.0));
    *app.state::<AppState>().panel_shown.lock().unwrap() = Instant::now().checked_add(Duration::from_secs(3600));
}

/// The width of the panel, in points.
const PANEL_WIDTH: f64 = 352.0;
/// What its height is until its content has said what it needs, and the least it is ever.
const PANEL_HEIGHT: f64 = 400.0;
const PANEL_MIN: f64 = 100.0;

/// How far the visible part of a window lies inside the rectangle Windows gives it: left, top, right, bottom. A window
/// without a frame still has an invisible edge to grab, and `set_size` sets the visible part, not the whole.
struct Insets {
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
}

fn insets(window: &tauri::WebviewWindow) -> Insets {
    let scale = window.scale_factor().unwrap_or(1.0);
    let measured = (|| {
        let (outer, inner) = (window.outer_size().ok()?, window.inner_size().ok()?);
        let (outer_at, inner_at) = (window.outer_position().ok()?, window.inner_position().ok()?);
        let left = inner_at.x - outer_at.x;
        let top = inner_at.y - outer_at.y;
        let right = outer.width as i32 - inner.width as i32 - left;
        let bottom = outer.height as i32 - inner.height as i32 - top;
        (left >= 0 && top >= 0 && right >= 0 && bottom >= 0).then_some(Insets { left, top, right, bottom })
    })();
    measured.unwrap_or(Insets {
        left: (8.0 * scale) as i32,
        top: (1.0 * scale) as i32,
        right: (8.0 * scale) as i32,
        bottom: (8.0 * scale) as i32,
    })
}

fn toggle_panel(app: &AppHandle, click: PhysicalPosition<f64>) {
    let Some(panel) = app.get_webview_window("panel") else { return };
    if panel.is_visible().unwrap_or(false) {
        log::info!("the panel closes");
        let _ = panel.hide();
        return;
    }
    let scale = panel.scale_factor().unwrap_or(1.0);
    let edge = insets(&panel);
    // What is placed is the visible part, from the height its content asked for.
    let wanted = app.state::<AppState>().panel_height.lock().unwrap().unwrap_or(PANEL_HEIGHT);
    let (width, height) = ((PANEL_WIDTH * scale).round() as i32, (wanted.clamp(PANEL_MIN, 720.0) * scale).round() as i32);
    let margin = (10.0 * scale) as i32;
    let (cx, cy) = (click.x as i32, click.y as i32);
    let (mut x, mut y) = (cx - width / 2, cy - height / 2);
    if let Ok(Some(screen)) = app.monitor_from_point(click.x, click.y) {
        // The work area is the screen without the taskbar. The panel goes against the side the taskbar is on, which is
        // the side the click came from.
        let work = screen.work_area();
        let (left, top) = (work.position.x, work.position.y);
        let (right, bottom) = (left + work.size.width as i32, top + work.size.height as i32);
        if cy >= bottom {
            y = bottom - height - margin;
        } else if cy < top {
            y = top + margin;
        } else if cx >= right {
            x = right - width - margin;
        } else if cx < left {
            x = left + margin;
        }
        x = x.clamp(left + margin, (right - width - margin).max(left));
        y = y.clamp(top + margin, (bottom - height - margin).max(top));
    }
    let _ = panel.set_size(Size::Physical(PhysicalSize::new(width as u32, height as u32)));
    let _ = panel.set_position(PhysicalPosition::new(x - edge.left, y - edge.top));
    *app.state::<AppState>().panel_shown.lock().unwrap() = Some(Instant::now());
    let shown = panel.show();
    let focused = panel.set_focus();
    log::info!(
        "the panel opens at {x},{y}, {width} by {height} (scale {scale}, edge {},{},{},{}): show {shown:?}, focus {focused:?}",
        edge.left,
        edge.top,
        edge.right,
        edge.bottom
    );
    describe_later(app, "panel");
}

/// Called when the panel loses focus. The click that opened it can still be settling, so a blur right after opening
/// is not a reason to close.
pub fn panel_lost_focus(app: &AppHandle) {
    let shown = *app.state::<AppState>().panel_shown.lock().unwrap();
    let settling = shown.map(|t| t.elapsed() < Duration::from_millis(300)).unwrap_or(false);
    log::info!("the panel lost focus{}", if settling { " (still settling, so it stays)" } else { " and closes" });
    if settling {
        return;
    }
    if let Some(panel) = app.get_webview_window("panel") {
        let _ = panel.hide();
    }
}

/// The panel asks for the height its content needs. While it is open the bottom edge stays where it is, since it sits
/// on the taskbar; while it is closed the height is only kept for the next time.
///
/// Everything is measured on the visible part of the window. Setting the size of the visible part from the size of the
/// whole made the window a little bigger at every call, and the content, seeing its window grow, asked again.
pub fn resize_panel(app: &AppHandle, logical_height: f64) {
    *app.state::<AppState>().panel_height.lock().unwrap() = Some(logical_height);
    let Some(panel) = app.get_webview_window("panel") else { return };
    if !panel.is_visible().unwrap_or(false) {
        return;
    }
    let scale = panel.scale_factor().unwrap_or(1.0);
    let wanted = ((logical_height.clamp(PANEL_MIN, 720.0)) * scale).round() as i32;
    let (Ok(inner), Ok(inner_at)) = (panel.inner_size(), panel.inner_position()) else { return };
    if (inner.height as i32 - wanted).abs() <= 1 {
        return;
    }
    let edge = insets(&panel);
    let bottom = inner_at.y + inner.height as i32;
    let _ = panel.set_size(Size::Physical(PhysicalSize::new(inner.width, wanted as u32)));
    let _ = panel.set_position(PhysicalPosition::new(inner_at.x - edge.left, bottom - wanted - edge.top));
}
