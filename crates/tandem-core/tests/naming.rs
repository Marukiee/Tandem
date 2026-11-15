//! A name for a device that holds for the whole circle and can be given from any device in it (R-I33). Real engines, real QUIC, loopback.

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::Event;
use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::{DeviceId, Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    dir: tempfile::TempDir,
}

async fn start(dir: &Path, name: &str) -> Engine {
    let data = dir.join("data");
    let mut cfg = EngineConfig::new(&data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: dir.join("downloads") });
    Engine::start(cfg, secrets, files).await.unwrap()
}

async fn node(name: &str) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let engine = start(dir.path(), name).await;
    Node { engine, dir }
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

fn name_of(node: &Node, id: DeviceId) -> Option<String> {
    node.engine.devices().into_iter().find(|d| d.id == id).map(|d| d.name)
}

async fn join(host: &Node, guest: &Node) {
    let offer = host.engine.create_pairing_offer().unwrap();
    guest.engine.pair_with_uri(&offer.uri).await.unwrap();
    let (h, g) = (host.engine.id(), guest.engine.id());
    wait_until("connected", || online(host, g) && online(guest, h)).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_name_given_on_one_device_holds_everywhere_and_on_the_device_itself() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let laptop = node("Laptop").await;
    join(&phone, &mac).await;
    join(&phone, &laptop).await;
    let (mac_id, laptop_id) = (mac.engine.id(), laptop.engine.id());
    wait_until("the laptop and the Mac know each other", || online(&mac, laptop_id) && online(&laptop, mac_id)).await;
    let mut mac_events = mac.engine.subscribe();

    // The phone names the Mac. The laptop shows it, and the Mac takes it as its own and says so to its app.
    assert!(phone.engine.can_rename(&mac_id));
    phone.engine.rename_device(&mac_id, "Work Mac").await.unwrap();
    let said = expect(&mut mac_events, "the Mac hears its new name", |e| match e {
        Event::SelfRenamed { name } => Some(name.clone()),
        _ => None,
    })
    .await;
    assert_eq!(said, "Work Mac");
    assert_eq!(mac.engine.name(), "Work Mac");
    wait_until("the laptop shows the new name", || name_of(&laptop, mac_id).as_deref() == Some("Work Mac")).await;
    assert_eq!(name_of(&phone, mac_id).as_deref(), Some("Work Mac"));

    // An empty name is refused.
    assert!(phone.engine.rename_device(&mac_id, "  ").await.is_err());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_device_that_was_away_picks_up_its_name_when_it_is_back_and_keeps_it_after_a_restart() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    join(&phone, &mac).await;
    let mac_id = mac.engine.id();
    let dir = mac.dir;
    mac.engine.shutdown().await;
    wait_until("the Mac is away", || !online(&phone, mac_id)).await;

    // The phone saw the Mac say hello before, so it knows the Mac can take a name.
    phone.engine.rename_device(&mac_id, "Studio Mac").await.unwrap();
    assert_eq!(name_of(&phone, mac_id).as_deref(), Some("Studio Mac"));

    let mac = start(dir.path(), "Mac").await;
    wait_until("the Mac takes the name it was given while away", || mac.name() == "Studio Mac").await;
    mac.shutdown().await;
    let again = start(dir.path(), "Mac").await;
    assert_eq!(again.name(), "Studio Mac");
    again.shutdown().await;
}
