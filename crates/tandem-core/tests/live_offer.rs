//! A phone that starts the sharing on its own screen tells the Mac with an offer. The Mac's app answers with an
//! ordinary request, so the session rules stay the same. Real engines, real QUIC, loopback.

use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::Event;
use tandem_core::live::{MediaFacing, MediaKind};
use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::{DeviceId, Engine, EngineConfig};

struct Node {
    engine: Engine,
    _dir: tempfile::TempDir,
}

async fn node(name: &str) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    let mut cfg = EngineConfig::new(&data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: dir.path().join("downloads") });
    let engine = Engine::start(cfg, secrets, files).await.unwrap();
    Node { engine, _dir: dir }
}

async fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + Duration::from_secs(45);
    while !condition() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

fn online(node: &Node, id: DeviceId) -> bool {
    node.engine.devices().iter().any(|d| d.id == id && d.online)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_offer_reaches_the_other_device_as_an_event() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let offer = phone.engine.create_pairing_offer().unwrap();
    mac.engine.pair_with_uri(&offer.uri).await.unwrap();
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;

    let mut events = mac.engine.subscribe();
    phone.engine.media_offer(mac.engine.id(), MediaKind::Camera, MediaFacing::Front, false).unwrap();

    let deadline = tokio::time::Instant::now() + Duration::from_secs(45);
    let (from, kind, facing) = loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(remaining, events.recv()).await {
            Ok(Ok(Event::MediaOffered { from, kind, facing, .. })) => break (from, kind, facing),
            Ok(Ok(_)) | Ok(Err(tokio::sync::broadcast::error::RecvError::Lagged(_))) => {}
            other => panic!("no offer arrived: {other:?}"),
        }
    };
    assert_eq!((from, kind, facing), (phone.engine.id(), MediaKind::Camera, MediaFacing::Front));

    // A kind nobody knows is refused before it leaves, and a device that is not in the circle cannot be offered anything.
    assert!(phone.engine.media_offer(mac.engine.id(), MediaKind::Other, MediaFacing::Any, false).is_err());
    let stranger = node("Stranger").await;
    assert!(phone.engine.media_offer(stranger.engine.id(), MediaKind::Screen, MediaFacing::Any, false).is_err());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_offer_for_a_second_screen_says_so_and_only_for_a_screen() {
    let laptop = node("Laptop").await;
    let phone = node("Phone").await;
    let offer = laptop.engine.create_pairing_offer().unwrap();
    phone.engine.pair_with_uri(&offer.uri).await.unwrap();
    wait_until("connected", || online(&laptop, phone.engine.id()) && online(&phone, laptop.engine.id())).await;

    let mut events = phone.engine.subscribe();
    laptop.engine.media_offer(phone.engine.id(), MediaKind::Screen, MediaFacing::Any, true).unwrap();
    // A camera cannot be a second screen: the flag does not survive for it.
    laptop.engine.media_offer(phone.engine.id(), MediaKind::Camera, MediaFacing::Back, true).unwrap();

    let deadline = tokio::time::Instant::now() + Duration::from_secs(45);
    let mut seen = Vec::new();
    while seen.len() < 2 {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(remaining, events.recv()).await {
            Ok(Ok(Event::MediaOffered { kind, extend, .. })) => seen.push((kind, extend)),
            Ok(Ok(_)) | Ok(Err(tokio::sync::broadcast::error::RecvError::Lagged(_))) => {}
            other => panic!("the offers did not arrive: {other:?}"),
        }
    }
    assert_eq!(seen, vec![(MediaKind::Screen, true), (MediaKind::Camera, false)]);
}
