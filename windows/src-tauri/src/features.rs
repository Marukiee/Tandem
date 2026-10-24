//! What this computer does for the other devices and whether it is ready for it (see `Feature` in the core). The others grey out what
//! will not work, say why, and can ask this computer to open the page of its settings where it is turned on (`open`).

use std::time::Duration;

use serde_json::{json, Value};
use tandem_core::ffi::TandemFeature;
use tauri::{AppHandle, Manager};

use crate::state::AppState;

fn feature(id: &str, ready: bool, name: &str, why: &str, page: Option<&str>) -> TandemFeature {
    TandemFeature {
        id: id.into(),
        ready,
        name: name.into(),
        why: if ready { String::new() } else { why.into() },
        page: if ready { None } else { page.map(Into::into) },
    }
}

pub fn current(app: &AppHandle) -> Vec<TandemFeature> {
    let nl = crate::i18n::lang(app) == "nl";
    let t = |en: &'static str, dutch: &'static str| if nl { dutch } else { en };
    let mut list = Vec::new();
    list.push(feature(
        "control",
        crate::commands::input_blocked().is_none(),
        t("Control this computer", "Deze computer bedienen"),
        t("This desktop does not let apps use its keyboard and mouse", "Dit bureaublad laat apps het toetsenbord en de muis niet gebruiken"),
        None,
    ));
    #[cfg(feature = "screen-host")]
    {
        let on = crate::settings::get(app).screen_host;
        list.push(feature(
            "screen",
            on && crate::host::available(),
            t("See this computer's screen", "Het scherm van deze computer zien"),
            if on {
                t("This desktop does not let apps show its screen", "Dit bureaublad laat apps het scherm niet tonen")
            } else {
                t("Showing this screen is off in Tandem on this computer", "Het scherm tonen staat uit in Tandem op deze computer")
            },
            None,
        ));
    }
    list.push(feature(
        "microphone",
        crate::side::can_listen(),
        t("Find the side by sound", "De kant vinden met geluid"),
        t("This computer has no microphone that Tandem can use", "Deze computer heeft geen microfoon die Tandem kan gebruiken"),
        Some("sound"),
    ));
    list.push(feature(
        "wifi.share",
        crate::wifi::available(),
        t("Share this computer's Wi-Fi", "De wifi van deze computer delen"),
        if cfg!(windows) {
            t("This PC has no Wi-Fi", "Deze pc heeft geen wifi")
        } else {
            t("This computer has no Wi-Fi that NetworkManager runs", "Deze computer heeft geen wifi die NetworkManager regelt")
        },
        None,
    ));
    if cfg!(target_os = "linux") {
        list.push(feature(
            "bluetooth",
            crate::bluetooth::available(),
            t("Bluetooth link", "Bluetooth-verbinding"),
            t("Bluetooth is off or missing on this computer", "Bluetooth staat uit of ontbreekt op deze computer"),
            Some("bluetooth"),
        ));
    }
    list
}

/// Looks again every few seconds while Tandem runs; the core sends the list on only when it changed.
pub fn start(app: &AppHandle) {
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-features".into())
        .spawn(move || loop {
            if let Ok(engine) = app.state::<AppState>().engine() {
                tauri::async_runtime::block_on(engine.set_features(current(&app)));
            }
            std::thread::sleep(Duration::from_secs(6));
        })
        .ok();
}

/// Opens the page of the system settings for `page`.
pub fn open(page: &str) {
    #[cfg(windows)]
    {
        let uri = match page {
            "sound" => "ms-settings:sound",
            "microphone" => "ms-settings:privacy-microphone",
            "bluetooth" => "ms-settings:bluetooth",
            "notifications" => "ms-settings:notifications",
            _ => "ms-settings:",
        };
        let mut command = std::process::Command::new("cmd");
        command.args(["/C", "start", "", uri]);
        let _ = command.spawn();
    }
    #[cfg(not(windows))]
    {
        let panel = match page {
            "sound" | "microphone" => "sound",
            "bluetooth" => "bluetooth",
            "notifications" => "notifications",
            _ => "",
        };
        // GNOME's settings, or KDE's when that is the desktop.
        let mut gnome = std::process::Command::new("gnome-control-center");
        gnome.arg(panel);
        tandem_winsys::system_env(&mut gnome);
        if gnome.spawn().is_err() {
            let mut kde = std::process::Command::new("systemsettings");
            if !panel.is_empty() {
                kde.arg(if panel == "sound" { "kcm_pulseaudio" } else { panel });
            }
            tandem_winsys::system_env(&mut kde);
            let _ = kde.spawn();
        }
    }
}

fn to_json(f: &TandemFeature) -> Value {
    json!({ "id": f.id, "ready": f.ready, "name": f.name, "why": f.why, "page": f.page })
}

pub fn list_json(features: &[TandemFeature]) -> Value {
    Value::Array(features.iter().map(to_json).collect())
}

/// This computer's own list, for the page that shows what works.
#[tauri::command]
pub fn features_local(app: AppHandle) -> Value {
    list_json(&current(&app))
}

#[tauri::command]
pub fn features_open_here(page: String) {
    open(&page);
}

/// Asks another device to open a page of its settings.
#[tauri::command]
pub async fn features_open_on(app: AppHandle, id: String, page: String) -> Result<(), String> {
    let engine = app.state::<AppState>().engine()?;
    engine.open_settings_on(id, page).await.map_err(|e| e.to_string())
}
