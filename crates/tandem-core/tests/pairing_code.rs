//! Pairing with a short code and taking a new device into a circle: real engines, real QUIC, loopback.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::{Engine, EngineConfig};

struct Node {
    engine: Engine,
    _dir: tempfile::TempDir,
}

impl Node {
    fn addr(&self) -> Vec<SocketAddr> {
        vec![SocketAddr::from(([127, 0, 0, 1], self.engine.port()))]
    }
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

async fn connected(a: &Node, b: &Node) {
    let (ia, ib) = (a.engine.id(), b.engine.id());
    wait_until("connected", || a.engine.is_connected(&ib) && b.engine.is_connected(&ia)).await;
}

#[tokio::test]
async fn a_typed_code_pairs_two_devices_that_are_alone() {
    let shower = node("Linux").await;
    let scanner = node("Phone").await;
    let offer = shower.engine.create_pairing_offer().unwrap();
    assert_eq!(offer.code.len(), 9, "four digits, a space and four digits");

    scanner.engine.pair_with_code_at(&offer.code, &shower.addr()).await.unwrap();
    connected(&shower, &scanner).await;

    // The code is used up.
    let other = node("Other").await;
    assert!(other.engine.pair_with_code_at(&offer.code, &shower.addr()).await.is_err());
}

#[tokio::test]
async fn a_device_in_a_circle_takes_a_new_one_in_with_its_code() {
    let mac = node("Mac").await;
    let phone = node("Phone").await;
    let offer = mac.engine.create_pairing_offer().unwrap();
    phone.engine.pair_with_uri(&offer.uri).await.unwrap();
    connected(&mac, &phone).await;

    let linux = node("Linux").await;
    let offer = linux.engine.create_pairing_offer().unwrap();
    // The phone is in a circle and types the code of the Linux computer.
    phone.engine.pair_with_code_at(&offer.code, &linux.addr()).await.unwrap();
    connected(&linux, &phone).await;
    // The Mac hears about it through the circle and ends up connected too.
    connected(&linux, &mac).await;
}

#[tokio::test]
async fn a_device_in_a_circle_takes_a_new_one_in_by_scanning_its_code() {
    let mac = node("Mac").await;
    let phone = node("Phone").await;
    let offer = mac.engine.create_pairing_offer().unwrap();
    phone.engine.pair_with_uri(&offer.uri).await.unwrap();
    connected(&mac, &phone).await;

    let linux = node("Linux").await;
    let offer = linux.engine.create_pairing_offer().unwrap();
    phone.engine.pair_with_uri(&offer.uri).await.unwrap();
    connected(&linux, &phone).await;
    connected(&linux, &mac).await;
}

#[tokio::test]
async fn two_devices_in_different_circles_do_not_pair() {
    let (a, b, c, d) = (node("A").await, node("B").await, node("C").await, node("D").await);
    let offer = a.engine.create_pairing_offer().unwrap();
    b.engine.pair_with_uri(&offer.uri).await.unwrap();
    let offer = c.engine.create_pairing_offer().unwrap();
    d.engine.pair_with_uri(&offer.uri).await.unwrap();

    let offer = a.engine.create_pairing_offer().unwrap();
    let error = c.engine.pair_with_code_at(&offer.code, &a.addr()).await.unwrap_err();
    assert!(error.to_string().contains("circle"), "{error}");
    let offer = a.engine.create_pairing_offer().unwrap();
    let error = c.engine.pair_with_uri(&offer.uri).await.unwrap_err();
    assert!(error.to_string().contains("circle"), "{error}");
}

#[tokio::test]
async fn a_wrong_code_is_refused_and_too_many_wrong_ones_use_the_code_up() {
    let shower = node("Linux").await;
    let scanner = node("Phone").await;
    let offer = shower.engine.create_pairing_offer().unwrap();
    let wrong = if offer.code.starts_with("0000") { "1111 1111" } else { "0000 0000" };

    for _ in 0..5 {
        assert!(scanner.engine.pair_with_code_at(wrong, &shower.addr()).await.is_err());
    }
    // The right code no longer works either, the owner has to show a new one.
    assert!(scanner.engine.pair_with_code_at(&offer.code, &shower.addr()).await.is_err());

    let fresh = shower.engine.create_pairing_offer().unwrap();
    scanner.engine.pair_with_code_at(&fresh.code, &shower.addr()).await.unwrap();
    connected(&shower, &scanner).await;
}

#[tokio::test]
async fn codes_with_spaces_and_dashes_are_understood() {
    assert_eq!(tandem_core::pairing::normalize_code("1234 5678").as_deref(), Some("12345678"));
    assert_eq!(tandem_core::pairing::normalize_code(" 1234-5678 ").as_deref(), Some("12345678"));
    assert_eq!(tandem_core::pairing::normalize_code("1234567"), None);
    assert_eq!(tandem_core::pairing::normalize_code("1234 56a8"), None);
}

/// Needs real multicast on this machine, so it is run by hand: `cargo test -p tandem-core --test pairing_code -- --ignored`.
#[tokio::test]
#[ignore]
async fn a_typed_code_finds_the_device_that_shows_it_over_mdns() {
    async fn with_mdns(name: &str) -> Node {
        let dir = tempfile::tempdir().unwrap();
        let data = dir.path().join("data");
        let mut cfg = EngineConfig::new(&data, name);
        cfg.port = 0;
        cfg.enable_mdns = true;
        let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
        let files = Arc::new(DesktopFiles { download_dir: dir.path().join("downloads") });
        let engine = Engine::start(cfg, secrets, files).await.unwrap();
        Node { engine, _dir: dir }
    }
    let shower = with_mdns("Linux").await;
    let scanner = with_mdns("Phone").await;
    let other = with_mdns("Bystander").await;
    let offer = shower.engine.create_pairing_offer().unwrap();

    // Nothing is told about where the device is: the code alone finds it.
    scanner.engine.pair_with_uri(&offer.code).await.unwrap();
    connected(&shower, &scanner).await;
    // A device that was not given the code does not get in.
    assert!(other.engine.pair_with_uri("00000000").await.is_err());
}
