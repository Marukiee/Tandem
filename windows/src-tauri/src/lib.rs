//! Tandem for Windows. A thin shell around the same core the phone and the Mac use: this crate starts the engine, turns
//! its events into notifications and the lists the interface shows, and hosts the interface in two windows, the main
//! one and the small panel above the tray icon.

mod clip;
mod commands;
mod engine;
mod events;
mod i18n;
mod input;
mod logfile;
mod media;
mod model;
mod power;
mod settings;
mod state;
mod tray;

use tauri::{Manager, WindowEvent};

pub fn run() {
    tauri::Builder::default()
        // A second start only brings the first one forward.
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            if args.iter().any(|a| a == "--panel") {
                tray::show_panel_in_corner(app);
            } else {
                tray::show_main(app);
            }
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]),
        ))
        .manage(state::AppState::default())
        .setup(|app| {
            let handle = app.handle().clone();
            logfile::init(&handle);
            settings::load(&handle);
            tray::build(&handle)?;
            media::start(&handle);
            power::start(handle.clone());
            engine::start(handle.clone());
            clip::start(handle.clone());
            // Started with Windows it waits in the tray; started by hand it shows its window.
            let args: Vec<String> = std::env::args().collect();
            if args.iter().any(|a| a == "--panel") {
                tray::show_panel_in_corner(&handle);
            } else if !args.iter().any(|a| a == "--minimized") {
                tray::show_main(&handle);
            }
            Ok(())
        })
        .on_window_event(|window, event| match event {
            WindowEvent::CloseRequested { api, .. } if window.label() == "main" => {
                let app = window.app_handle();
                if settings::get(app).close_to_tray {
                    api.prevent_close();
                    let _ = window.hide();
                } else {
                    tray::quit_app(app);
                }
            }
            WindowEvent::Focused(false) if window.label() == "panel" => tray::panel_lost_focus(window.app_handle()),
            _ => {}
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_state,
            commands::get_players,
            commands::create_pairing,
            commands::cancel_pairing,
            commands::pair,
            commands::remove_device,
            commands::rename_self,
            commands::set_device_settings,
            commands::ring,
            commands::send_paths,
            commands::pick_and_send,
            commands::send_clipboard,
            commands::send_text,
            commands::accept_offer,
            commands::decline_offer,
            commands::media_command,
            commands::notification_action,
            commands::clear_notifications,
            commands::set_settings,
            commands::set_autostart,
            commands::choose_download_dir,
            commands::open_path,
            commands::reveal_path,
            commands::open_downloads,
            commands::show_main,
            commands::hide_panel,
            commands::resize_panel,
            commands::open_logs,
            commands::quit_app,
        ])
        .run(tauri::generate_context!())
        .expect("Tandem could not start");
}
