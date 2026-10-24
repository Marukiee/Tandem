//! Handing the Wi-Fi of this computer to another device of the circle, and joining the Wi-Fi another computer hands over (see
//! `wifi` in the core). The core tells the others which network this computer is on. A device that is not on it asks, and the person
//! here decides in the card in the corner (on Linux also a notification with the buttons). The password is only in memory, on its way.

use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use tandem_core::ffi::{TandemWifi, TandemWifiNetwork, TandemWifiRefusal, TandemWifiSecurity};
use tandem_winsys::wifi::{self, Network, Security};
use tauri::{AppHandle, Emitter, Manager};

use crate::ask::{Answer, Question};
use crate::events::{device_name, toast};
use crate::i18n;
use crate::state::AppState;

/// Whether this computer has Wi-Fi to share and join. Looked at once: the tools and the hardware do not come and go while Tandem runs.
pub fn available() -> bool {
    static AVAILABLE: OnceLock<bool> = OnceLock::new();
    *AVAILABLE.get_or_init(wifi::available)
}

fn to_core(network: &Network) -> TandemWifiNetwork {
    let security = match network.security {
        Security::Open => TandemWifiSecurity::Open,
        Security::Wpa2 => TandemWifiSecurity::Wpa2,
        Security::Wpa3 => TandemWifiSecurity::Wpa3,
        Security::Enterprise => TandemWifiSecurity::Enterprise,
        Security::Other => TandemWifiSecurity::Other,
    };
    TandemWifiNetwork { ssid: network.ssid.clone(), security, hidden: network.hidden }
}

fn from_core(network: &TandemWifiNetwork) -> Network {
    let security = match network.security {
        TandemWifiSecurity::Open => Security::Open,
        TandemWifiSecurity::Wpa2 => Security::Wpa2,
        TandemWifiSecurity::Wpa3 => Security::Wpa3,
        TandemWifiSecurity::Enterprise => Security::Enterprise,
        TandemWifiSecurity::Other => Security::Other,
    };
    Network { ssid: network.ssid.clone(), security, hidden: network.hidden, profile: network.ssid.clone() }
}

fn shareable(security: Security) -> bool {
    matches!(security, Security::Open | Security::Wpa2 | Security::Wpa3)
}

/// The name of the network this computer is on, as last read, for the page: "Share Wi-Fi" only shows where it gives the device something.
static HERE: Mutex<Option<String>> = Mutex::new(None);

/// Says which network this computer is on, and again whenever that changes. The core passes on only a change.
pub fn start(app: &AppHandle) {
    if !available() {
        return;
    }
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-wifi".into())
        .spawn(move || loop {
            let current = wifi::current();
            let ssid = current.as_ref().map(|n| n.ssid.clone());
            let changed = {
                let mut here = HERE.lock().unwrap();
                let changed = *here != ssid;
                *here = ssid.clone();
                changed
            };
            if changed {
                let _ = app.emit("wifi-here", ssid);
            }
            if let Ok(engine) = app.state::<AppState>().engine() {
                tauri::async_runtime::block_on(engine.set_wifi_network(current.map(|n| to_core(&n))));
            }
            std::thread::sleep(Duration::from_secs(10));
        })
        .ok();
}

#[tauri::command]
pub fn wifi_here() -> Option<String> {
    HERE.lock().unwrap().clone()
}

fn reply(app: &AppHandle, to: &str, message: TandemWifi) -> bool {
    let Ok(engine) = app.state::<AppState>().engine() else { return false };
    tauri::async_runtime::block_on(engine.send_wifi(to.to_string(), message)).is_ok()
}

pub fn handle(app: &AppHandle, from: String, message: TandemWifi) {
    let app = app.clone();
    match message {
        // Both wait for the person, so off the thread of the events.
        TandemWifi::Ask { ssid } => {
            std::thread::spawn(move || asked(&app, &from, &ssid));
        }
        TandemWifi::Share { network, password } => {
            std::thread::spawn(move || offered(&app, &from, &network, &password));
        }
        // This computer does not ask others for their Wi-Fi: it offers its own, and joins what it is handed.
        TandemWifi::Network { .. } | TandemWifi::Refuse { .. } => {}
    }
}

/// A device that is not on the network of this computer asks for its password. One question for each device at a time.
fn asked(app: &AppHandle, from: &str, ssid: &str) {
    static ASKING: Mutex<Vec<String>> = Mutex::new(Vec::new());
    let Some(network) = wifi::current().filter(|n| n.ssid == ssid) else {
        reply(app, from, TandemWifi::Refuse { ssid: ssid.to_string(), why: TandemWifiRefusal::NotOnIt });
        return;
    };
    if !shareable(network.security) {
        reply(app, from, TandemWifi::Refuse { ssid: ssid.to_string(), why: TandemWifiRefusal::CannotShare });
        return;
    }
    {
        let mut asking = ASKING.lock().unwrap();
        if asking.iter().any(|id| id == from) {
            return;
        }
        asking.push(from.to_string());
    }
    let name = device_name(app, from);
    let question = Question {
        id: 0,
        title: i18n::t2(app, "wifi_ask_title", ssid, &name),
        body: i18n::t1(app, "wifi_ask_body", &name),
        allow: i18n::t(app, "wifi_share"),
        always: String::new(),
        deny: i18n::t(app, "wifi_dont_share"),
        control: false,
    };
    let answer = crate::ask::ask(app, question);
    ASKING.lock().unwrap().retain(|id| id != from);
    if answer == Answer::Deny {
        reply(app, from, TandemWifi::Refuse { ssid: ssid.to_string(), why: TandemWifiRefusal::Declined });
        return;
    }
    match wifi::password(&network) {
        Some(password) => {
            if reply(app, from, TandemWifi::Share { network: to_core(&network), password }) {
                toast(app, &i18n::t1(app, "wifi_sent", &name), &network.ssid);
            }
        }
        None => {
            reply(app, from, TandemWifi::Refuse { ssid: ssid.to_string(), why: TandemWifiRefusal::Unreadable });
            toast(app, &i18n::t1(app, "wifi_unreadable", ssid), &i18n::t(app, "wifi_unreadable_body"));
        }
    }
}

/// Another computer hands this one its Wi-Fi. Joining moves this computer to another network, so the person here says yes first.
fn offered(app: &AppHandle, from: &str, network: &TandemWifiNetwork, password: &str) {
    let name = device_name(app, from);
    let question = Question {
        id: 0,
        title: i18n::t1(app, "wifi_join_title", &network.ssid),
        body: i18n::t1(app, "wifi_join_body", &name),
        allow: i18n::t(app, "wifi_join"),
        always: String::new(),
        deny: i18n::t(app, "wifi_not_now"),
        control: false,
    };
    if crate::ask::ask(app, question) == Answer::Deny {
        return;
    }
    match wifi::join(&from_core(network), password) {
        Ok(()) => toast(app, &i18n::t1(app, "wifi_joined", &network.ssid), &name),
        Err(reason) => {
            log::warn!("could not join the Wi-Fi from {name}: {reason}");
            toast(app, &i18n::t1(app, "wifi_join_failed", &network.ssid), &reason);
        }
    }
}

/// "Share Wi-Fi" on the page of a device: no question here, the person pressed it. What stands in the way is said now.
#[tauri::command]
pub async fn wifi_share(app: AppHandle, id: String) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || share(&app, &id)).await.map_err(|e| e.to_string())?
}

fn share(app: &AppHandle, id: &str) -> Result<String, String> {
    let name = device_name(app, id);
    if !available() {
        return Err(i18n::t(app, "wifi_none_here"));
    }
    let network = wifi::current().ok_or_else(|| i18n::t(app, "wifi_not_on"))?;
    if !shareable(network.security) {
        let key = if network.security == Security::Enterprise { "wifi_enterprise" } else { "wifi_old" };
        return Err(i18n::t1(app, key, &network.ssid));
    }
    let password = wifi::password(&network).ok_or_else(|| format!("{} {}", i18n::t1(app, "wifi_unreadable", &network.ssid), i18n::t(app, "wifi_unreadable_body")))?;
    if !reply(app, id, TandemWifi::Share { network: to_core(&network), password }) {
        return Err(i18n::t1(app, "wifi_unreachable", &name));
    }
    Ok(i18n::t1(app, "wifi_sent", &name))
}
