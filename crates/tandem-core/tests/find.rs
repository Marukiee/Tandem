//! Finding a device: ringing it and hearing back that it rings, and where it was last seen. Real engines over loopback.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::Event;
use tandem_core::platform::DesktopFiles;
use tandem_core::proto::{Msg, NetKind, NetworkStatus, Status};
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::{DeviceId, Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    data: PathBuf,
    downloads: PathBuf,
    _dir: tempfile::TempDir,
}

async fn start(data: &PathBuf, downloads: &PathBuf, name: &str) -> Engine {
    let mut cfg = EngineConfig::new(data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: downloads.clone() });
    Engine::start(cfg, secrets, files).await.unwrap()
}

async fn node(name: &str) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    let downloads = dir.path().join("downloads");
    let engine = start(&data, &downloads, name).await;
    Node { engine, data, downloads, _dir: dir }
}

async fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + Duration::from_secs(45);
    while !condition() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

async fn expect<T>(rx: &mut Receiver<Event>, what: &str, within: Duration, mut pick: impl FnMut(&Event) -> Option<T>) -> Option<T> {
    let deadline = tokio::time::Instant::now() + within;
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(remaining, rx.recv()).await {
            Ok(Ok(event)) => {
                if let Some(found) = pick(&event) {
                    return Some(found);
                }
            }
            Ok(Err(tokio::sync::broadcast::error::RecvError::Lagged(_))) => {}
            Err(_) => return None,
            other => panic!("closed while waiting for: {what} ({other:?})"),
        }
    }
}

fn online(node: &Node, id: DeviceId) -> bool {
    node.engine.devices().iter().any(|d| d.id == id && d.online)
}

async fn pair(showing: &Node, scanning: &Node) {
    let offer = showing.engine.create_pairing_offer().unwrap();
    scanning.engine.pair_with_uri(&offer.uri).await.unwrap();
    wait_until("connected", || online(showing, scanning.engine.id()) && online(scanning, showing.engine.id())).await;
}

fn ringing_from(from: DeviceId) -> impl FnMut(&Event) -> Option<bool> {
    move |e| match e {
        Event::Ringing { from: f, on } if *f == from => Some(*on),
        _ => None,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_computer_that_is_rung_says_when_it_rings_and_when_it_stopped() {
    let phone = node("Phone").await;
    let laptop = node("Laptop").await;
    let other = node("Other Mac").await;
    pair(&phone, &laptop).await;
    pair(&phone, &other).await;
    wait_until("the two computers connect", || online(&laptop, other.engine.id())).await;

    let mut at_laptop = laptop.engine.subscribe();
    let mut at_phone = phone.engine.subscribe();
    let mut at_other = other.engine.subscribe();
    phone.engine.send_msg(&[laptop.engine.id()], Msg::Ring { on: true }).await;
    let asked = expect(&mut at_laptop, "the ring arrives", Duration::from_secs(45), |e| match e {
        Event::Ring { from, on } => Some((*from, *on)),
        _ => None,
    })
    .await;
    assert_eq!(asked, Some((phone.engine.id(), true)));

    laptop.engine.ringing(true).await;
    assert_eq!(expect(&mut at_phone, "it rings", Duration::from_secs(45), ringing_from(laptop.engine.id())).await, Some(true));
    // Stopped on the laptop itself: the phone hears it, and its button goes back.
    laptop.engine.ringing(false).await;
    assert_eq!(expect(&mut at_phone, "it stopped", Duration::from_secs(45), ringing_from(laptop.engine.id())).await, Some(false));
    // A device that never asked hears none of it.
    assert_eq!(expect(&mut at_other, "nothing for the other one", Duration::from_millis(600), ringing_from(laptop.engine.id())).await, None);

    // After a stop nobody asked any more, so a sound that starts for some other reason is told to no one.
    laptop.engine.ringing(true).await;
    assert_eq!(expect(&mut at_phone, "nothing more", Duration::from_millis(800), ringing_from(laptop.engine.id())).await, None);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn where_a_computer_was_last_seen_is_kept_after_it_goes_and_after_a_restart() {
    let phone = node("Phone").await;
    let laptop = node("Laptop").await;
    pair(&phone, &laptop).await;
    let laptop_id = laptop.engine.id();
    let seen = |engine: &Engine| engine.devices().into_iter().find(|d| d.id == laptop_id).and_then(|d| d.last_seen);

    let before = tandem_core::ids::now_ms();
    laptop
        .engine
        .update_status(Status {
            network: Some(NetworkStatus { kind: NetKind::Wifi, ssid: Some("Thuis".into()), metered: false, roaming: false, signal: None }),
            ..Default::default()
        })
        .await;
    wait_until("the network arrives", || seen(&phone.engine).and_then(|s| s.ssid).as_deref() == Some("Thuis")).await;
    assert!(seen(&phone.engine).unwrap().at_ms >= before);

    laptop.engine.shutdown().await;
    wait_until("the laptop is gone", || !online(&phone, laptop_id)).await;
    let gone = seen(&phone.engine).unwrap();
    assert_eq!(gone.ssid.as_deref(), Some("Thuis"));
    tokio::time::sleep(Duration::from_millis(300)).await;
    // Offline, the time stays where it was instead of moving with the clock.
    assert_eq!(seen(&phone.engine).unwrap().at_ms, gone.at_ms);

    phone.engine.shutdown().await;
    let again = start(&phone.data, &phone.downloads, "Phone").await;
    let kept = seen(&again).expect("still known after a restart");
    assert_eq!(kept.ssid.as_deref(), Some("Thuis"));
    assert!(kept.at_ms >= gone.at_ms);
}
