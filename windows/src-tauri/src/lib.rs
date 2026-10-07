//! Tandem for Windows. A thin shell around the same core the phone and the Mac use: this crate starts the engine, turns
//! its events into notifications and the lists the interface shows, and hosts the interface in two windows, the main
//! one and the small panel above the tray icon.

mod browse;
mod capture;
mod quickshare;
mod clip;
mod commands;
mod engine;
mod events;
mod history;
mod i18n;
mod input;
mod live;
mod logfile;
mod media;
mod model;
mod names;
mod power;
mod settings;
mod ssh;
mod state;
mod tray;
mod update;
#[cfg(feature = "native-video")]
mod video;

use tauri::{Manager, RunEvent, WindowEvent};

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
            update::start(handle.clone());
            // When the pointer of another computer runs into the edge it came in by, that computer is told.
            let leave_app = handle.clone();
            input::on_leave(move |device, along| {
                if let Ok(engine) = leave_app.state::<state::AppState>().engine() {
                    tauri::async_runtime::spawn(async move {
                        let _ = engine.send_pointer_share(device, tandem_core::ffi::TandemPointerShare::Leave { along }).await;
                    });
                }
            });
            engine::start(handle.clone());
            capture::configure(&handle);
            quickshare::configure(&handle);
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
                // Closing the window to the tray really closes it, which gives back what its web view held; the next
                // time it is wanted a new one is made. Tandem itself runs on.
                if !settings::get(app).close_to_tray {
                    api.prevent_close();
                    tray::quit_app(app);
                }
            }
            WindowEvent::Focused(false) if window.label() == "panel" => tray::panel_lost_focus(window.app_handle()),
            _ => {}
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_state,
            quickshare::qs_state,
            quickshare::qs_respond,
            quickshare::qs_dismiss,
            quickshare::qs_open_link,
            quickshare::qs_pick_and_send,
            quickshare::qs_send_paths,
            quickshare::qs_send_clipboard,
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
            commands::check_update,
            commands::open_url,
            commands::install_update,
            commands::dismiss_update,
            commands::quit_app,
            browse::fs_roots,
            browse::fs_list,
            browse::fs_get,
            history::clip_history,
            history::clip_history_search,
            history::clip_history_pin,
            history::clip_history_remove,
            history::clip_history_clear,
            history::clip_history_copy,
            live::live_start,
            live::live_attach,
            live::live_keyframe,
            live::live_stop,
            live::live_pin,
            ssh::ssh_probe,
            ssh::ssh_open,
            ssh::ssh_start,
            ssh::ssh_write,
            ssh::ssh_resize,
            ssh::ssh_close,
            ssh::ssh_info,
        ])
        .build(tauri::generate_context!())
        .expect("Tandem could not start")
        .run(|_app, event| {
            // The last window going away is not a reason to stop: Tandem lives in the tray. Quitting is its own thing.
            if let RunEvent::ExitRequested { api, code: None, .. } = event {
                api.prevent_exit();
            }
        });
}
