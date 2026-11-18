//! What a phone's "the network changed" does to connections that work (R-V15): nothing, unless the addresses are really different or the
//! device woke from sleep. Real engines, real QUIC, loopback, with a heartbeat so slow that it does not take part.

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::tls::Tuning;
use tandem_core::{DeviceId, Engine, EngineConfig, Platform};

fn slow_beat() -> Tuning {
    Tuning {
        keep_alive: None,
        idle_timeout: Duration::from_secs(60),
        beat_awake: Duration::from_secs(60),
        beat_quiet: Duration::from_secs(60),
        answer_within: Duration::from_millis(400),
        answer_within_quiet: Duration::from_millis(400),
        status_refresh: Duration::from_secs(3_600),
    }
}

async fn start(data: &Path, name: &str, platform: Platform) -> Engine {
    let mut cfg = EngineConfig::new(data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    cfg.platform = platform;
    cfg.tuning = slow_beat();
    let secrets = Arc::new(FileSecretStore::new(Store::new(data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: data.join("downloads") });
    Engine::start(cfg, secrets, files).await.unwrap()
}

fn online(engine: &Engine, id: DeviceId) -> bool {
    engine.devices().iter().any(|d| d.id == id && d.online)
}

async fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + Duration::from_secs(30);
    while !condition() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

async fn pair(showing: &Engine, scanning: &Engine) {
    let offer = showing.create_pairing_offer().unwrap();
    scanning.pair_with_uri(&offer.uri).await.unwrap();
    let (a, b) = (showing.id(), scanning.id());
    wait_until("connected", || online(showing, b) && online(scanning, a)).await;
}

/// A device whose engine stops answering, without closing anything: a laptop whose lid closed, a phone that lost its network.
fn device_that_can_freeze(data: std::path::PathBuf, name: &'static str) -> (Engine, std::sync::mpsc::Sender<()>) {
    let (give, take) = std::sync::mpsc::channel();
    let (freeze, frozen) = std::sync::mpsc::channel::<()>();
    std::thread::spawn(move || {
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async move {
            give.send(start(&data, name, Platform::MacOs).await).unwrap();
            loop {
                if frozen.try_recv().is_ok() {
                    std::thread::sleep(Duration::from_secs(120));
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        });
    });
    (take.recv().unwrap(), freeze)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_network_that_did_not_change_asks_no_connection_anything() {
    let (a, b) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let phone = start(a.path(), "Phone", Platform::Android).await;
    let mac = start(b.path(), "Mac", Platform::MacOs).await;
    pair(&phone, &mac).await;
    let mac_id = mac.id();
    // What pairing and the hello send has gone by, and a first look at the addresses has been made.
    tokio::time::sleep(Duration::from_millis(1_500)).await;
    phone.network_changed();
    tokio::time::sleep(Duration::from_millis(300)).await;

    let before = phone.traffic(&mac_id).unwrap();
    // The screen goes on and off, the signal moves, a VPN reconnects: the system says so a hundred times.
    for _ in 0..100 {
        phone.network_changed();
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    phone.set_quiet(true).await;
    phone.set_quiet(false).await;
    tokio::time::sleep(Duration::from_millis(500)).await;
    let after = phone.traffic(&mac_id).unwrap();
    assert!(online(&phone, mac_id) && online(&mac, phone.id()), "the connection that worked was closed");
    // The status of the phone going quiet and awake again is a few packets each way; a question per call would be a hundred.
    assert!(after.0 - before.0 <= 12 && after.1 - before.1 <= 12, "packets sent {} and received {} for nothing", after.0 - before.0, after.1 - before.1);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_device_that_went_silent_is_let_go_after_it_woke_from_sleep_and_not_after_a_screen_that_went_on() {
    let (a, b) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let phone = start(a.path(), "Phone", Platform::Android).await;
    let (mac, freeze) = device_that_can_freeze(b.path().to_path_buf(), "Mac");
    pair(&phone, &mac).await;
    let mac_id = mac.id();
    tokio::time::sleep(Duration::from_millis(1_500)).await;
    phone.network_changed();

    freeze.send(()).unwrap();
    // The screen going on is not a reason to ask anything.
    for _ in 0..3 {
        phone.network_changed();
        phone.set_quiet(true).await;
        phone.set_quiet(false).await;
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    // Long enough for a question to have been answered or given up on, and for the last word of the frozen one to be old.
    tokio::time::sleep(Duration::from_millis(4_000)).await;
    assert!(online(&phone, mac_id), "a connection that went quiet was closed for a screen that went on");

    // A device that woke from sleep asks, and finds that nothing answers.
    phone.woke_from_sleep();
    wait_until("the silent one was let go after the wake", || !online(&phone, mac_id)).await;
}
