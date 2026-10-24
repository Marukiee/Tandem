//! Handing the Wi-Fi of a computer to a phone: the computer says which network it is on, to a phone that connects and to one
//! that only has a Bluetooth link, and a question for the password gets its answer. Real engines, real QUIC, loopback.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::Event;
use tandem_core::platform::DesktopFiles;
use tandem_core::proto::Msg;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::wifi::{WifiMsg, WifiNetwork, WifiPassword, WifiSecurity};
use tandem_core::{DeviceId, Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    _dir: tempfile::TempDir,
    _downloads: PathBuf,
}

async fn node(name: &str, caps: &[&str]) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    let downloads = dir.path().join("downloads");
    let mut cfg = EngineConfig::new(&data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    cfg.caps = caps.iter().map(|c| c.to_string()).collect();
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: downloads.clone() });
    let engine = Engine::start(cfg, secrets, files).await.unwrap();
    Node { engine, _dir: dir, _downloads: downloads }
}

async fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + Duration::from_secs(45);
    while !condition() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

async fn expect<T>(rx: &mut Receiver<Event>, what: &str, mut pick: impl FnMut(&Event) -> Option<T>) -> T {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(45);
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(remaining, rx.recv()).await {
            Ok(Ok(event)) => {
                if let Some(found) = pick(&event) {
                    return found;
                }
            }
            Ok(Err(tokio::sync::broadcast::error::RecvError::Lagged(_))) => {}
            other => panic!("timed out or closed while waiting for: {what} ({other:?})"),
        }
    }
}

fn online(node: &Node, id: DeviceId) -> bool {
    node.engine.devices().iter().any(|d| d.id == id && d.online)
}

fn home() -> WifiNetwork {
    WifiNetwork { ssid: "Home".into(), security: WifiSecurity::Wpa2, hidden: false }
}

fn network_from(event: &Event, from: DeviceId) -> Option<Option<WifiNetwork>> {
    match event {
        Event::Wifi { from: sender, msg: WifiMsg::Network { network } } if *sender == from => Some(network.clone()),
        _ => None,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_phone_hears_the_network_when_it_connects_and_when_it_changes() {
    let phone = node("Phone", &["wifi.join"]).await;
    let mac = node("Mac", &["wifi.share", "wifi.join"]).await;
    // Said before the phone is there: the phone hears it the moment it connects.
    mac.engine.set_wifi_network(Some(home())).await;
    let mut phone_events = phone.engine.subscribe();

    let offer = phone.engine.create_pairing_offer().unwrap();
    mac.engine.pair_with_uri(&offer.uri).await.unwrap();
    let mac_id = mac.engine.id();
    let heard = expect(&mut phone_events, "the network on connect", |e| network_from(e, mac_id)).await;
    assert_eq!(heard, Some(home()));

    // Two devices that find each other can have two connections for a moment, and a connection that starts says the network again: the
    // one said last may come once more. One said before it may not.
    let office = WifiNetwork { ssid: "Office".into(), security: WifiSecurity::Wpa3, hidden: true };
    mac.engine.set_wifi_network(Some(office.clone())).await;
    let heard = expect(&mut phone_events, "the new network", |e| network_from(e, mac_id).filter(|n| *n != Some(home()))).await;
    assert_eq!(heard, Some(office.clone()));

    // Off Wi-Fi: the phone must forget the old one, or it would offer a network the Mac has left.
    mac.engine.set_wifi_network(None).await;
    let heard = expect(&mut phone_events, "no network", |e| network_from(e, mac_id).filter(|n| *n != Some(office.clone()))).await;
    assert_eq!(heard, None);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_password_comes_back_after_a_question() {
    let phone = node("Phone", &["wifi.join"]).await;
    let mac = node("Mac", &["wifi.share"]).await;
    let offer = phone.engine.create_pairing_offer().unwrap();
    mac.engine.pair_with_uri(&offer.uri).await.unwrap();
    let (phone_id, mac_id) = (phone.engine.id(), mac.engine.id());
    wait_until("connected", || online(&phone, mac_id) && online(&mac, phone_id)).await;
    let mut phone_events = phone.engine.subscribe();
    let mut mac_events = mac.engine.subscribe();

    phone.engine.send_msg(&[mac_id], Msg::Wifi(WifiMsg::Ask { ssid: "Home".into() })).await;
    let asked = expect(&mut mac_events, "the question", |e| match e {
        Event::Wifi { from, msg: WifiMsg::Ask { ssid } } if *from == phone_id => Some(ssid.clone()),
        _ => None,
    })
    .await;
    assert_eq!(asked, "Home");

    let share = WifiMsg::Share { network: home(), password: WifiPassword("correct horse".into()) };
    mac.engine.send_msg(&[phone_id], Msg::Wifi(share.clone())).await;
    let got = expect(&mut phone_events, "the password", |e| match e {
        Event::Wifi { from, msg: msg @ WifiMsg::Share { .. } } if *from == mac_id => Some(msg.clone()),
        _ => None,
    })
    .await;
    assert_eq!(got, share);

    // A name longer than Wi-Fi allows goes no further than the core.
    let silly = WifiMsg::Ask { ssid: "x".repeat(40) };
    phone.engine.send_msg(&[mac_id], Msg::Wifi(silly)).await;
    phone.engine.send_msg(&[mac_id], Msg::Wifi(WifiMsg::Ask { ssid: "Next".into() })).await;
    let next = expect(&mut mac_events, "the next question", |e| match e {
        Event::Wifi { msg: WifiMsg::Ask { ssid }, .. } => Some(ssid.clone()),
        _ => None,
    })
    .await;
    assert_eq!(next, "Next");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_new_bluetooth_link_carries_the_network_and_a_phone_never_announces() {
    let phone = node("Phone", &["wifi.join"]).await;
    let mac = node("Mac", &["wifi.share"]).await;
    let offer = phone.engine.create_pairing_offer().unwrap();
    mac.engine.pair_with_uri(&offer.uri).await.unwrap();
    let (phone_id, mac_id) = (phone.engine.id(), mac.engine.id());
    wait_until("the Bluetooth key", || mac.engine.ble_ready(&phone_id) && phone.engine.ble_ready(&mac_id)).await;

    // A phone that says which network it is on tells nobody: it has no `wifi.share`.
    let mut mac_events = mac.engine.subscribe();
    phone.engine.set_wifi_network(Some(home())).await;

    let mut phone_events = phone.engine.subscribe();
    mac.engine.set_wifi_network(Some(home())).await;
    expect(&mut phone_events, "the network over QUIC", |e| network_from(e, mac_id)).await;

    // The phone says hello over the air; the Mac takes the link and queues its network for it. Nothing changed, so a second
    // network can only have come this way.
    for chunk in phone.engine.ble_hello(&mac_id, 180) {
        mac.engine.ble_receive("phone", &chunk).await;
    }
    let waiting = mac.engine.ble_take_outbox(&phone_id, 180);
    assert!(!waiting.is_empty(), "the Mac queued nothing for the new link");
    for chunk in waiting {
        phone.engine.ble_receive("mac", &chunk).await;
    }
    let heard = expect(&mut phone_events, "the network over Bluetooth", |e| network_from(e, mac_id)).await;
    assert_eq!(heard, Some(home()));

    tokio::time::sleep(Duration::from_millis(300)).await;
    while let Ok(event) = mac_events.try_recv() {
        assert!(network_from(&event, phone_id).is_none(), "the phone announced its network");
    }
}
