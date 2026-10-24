//! The icon by the clock and the small panel that opens from it, and the windows of the app.
//!
//! The windows are made when they are needed and put away when they are not. A web view that is not on the screen still
//! costs tens of megabytes and keeps a few processes of Windows alive, and a tray app sits there all day: with both
//! windows away, Tandem is its engine and nothing else. The panel stays for a few minutes after it was last used, so
//! the clicks of one moment find it ready.
//!
//! The panel is a window of its own with no frame: it opens above the icon that was clicked, against the taskbar, and
//! closes when it loses focus.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};

use tauri::image::Image;
use tauri::menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem, Submenu};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::webview::PageLoadEvent;
use tauri::{AppHandle, LogicalSize, Manager, PhysicalPosition, PhysicalSize, Size, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

use crate::{i18n, state::AppState};

/// How long the panel is kept after it was closed, in case it is wanted again. TANDEM_PANEL_KEPT_SECONDS changes it, which
/// is how a test does not have to wait for minutes.
fn panel_kept() -> Duration {
    std::env::var("TANDEM_PANEL_KEPT_SECONDS").ok().and_then(|v| v.parse().ok()).map(Duration::from_secs).unwrap_or(Duration::from_secs(180))
}

/// The width of the panel, in points.
const PANEL_WIDTH: f64 = 352.0;
/// What its height is until its content has said what it needs, and the least it is ever.
const PANEL_HEIGHT: f64 = 400.0;
const PANEL_MIN: f64 = 100.0;

/// Set for a panel that was asked for by name (`--panel`) and has no click to settle: it stays until it is clicked away.
static PINNED: AtomicBool = AtomicBool::new(false);
/// Counts the times the panel was closed, so a timer can tell whether the panel was used again before it ran out.
static PANEL_CLOSED: AtomicU64 = AtomicU64::new(0);

pub fn build(app: &AppHandle) -> tauri::Result<()> {
    let menu = menu(app)?;
    TrayIconBuilder::with_id("tandem")
        .icon(Image::from_bytes(include_bytes!("../icons/tray.png"))?)
        .tooltip("Tandem")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| {
            let id = event.id.as_ref();
            match id {
                "open" => show_main(app),
                "quit" => quit_app(app),
                "quickshare" => crate::quickshare::toggle(app),
                "share-mouse" => {
                    crate::settings::update(app, |s| s.pointer_share = !s.pointer_share);
                    if crate::settings::get(app).pointer_share {
                        crate::side::mouse_turned_on(app);
                    }
                    crate::capture::configure(app);
                    rebuild(app);
                }
                _ => {
                    if let Some(device) = id.strip_prefix("extend:") {
                        extend(app, device);
                    } else if let Some(device) = id.strip_prefix("ring:") {
                        ring(app, device);
                    }
                }
            }
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, position, .. } = event {
                toggle_panel(tray.app_handle(), position);
            }
        })
        .build(app)?;
    Ok(())
}

// ---- The menu of the icon ----------------------------------------------------------------

/// The devices this computer shows an extra screen of itself on now (see `host.rs`).
static EXTENDING: std::sync::Mutex<Vec<String>> = std::sync::Mutex::new(Vec::new());
/// What the menu was last made from, so it is only made again when something in it changed.
static MADE_FROM: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

/// The same quick buttons as the Control Center of the Mac: Quick Share, the shared mouse, extending this screen to a device, and
/// ringing a phone. Windows and GNOME have no place for the quick settings of an app, so they are here.
fn menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    let settings = crate::settings::get(app);
    let devices = app.state::<AppState>().data.lock().unwrap().devices.clone();
    let online = |d: &&serde_json::Value| d["online"].as_bool().unwrap_or(false);
    let has = |d: &serde_json::Value, cap: &str| d["caps"].as_array().is_some_and(|c| c.iter().any(|x| x == cap));
    let name = |d: &serde_json::Value| d["name"].as_str().unwrap_or("?").to_string();
    let id = |d: &serde_json::Value| d["id"].as_str().unwrap_or("").to_string();

    let open = MenuItem::with_id(app, "open", i18n::t(app, "open_tandem"), true, None::<&str>)?;
    let quick_share = crate::quickshare::tray_item(app)?;
    let mouse = CheckMenuItem::with_id(app, "share-mouse", i18n::t(app, "tray_shared_mouse"), !settings.layout.is_empty(), settings.pointer_share, None::<&str>)?;

    let extending = EXTENDING.lock().unwrap().clone();
    let extend = Submenu::with_id(app, "extend", i18n::t(app, "tray_extend"), true)?;
    let mut any = false;
    for device in devices.iter().filter(online).filter(|d| has(d, "screen.extend.view")) {
        let on = extending.contains(&id(device));
        extend.append(&CheckMenuItem::with_id(app, format!("extend:{}", id(device)), name(device), true, on, None::<&str>)?)?;
        any = true;
    }
    if !any {
        extend.append(&MenuItem::with_id(app, "extend-none", i18n::t(app, "tray_none"), false, None::<&str>)?)?;
    }

    let find = Submenu::with_id(app, "find", i18n::t(app, "tray_find_phone"), true)?;
    let mut phones = false;
    for device in devices.iter().filter(online).filter(|d| d["platform"] == "android") {
        find.append(&MenuItem::with_id(app, format!("ring:{}", id(device)), name(device), true, None::<&str>)?)?;
        phones = true;
    }
    if !phones {
        find.append(&MenuItem::with_id(app, "find-none", i18n::t(app, "tray_none"), false, None::<&str>)?)?;
    }

    let quit = MenuItem::with_id(app, "quit", i18n::t(app, "quit_tandem"), true, None::<&str>)?;
    let line = PredefinedMenuItem::separator(app)?;
    let line2 = PredefinedMenuItem::separator(app)?;
    Menu::with_items(app, &[&open, &line, &quick_share, &mouse, &extend, &find, &line2, &quit])
}

/// Makes the menu again when the devices, the shared mouse or an extra screen changed.
pub fn rebuild(app: &AppHandle) {
    let settings = crate::settings::get(app);
    let devices = app.state::<AppState>().data.lock().unwrap().devices.clone();
    let key = format!(
        "{}{}{:?}{}",
        settings.pointer_share,
        settings.layout.is_empty(),
        EXTENDING.lock().unwrap(),
        devices.iter().map(|d| format!("{}{}{}{}", d["id"], d["name"], d["online"], d["caps"])).collect::<String>()
    );
    {
        let mut made = MADE_FROM.lock().unwrap();
        if *made == key {
            return;
        }
        *made = key;
    }
    let (Some(tray), Ok(menu)) = (app.tray_by_id("tandem"), menu(app)) else { return };
    let _ = tray.set_menu(Some(menu));
}

/// Which devices this computer shows an extra screen on now.
pub fn set_extending(app: &AppHandle, peers: Vec<String>) {
    *EXTENDING.lock().unwrap() = peers;
    rebuild(app);
}

fn extend(app: &AppHandle, device: &str) {
    let device = device.to_string();
    if EXTENDING.lock().unwrap().contains(&device) {
        crate::live::display_offer_stop(app.clone(), device);
    } else if let Err(error) = crate::live::display_offer(app.clone(), device) {
        log::info!("extending to that device did not start: {error:?}");
    }
}

fn ring(app: &AppHandle, device: &str) {
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let device = device.to_string();
    tauri::async_runtime::spawn(async move {
        let _ = engine.ring(device, true).await;
    });
}

// ---- The main window -------------------------------------------------------------------

/// Brings the window forward, making it first when it is not there.
pub fn show_main(app: &AppHandle) {
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || {
        hide_panel(&handle);
        match handle.get_webview_window("main") {
            Some(window) => {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
            None => create_main(&handle),
        }
        describe_later(&handle, "main");
    });
}

/// Made hidden and shown when its page is there, so what appears is the window and not an empty box that fills in.
fn create_main(app: &AppHandle) {
    let shown = app.clone();
    let built = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
        .title("Tandem")
        .inner_size(1040.0, 720.0)
        .min_inner_size(860.0, 560.0)
        // Linux draws no frame of its own: the page does (see ui/js/chrome.js), in the style of the rest of the app.
        .decorations(cfg!(windows))
        .visible(false)
        .on_page_load(move |_, payload| {
            if matches!(payload.event(), PageLoadEvent::Finished) {
                if let Some(window) = shown.get_webview_window("main") {
                    if !window.is_visible().unwrap_or(false) {
                        fit_main(&shown, &window);
                        let _ = window.show();
                        let _ = window.set_focus();
                        log::info!("the main window shows");
                    }
                }
            }
        })
        .build();
    match built {
        Ok(_) => log::info!("the main window is made"),
        Err(error) => log::warn!("the main window could not be made: {error}"),
    }
}

/// Sizes the main window to the screen: on a small laptop screen the usual size would hang behind the taskbar. Done
/// once, before the window is first shown.
fn fit_main(app: &AppHandle, window: &WebviewWindow) {
    let Ok(Some(screen)) = app.primary_monitor() else { return };
    let scale = screen.scale_factor();
    let (width, height) = (screen.size().width as f64 / scale, screen.size().height as f64 / scale);
    let wanted = LogicalSize::new((width * 0.9).clamp(860.0, 1040.0), (height * 0.82).clamp(560.0, 720.0));
    let _ = window.set_size(Size::Logical(wanted));
    let _ = window.center();
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

// ---- The panel -------------------------------------------------------------------------

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
    PINNED.store(true, Ordering::SeqCst);
    toggle_panel(app, PhysicalPosition::new(left + width - 120.0, top + height - 20.0));
}

fn toggle_panel(app: &AppHandle, click: PhysicalPosition<f64>) {
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || match handle.get_webview_window("panel") {
        Some(panel) if panel.is_visible().unwrap_or(false) => {
            log::info!("the panel closes");
            hide_panel(&handle);
        }
        Some(panel) => place_and_show(&handle, &panel, click),
        None => create_panel(&handle, click),
    });
}

/// Made hidden. It is shown when its content has said how high it wants to be (see [`resize_panel`]), so it opens at the
/// right size instead of jumping to it.
fn create_panel(app: &AppHandle, click: PhysicalPosition<f64>) {
    *app.state::<AppState>().pending_panel.lock().unwrap() = Some(click);
    let late = app.clone();
    let built = WebviewWindowBuilder::new(app, "panel", WebviewUrl::App("panel.html".into()))
        .title("Tandem")
        .inner_size(PANEL_WIDTH, 540.0)
        .visible(false)
        .decorations(false)
        .resizable(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .focused(false)
        .shadow(true)
        .on_page_load(move |_, payload| {
            if matches!(payload.event(), PageLoadEvent::Finished) {
                // Should the content never say how high it is, the panel opens anyway.
                let late = late.clone();
                std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_millis(1500));
                    let handle = late.clone();
                    let _ = late.run_on_main_thread(move || show_pending_panel(&handle));
                });
            }
        })
        .build();
    match built {
        Ok(_) => log::info!("the panel is made"),
        Err(error) => {
            log::warn!("the panel could not be made: {error}");
            *app.state::<AppState>().pending_panel.lock().unwrap() = None;
        }
    }
}

fn show_pending_panel(app: &AppHandle) {
    let click = app.state::<AppState>().pending_panel.lock().unwrap().take();
    if let (Some(click), Some(panel)) = (click, app.get_webview_window("panel")) {
        if !panel.is_visible().unwrap_or(false) {
            place_and_show(app, &panel, click);
        }
    }
}

/// How far the visible part of a window lies inside the rectangle Windows gives it: left, top, right, bottom. A window
/// without a frame still has an invisible edge to grab, and `set_size` sets the visible part, not the whole.
struct Insets {
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
}

fn insets(window: &WebviewWindow) -> Insets {
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

fn place_and_show(app: &AppHandle, panel: &WebviewWindow, click: PhysicalPosition<f64>) {
    // Whatever was waiting to open is opening now.
    *app.state::<AppState>().pending_panel.lock().unwrap() = None;
    // And it will not be put away while somebody is looking at it.
    PANEL_CLOSED.fetch_add(1, Ordering::SeqCst);
    let scale = panel.scale_factor().unwrap_or(1.0);
    let edge = insets(panel);
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
    if PINNED.load(Ordering::SeqCst) {
        return;
    }
    let shown = *app.state::<AppState>().panel_shown.lock().unwrap();
    let settling = shown.map(|t| t.elapsed() < Duration::from_millis(300)).unwrap_or(false);
    log::info!("the panel lost focus{}", if settling { " (still settling, so it stays)" } else { " and closes" });
    if settling {
        return;
    }
    hide_panel(app);
}

/// Closes the panel, and puts it away if nobody opens it again soon.
fn hide_panel(app: &AppHandle) {
    let Some(panel) = app.get_webview_window("panel") else { return };
    let _ = panel.hide();
    PINNED.store(false, Ordering::SeqCst);
    let ticket = PANEL_CLOSED.fetch_add(1, Ordering::SeqCst) + 1;
    let kept = panel_kept();
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(kept);
        if PANEL_CLOSED.load(Ordering::SeqCst) != ticket {
            return;
        }
        let handle = app.clone();
        let _ = app.run_on_main_thread(move || {
            if let Some(panel) = handle.get_webview_window("panel") {
                if !panel.is_visible().unwrap_or(false) {
                    let _ = panel.destroy();
                    log::info!("the panel is put away");
                }
            }
        });
    });
}

/// The panel asks for the height its content needs. While it is open the bottom edge stays where it is, since it sits
/// on the taskbar; while it is closed the height is only kept for the next time. A panel that was just made opens now,
/// since it knows how high it is.
///
/// Everything is measured on the visible part of the window. Setting the size of the visible part from the size of the
/// whole made the window a little bigger at every call, and the content, seeing its window grow, asked again.
pub fn resize_panel(app: &AppHandle, logical_height: f64) {
    *app.state::<AppState>().panel_height.lock().unwrap() = Some(logical_height);
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || {
        let Some(panel) = handle.get_webview_window("panel") else { return };
        if !panel.is_visible().unwrap_or(false) {
            show_pending_panel(&handle);
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
    });
}
