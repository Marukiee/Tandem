//! What this computer does for the other devices and whether it is ready for it (see `Feature` in the core). The others grey out what
//! will not work, say why, and can ask this computer to open the page of its settings where it is turned on (`open`).

use std::time::Duration;

use serde_json::{json, Value};
use tandem_core::ffi::TandemFeature;
use tauri::{AppHandle, Emitter, Manager};

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
            (!on).then_some("switch:screen"),
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
        "ring",
        crate::find::can_ring(app),
        t("Play a sound to be found", "Een geluid afspelen om gevonden te worden"),
        if cfg!(target_os = "linux") {
            t("No program to play sounds was found (pw-play, paplay or canberra-gtk-play)", "Er is geen programma gevonden dat geluid afspeelt (pw-play, paplay of canberra-gtk-play)")
        } else {
            t("The sounds of Windows are missing", "De geluiden van Windows ontbreken")
        },
        Some("sound"),
    ));
    // A computer without Wi-Fi (or, on Linux, without NetworkManager) has nothing to share and no setting that would change that: it
    // is left out rather than shown on the other devices as something missing.
    if crate::wifi::available() {
        list.push(feature("wifi.share", true, t("Share this computer's Wi-Fi", "De wifi van deze computer delen"), "", None));
    }
    // Codes from the texts of a phone, copied here: the notice that says so needs nothing but the switch.
    let codes = crate::settings::get(app).copy_codes;
    list.push(feature(
        "codes",
        codes,
        t("Codes from texts copied here", "Codes uit sms'jes hier gekopieerd"),
        t("Copy codes is off in Tandem on this computer", "Codes kopi\u{eb}ren staat uit in Tandem op deze computer"),
        (!codes).then_some("switch:codes"),
    ));
    let focus = tandem_winsys::focus::abilities();
    list.push(feature(
        "focus",
        focus.read && crate::focus::on(app),
        t("Focus on all your devices", "Focus op al je apparaten"),
        if !focus.read {
            t("This desktop has no Do Not Disturb that Tandem can use", "Dit bureaublad heeft geen Niet storen dat Tandem kan gebruiken")
        } else {
            t("Share Focus is off in Tandem on this computer", "Focus delen staat uit in Tandem op deze computer")
        },
        (focus.read && !crate::focus::on(app)).then_some("switch:focus"),
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
                let features = current(&app);
                remind_if_due(&app, &features);
                tauri::async_runtime::block_on(engine.set_features(features));
            }
            std::thread::sleep(Duration::from_secs(6));
        })
        .ok();
}

/// What was left for later in the tour comes back once, when its day has come and only while it is still not ready: one notice that opens
/// the tour. What got ready in the meantime is dropped from the list.
fn remind_if_due(app: &AppHandle, features: &[TandemFeature]) {
    let settings = crate::settings::get(app);
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
    if settings.tour_remind_at == 0 || now < settings.tour_remind_at {
        return;
    }
    let waiting: Vec<&TandemFeature> = features.iter().filter(|f| !f.ready && settings.tour_later.contains(&f.id)).collect();
    let still: Vec<String> = waiting.iter().map(|f| f.id.clone()).collect();
    crate::settings::update(app, |s| {
        s.tour_remind_at = 0;
        s.tour_later = still;
    });
    if waiting.is_empty() {
        return;
    }
    let nl = crate::i18n::lang(app) == "nl";
    let title = if nl { "Tandem: nog in te stellen" } else { "Tandem: still to set up" };
    let names = waiting.iter().map(|f| f.name.as_str()).collect::<Vec<_>>().join(", ");
    let body = if waiting.len() == 1 {
        names
    } else if nl {
        format!("{} dingen wachten nog: {names}", waiting.len())
    } else {
        format!("{} things are still waiting: {names}", waiting.len())
    };
    #[cfg(target_os = "linux")]
    {
        let handle = app.clone();
        let open = if nl { "Openen" } else { "Open" };
        let told = tandem_winsys::notify::tell(title, &body, &[("default", ""), ("open", open)], move |_, _| open_tour(&handle));
        if told.is_some() {
            return;
        }
    }
    crate::events::toast(app, title, &body);
}

/// The main window, on the page of the tour. A main window that is not there yet asks for it when it starts (`tour_pending`).
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub fn open_tour(app: &AppHandle) {
    if app.get_webview_window("main").is_some() {
        let _ = app.emit_to("main", "open-page", "tour");
    } else {
        TOUR_PENDING.store(true, std::sync::atomic::Ordering::SeqCst);
    }
    crate::tray::show_main(app);
}

static TOUR_PENDING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Whether the main window is to open on the tour, once.
#[tauri::command]
pub fn tour_pending() -> bool {
    TOUR_PENDING.swap(false, std::sync::atomic::Ordering::SeqCst)
}

/// Opens the page of the system settings for `page`.
/// A page that is a switch of Tandem itself and not a setting of the system: another device turns it on from afar instead of opening a
/// page here. A permission of the system can only be given here, by the person. True when it was one.
pub fn turn_on(app: &AppHandle, page: &str) -> bool {
    let Some(feature) = page.strip_prefix("switch:") else { return false };
    match feature {
        "screen" => crate::settings::update(app, |s| s.screen_host = true),
        "focus" => crate::settings::update(app, |s| s.focus_share = Some(true)),
        "codes" => crate::settings::update(app, |s| s.copy_codes = true),
        _ => {}
    }
    true
}

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
pub fn features_open_here(app: AppHandle, page: String) {
    if turn_on(&app, &page) {
        return;
    }
    open(&page);
}

/// Asks another device to open a page of its settings.
#[tauri::command]
pub async fn features_open_on(app: AppHandle, id: String, page: String) -> Result<(), String> {
    let engine = app.state::<AppState>().engine()?;
    engine.open_settings_on(id, page).await.map_err(|e| e.to_string())
}
