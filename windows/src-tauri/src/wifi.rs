//! Handing the Wi-Fi of this computer to another device of the circle, and taking over the Wi-Fi of another one (see `wifi` in the
//! core). The core tells the others which network this computer is on. A device without a network offers its person to take over a
//! network of another one; that yes is the only question, the device that is asked hands the password over. Here the question is the
//! card in the corner (on Linux also a notification with the buttons). The password is only in memory, on its way.

use std::collections::{HashMap, HashSet};
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
    TandemWifiNetwork { ssid: network.ssid.clone(), security, hidden: network.hidden, locked: false }
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

/// What this computer knows of the networks of others, and what it asked: one lock, as the loop and the events both use it.
#[derive(Default)]
struct Offers {
    /// The network each other device said it is on.
    theirs: HashMap<String, TandemWifiNetwork>,
    /// Offered since this computer last had a network.
    offered: HashSet<String>,
    /// Said no to, until that device is on another network.
    declined: HashSet<String>,
    /// The network asked for from each device, after a yes here: what comes back is joined without asking again.
    pending: HashMap<String, String>,
    /// How many looks in a row found no network. Losing it for a moment (moving to another access point) says nothing.
    without: u32,
}

static OFFERS: OnceLock<Mutex<Offers>> = OnceLock::new();

fn offers() -> std::sync::MutexGuard<'static, Offers> {
    OFFERS.get_or_init(Default::default).lock().unwrap()
}

/// Says which network this computer is on, and again whenever that changes. The core passes on only a change. Every look also
/// says whether to offer the network of another device.
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
            let on_wifi = current.is_some();
            if let Ok(engine) = app.state::<AppState>().engine() {
                tauri::async_runtime::block_on(engine.set_wifi_network(current.map(|n| to_core(&n))));
            }
            look(&app, on_wifi || wifi::connected());
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

/// One look: with no network for two looks in a row, a network of another device that can be reached now and that this computer does
/// not know is offered, one at a time.
fn look(app: &AppHandle, connected: bool) {
    let offer = {
        let mut offers = offers();
        if connected {
            offers.without = 0;
            offers.offered.clear();
            return;
        }
        offers.without += 1;
        if offers.without < 2 || !offers.pending.is_empty() {
            return;
        }
        let known = wifi::known();
        let reachable = |id: &str| {
            let state = app.state::<AppState>();
            let data = state.data.lock().unwrap();
            data.devices.iter().any(|d| d["id"] == id && (d["online"].as_bool() == Some(true) || d["ble"].as_bool() == Some(true)))
        };
        let found = offers
            .theirs
            .iter()
            .find(|(id, network)| {
                !offers.offered.contains(&network.ssid)
                    && !offers.declined.contains(&network.ssid)
                    && tandem_core::ffi::wifi_worth_offering((*network).clone(), false, known.clone())
                    && reachable(id)
            })
            .map(|(id, network)| (id.clone(), network.clone()));
        if let Some((_, network)) = &found {
            offers.offered.insert(network.ssid.clone());
        }
        found
    };
    if let Some((id, network)) = offer {
        let app = app.clone();
        std::thread::spawn(move || offer_network(&app, &id, &network));
    }
}

/// Asks the person here whether this computer, which has no network, takes over the one of another device.
fn offer_network(app: &AppHandle, from: &str, network: &TandemWifiNetwork) {
    let name = device_name(app, from);
    let question = Question {
        id: 0,
        title: i18n::t1(app, "wifi_use_title", &name),
        body: i18n::t2(app, "wifi_use_body", &name, &network.ssid),
        allow: i18n::t(app, "wifi_join"),
        always: String::new(),
        deny: i18n::t(app, "wifi_not_now"),
        control: false,
    };
    if crate::ask::ask(app, question) == Answer::Deny {
        offers().declined.insert(network.ssid.clone());
        return;
    }
    offers().pending.insert(from.to_string(), network.ssid.clone());
    if !reply(app, from, TandemWifi::Ask { ssid: network.ssid.clone() }) {
        offers().pending.remove(from);
        toast(app, &i18n::t1(app, "wifi_unreachable", &name), &network.ssid);
        return;
    }
    std::thread::sleep(Duration::from_secs(100));
    let mut offers = offers();
    if offers.pending.get(from) == Some(&network.ssid) {
        offers.pending.remove(from);
        drop(offers);
        toast(app, &i18n::t1(app, "wifi_not_sent", &name), &network.ssid);
    }
}

pub fn handle(app: &AppHandle, from: String, message: TandemWifi) {
    let app = app.clone();
    match message {
        // Off the thread of the events: reading a password and joining take a while, and a question waits for the person.
        TandemWifi::Ask { ssid } => {
            std::thread::spawn(move || asked(&app, &from, &ssid));
        }
        TandemWifi::Share { network, password } => {
            let wanted = {
                let mut offers = offers();
                let wanted = offers.pending.get(&from) == Some(&network.ssid);
                if wanted {
                    offers.pending.remove(&from);
                }
                wanted
            };
            std::thread::spawn(move || {
                if wanted {
                    join(&app, &from, &network, &password);
                } else {
                    offered(&app, &from, &network, &password);
                }
            });
        }
        TandemWifi::Network { network } => {
            let mut offers = offers();
            // A no holds until that device is on another network.
            if let Some(before) = offers.theirs.get(&from).map(|n| n.ssid.clone()) {
                if network.as_ref().map(|n| &n.ssid) != Some(&before) {
                    offers.declined.remove(&before);
                }
            }
            match network {
                Some(network) => offers.theirs.insert(from, network),
                None => offers.theirs.remove(&from),
            };
        }
        TandemWifi::Refuse { ssid, why } => {
            let mut offers = offers();
            if offers.pending.get(&from) != Some(&ssid) {
                return;
            }
            offers.pending.remove(&from);
            drop(offers);
            let name = device_name(&app, &from);
            let key = match why {
                TandemWifiRefusal::CannotShare => "wifi_refused_cannot",
                TandemWifiRefusal::NotOnIt => "wifi_refused_not_on_it",
                TandemWifiRefusal::Unreadable => "wifi_refused_unreadable",
                _ => "wifi_not_sent",
            };
            toast(&app, &i18n::t1(&app, key, &name), &ssid);
        }
    }
}

/// Another device asks for the password of this computer's network: its person said yes to taking it over there, so no second
/// question here.
fn asked(app: &AppHandle, from: &str, ssid: &str) {
    let Some(network) = wifi::current().filter(|n| n.ssid == ssid) else {
        reply(app, from, TandemWifi::Refuse { ssid: ssid.to_string(), why: TandemWifiRefusal::NotOnIt });
        return;
    };
    if !shareable(network.security) {
        reply(app, from, TandemWifi::Refuse { ssid: ssid.to_string(), why: TandemWifiRefusal::CannotShare });
        return;
    }
    let name = device_name(app, from);
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

/// Another device hands this one its Wi-Fi without being asked (Share Wi-Fi there). Joining moves this computer to another network,
/// so the person here says yes first.
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
    join(app, from, network, password);
}

fn join(app: &AppHandle, from: &str, network: &TandemWifiNetwork, password: &str) {
    let name = device_name(app, from);
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
