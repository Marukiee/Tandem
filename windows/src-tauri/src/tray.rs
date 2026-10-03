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
}

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

fn toggle_panel(app: &AppHandle, click: PhysicalPosition<f64>) {
    let Some(panel) = app.get_webview_window("panel") else { return };
    if panel.is_visible().unwrap_or(false) {
        let _ = panel.hide();
        return;
    }
    let scale = panel.scale_factor().unwrap_or(1.0);
    let size = panel.outer_size().unwrap_or(PhysicalSize::new((352.0 * scale) as u32, (540.0 * scale) as u32));
    let (width, height) = (size.width as i32, size.height as i32);
    let margin = (12.0 * scale) as i32;
    let (mut x, mut y) = (click.x as i32 - width / 2, click.y as i32 - height - margin);
    if let Ok(Some(screen)) = app.monitor_from_point(click.x, click.y) {
        let (left, top) = (screen.position().x, screen.position().y);
        let (w, h) = (screen.size().width as i32, screen.size().height as i32);
        x = x.clamp(left + margin, (left + w - width - margin).max(left));
        // A taskbar at the top of the screen: the panel hangs under the icon instead.
        if click.y < (top + h / 2) as f64 {
            y = click.y as i32 + margin;
        }
        y = y.clamp(top + margin, (top + h - height - margin).max(top));
    }
    let _ = panel.set_position(PhysicalPosition::new(x, y));
    *app.state::<AppState>().panel_shown.lock().unwrap() = Some(Instant::now());
    log::info!("the panel opens at {x},{y}, {width} by {height}");
    let _ = panel.show();
    let _ = panel.set_focus();
}

/// Called when the panel loses focus. The click that opened it can still be settling, so a blur right after opening
/// is not a reason to close.
pub fn panel_lost_focus(app: &AppHandle) {
    let shown = *app.state::<AppState>().panel_shown.lock().unwrap();
    if shown.map(|t| t.elapsed() < Duration::from_millis(300)).unwrap_or(false) {
        return;
    }
    if let Some(panel) = app.get_webview_window("panel") {
        let _ = panel.hide();
    }
}

/// The panel asks for the height its content needs. The bottom edge stays where it is, since it sits on the taskbar.
pub fn resize_panel(app: &AppHandle, logical_height: f64) {
    let Some(panel) = app.get_webview_window("panel") else { return };
    let scale = panel.scale_factor().unwrap_or(1.0);
    let wanted = ((logical_height.clamp(180.0, 720.0)) * scale).round() as u32;
    let (Ok(size), Ok(position)) = (panel.outer_size(), panel.outer_position()) else { return };
    if size.height == wanted {
        return;
    }
    let bottom = position.y + size.height as i32;
    let _ = panel.set_size(Size::Physical(PhysicalSize::new(size.width, wanted)));
    let _ = panel.set_position(PhysicalPosition::new(position.x, bottom - wanted as i32));
}
