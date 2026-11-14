//! How often connected devices wake each other to make sure they are still there. Real engines, real QUIC, loopback, with the pace of the
//! heartbeat made fast so a test takes seconds and not minutes.

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::tls::Tuning;
use tandem_core::{DeviceId, Engine, EngineConfig, Platform};

/// The pace of the real thing, a hundred times faster: half a second awake, ten seconds with the screen off would be 40 times slower.
fn fast() -> Tuning {
    Tuning {
        keep_alive: None,
        idle_timeout: Duration::from_secs(20),
        beat_awake: Duration::from_millis(250),
        beat_quiet: Duration::from_millis(2_400),
        answer_within: Duration::from_millis(150),
        answer_within_quiet: Duration::from_millis(400),
    }
}

async fn start(data: &Path, name: &str, platform: Platform) -> Engine {
    let mut cfg = EngineConfig::new(data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    cfg.platform = platform;
    cfg.tuning = fast();
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

/// The packets the phone got from the other one in `window`.
async fn received_in(phone: &Engine, other: DeviceId, window: Duration) -> u64 {
    let before = phone.traffic(&other).unwrap().1;
    tokio::time::sleep(window).await;
    phone.traffic(&other).unwrap().1 - before
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_phone_with_its_screen_off_is_woken_far_less_and_stays_connected() {
    let (a, b) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let phone = start(a.path(), "Phone", Platform::Android).await;
    let mac = start(b.path(), "Mac", Platform::MacOs).await;
    pair(&phone, &mac).await;
    let mac_id = mac.id();
    let mut phone_events = phone.subscribe();
    // What pairing and the first hello send has gone by.
    tokio::time::sleep(Duration::from_millis(800)).await;

    let window = Duration::from_millis(4_800);
    let awake = received_in(&phone, mac_id, window).await;
    phone.set_quiet(true).await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    let quiet = received_in(&phone, mac_id, window).await;
    eprintln!("packets the phone got in {window:?}: {awake} with the screen on, {quiet} with it off");

    assert!(awake >= 10, "awake, the two check on each other often: {awake}");
    assert!(quiet * 4 <= awake, "with the screen off the phone is woken far less: {quiet} against {awake}");
    // Longer than the old idle time of the connection would allow without traffic, and never let go meanwhile.
    assert!(online(&phone, mac_id) && online(&mac, phone.id()));
    while let Ok(event) = phone_events.try_recv() {
        assert!(!matches!(event, tandem_core::events::Event::Disconnected { .. }), "the connection stayed: {event:?}");
    }

    // The screen goes on: back to the quick pace at once.
    phone.set_quiet(false).await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    let again = received_in(&phone, mac_id, Duration::from_millis(2_000)).await;
    assert!(again >= 4, "awake again, checked often again: {again}");
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
                // Everything of this engine runs on this one thread: while it sleeps, nothing of it answers.
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
async fn a_device_that_goes_silent_is_let_go_within_a_few_beats() {
    let (a, b) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let phone = start(a.path(), "Phone", Platform::Android).await;
    let (mac, freeze) = device_that_can_freeze(b.path().to_path_buf(), "Mac");
    pair(&phone, &mac).await;
    let mac_id = mac.id();
    tokio::time::sleep(Duration::from_millis(500)).await;

    freeze.send(()).unwrap();
    let went = std::time::Instant::now();
    wait_until("the phone let the silent one go", || !online(&phone, mac_id)).await;
    let took = went.elapsed();
    eprintln!("let go after {took:?}");
    // Two rounds and an answer at most, whichever of the two asks.
    assert!(took < Duration::from_millis(1_500), "let go in time: {took:?}");
}
