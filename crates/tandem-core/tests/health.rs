//! Nights and workouts passing from the device that has the watch to the ones the person chose. Real engines, real QUIC, loopback.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use tandem_core::health::{HealthKind, HealthRecord, SleepStage, StageRun};
use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::{Engine, EngineConfig, Platform};

struct Node {
    engine: Engine,
    data: PathBuf,
    downloads: PathBuf,
    _dir: tempfile::TempDir,
}

async fn start(data: &Path, downloads: &Path, name: &str) -> Engine {
    start_as(data, downloads, name, Platform::MacOs).await
}

async fn start_as(data: &Path, downloads: &Path, name: &str, platform: Platform) -> Engine {
    let mut cfg = EngineConfig::new(data, name);
    cfg.platform = platform;
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: downloads.to_path_buf() });
    Engine::start(cfg, secrets, files).await.unwrap()
}

async fn node(name: &str) -> Node {
    node_as(name, Platform::MacOs).await
}

async fn node_as(name: &str, platform: Platform) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let (data, downloads) = (dir.path().join("data"), dir.path().join("downloads"));
    let engine = start_as(&data, &downloads, name, platform).await;
    Node { engine, data, downloads, _dir: dir }
}

async fn pair(showing: &Node, scanning: &Node) {
    let offer = showing.engine.create_pairing_offer().unwrap();
    scanning.engine.pair_with_uri(&offer.uri).await.unwrap();
    let (a, b) = (showing.engine.id(), scanning.engine.id());
    wait_until("connected", || online(&showing.engine, b) && online(&scanning.engine, a)).await;
}

fn online(engine: &Engine, id: tandem_core::DeviceId) -> bool {
    engine.devices().iter().any(|d| d.id == id && d.online)
}

async fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + Duration::from_secs(45);
    while !condition() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

fn night(id: &str, start_ms: u64) -> HealthRecord {
    HealthRecord {
        id: id.to_string(),
        kind: HealthKind::Sleep,
        start_ms,
        end_ms: start_ms + 8 * 3_600_000,
        sleep_minutes: 440,
        awake_minutes: 25,
        awakenings: 3,
        efficiency_percent: 92,
        light_minutes: Some(230),
        deep_minutes: Some(90),
        rem_minutes: Some(120),
        stages: vec![StageRun { stage: SleepStage::Deep, seconds: 1800 }, StageRun { stage: SleepStage::Rem, seconds: 900 }],
        heart_rate_mean: Some(57),
        heart_rate_low: Some(46),
        heart_rate_high: None,
        steps: 0,
        active_minutes: 0,
        heart_curve: Vec::new(),
        step_curve: Vec::new(),
        added_ms: 0,
        origin: None,
    }
}

fn ids(engine: &Engine) -> Vec<String> {
    engine.health_records().into_iter().map(|r| r.id).collect()
}

/// Gives anything that was wrongly sent the time to arrive.
async fn settle() {
    tokio::time::sleep(Duration::from_millis(400)).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn only_a_device_the_person_chose_gets_them() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let shared = node("Shared computer").await;
    pair(&phone, &mac).await;
    pair(&phone, &shared).await;

    phone.engine.add_health_records(vec![night("first", 1000)]).await.unwrap();
    settle().await;
    assert_eq!(ids(&phone.engine), ["first"]);
    assert!(ids(&mac.engine).is_empty(), "off until the person says so");

    phone.engine.set_health_allowed(&mac.engine.id(), true).await.unwrap();
    wait_until("the mac hears what is there", || ids(&mac.engine) == ["first"]).await;

    phone.engine.add_health_records(vec![night("second", 2000)]).await.unwrap();
    wait_until("the mac hears what is new", || ids(&mac.engine) == ["second", "first"]).await;

    let got = &mac.engine.health_records()[1];
    assert_eq!(got.deep_minutes, Some(90));
    assert_eq!(got.stages.len(), 2);

    settle().await;
    assert!(ids(&shared.engine).is_empty(), "the other computer was never chosen");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn switching_it_off_makes_the_device_forget_them() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    phone.engine.add_health_records(vec![night("first", 1000)]).await.unwrap();
    phone.engine.set_health_allowed(&mac.engine.id(), true).await.unwrap();
    wait_until("the mac has it", || ids(&mac.engine) == ["first"]).await;

    phone.engine.set_health_allowed(&mac.engine.id(), false).await.unwrap();
    wait_until("the mac forgot", || ids(&mac.engine).is_empty()).await;
    assert_eq!(ids(&phone.engine), ["first"], "the phone keeps its own");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_phone_that_leaves_the_circle_takes_them_along() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let other = node("Other computer").await;
    pair(&phone, &mac).await;
    pair(&phone, &other).await;
    phone.engine.add_health_records(vec![night("first", 1000)]).await.unwrap();
    phone.engine.set_health_allowed(&mac.engine.id(), true).await.unwrap();
    phone.engine.set_health_allowed(&other.engine.id(), true).await.unwrap();
    wait_until("both have it", || ids(&mac.engine) == ["first"] && ids(&other.engine) == ["first"]).await;

    mac.engine.remove_device(phone.engine.id()).await.unwrap();
    wait_until("the one that removed it forgot", || ids(&mac.engine).is_empty()).await;
    wait_until("the one that heard about it forgot", || ids(&other.engine).is_empty()).await;
    assert_eq!(ids(&phone.engine), ["first"], "the phone keeps its own");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_computer_that_is_removed_forgets_them() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    phone.engine.add_health_records(vec![night("first", 1000)]).await.unwrap();
    phone.engine.set_health_allowed(&mac.engine.id(), true).await.unwrap();
    wait_until("the mac has it", || ids(&mac.engine) == ["first"]).await;

    phone.engine.remove_device(mac.engine.id()).await.unwrap();
    wait_until("the mac forgot", || ids(&mac.engine).is_empty()).await;
    assert_eq!(ids(&phone.engine), ["first"], "the phone keeps its own");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_device_that_was_away_catches_up_or_forgets() {
    let phone = node("Phone").await;
    let mut mac = node("Mac").await;
    pair(&phone, &mac).await;
    let (phone_id, mac_id) = (phone.engine.id(), mac.engine.id());
    phone.engine.set_health_allowed(&mac_id, true).await.unwrap();
    phone.engine.add_health_records(vec![night("first", 1000)]).await.unwrap();
    wait_until("the mac has it", || ids(&mac.engine) == ["first"]).await;

    // A first sync of a long history does not fit one message's worth of nights, and comes back complete.
    mac.engine.shutdown().await;
    wait_until("the phone sees it go", || !online(&phone.engine, mac_id)).await;
    let many: Vec<HealthRecord> = (0..130).map(|n| night(&format!("n{n}"), 2000 + n)).collect();
    phone.engine.add_health_records(many).await.unwrap();

    mac.engine = start(&mac.data, &mac.downloads, "Mac").await;
    assert_eq!(ids(&mac.engine), ["first"], "what it had is still there after a restart");
    wait_until("connected again", || online(&phone.engine, mac_id) && online(&mac.engine, phone_id)).await;
    wait_until("the mac caught up", || mac.engine.health_records().len() == 131).await;

    // Switched off while it is away: it hears as soon as it is back.
    mac.engine.shutdown().await;
    wait_until("the phone sees it go", || !online(&phone.engine, mac_id)).await;
    phone.engine.set_health_allowed(&mac_id, false).await.unwrap();

    mac.engine = start(&mac.data, &mac.downloads, "Mac").await;
    assert_eq!(mac.engine.health_records().len(), 131);
    wait_until("the mac forgot", || mac.engine.health_records().is_empty()).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_phones_of_a_watch_always_share_them() {
    let first = node_as("Phone", Platform::Android).await;
    let second = node_as("New phone", Platform::Android).await;
    let mac = node("Mac").await;
    pair(&first, &second).await;
    pair(&first, &mac).await;
    let (second_id, mac_id) = (second.engine.id(), mac.engine.id());
    wait_until("the new phone and the mac see each other", || online(&second.engine, mac_id) && online(&mac.engine, second_id)).await;
    assert!(first.engine.health_allowed(&second_id), "no switch between two phones");
    assert!(!first.engine.health_allowed(&mac_id), "a computer still only when the person chose it");

    // The watch was with the first phone, then goes to the new one: each keeps what the other measured.
    first.engine.add_health_records(vec![night("before", 1000)]).await.unwrap();
    wait_until("the new phone has the nights from before", || ids(&second.engine) == ["before"]).await;
    second.engine.add_health_records(vec![night("after", 2000)]).await.unwrap();
    wait_until("the first phone hears the new nights", || ids(&first.engine) == ["after", "before"]).await;

    // Turning it off between two phones changes nothing.
    first.engine.set_health_allowed(&second_id, false).await.unwrap();
    settle().await;
    assert_eq!(ids(&second.engine), ["after", "before"]);

    // A phone only passes on what it measured itself: chosen on one phone, the mac hears each phone's nights from that phone.
    second.engine.set_health_allowed(&mac_id, true).await.unwrap();
    wait_until("the mac hears both phones", || ids(&mac.engine) == ["after", "before"]).await;
    let from: Vec<_> = mac.engine.health_records().into_iter().map(|r| r.origin).collect();
    assert_eq!(from, [Some(second_id), Some(first.engine.id())]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_choice_for_a_computer_and_the_nights_stay_when_a_phone_goes() {
    let first = node_as("Phone", Platform::Android).await;
    let second = node_as("New phone", Platform::Android).await;
    let mac = node("Mac").await;
    pair(&first, &second).await;
    pair(&first, &mac).await;
    let (first_id, second_id, mac_id) = (first.engine.id(), second.engine.id(), mac.engine.id());
    wait_until("the new phone and the mac see each other", || online(&second.engine, mac_id) && online(&mac.engine, second_id)).await;

    // Chosen on the phone with the watch: the new phone makes the same choice.
    first.engine.add_health_records(vec![night("before", 1000)]).await.unwrap();
    first.engine.set_health_allowed(&mac_id, true).await.unwrap();
    wait_until("the new phone chose the mac too", || second.engine.health_allowed(&mac_id)).await;
    wait_until("the mac has the nights from before", || ids(&mac.engine) == ["before"]).await;

    // The watch moved: the mac hears its new nights without a switch on the new phone.
    second.engine.add_health_records(vec![night("after", 2000)]).await.unwrap();
    wait_until("the mac hears the new phone", || ids(&mac.engine) == ["after", "before"]).await;

    // Off on one phone is off on both.
    second.engine.set_health_allowed(&mac_id, false).await.unwrap();
    wait_until("the first phone chose it off too", || !first.engine.health_allowed(&mac_id)).await;
    wait_until("the mac forgot all of it", || ids(&mac.engine).is_empty()).await;
    second.engine.set_health_allowed(&mac_id, true).await.unwrap();
    wait_until("the mac has all of it again", || ids(&mac.engine) == ["after", "before"]).await;

    // The old phone leaves the circle: its nights stay, on the new phone as its own and from there on the mac.
    mac.engine.remove_device(first_id).await.unwrap();
    wait_until("the new phone keeps them as its own", || {
        let records = second.engine.health_records();
        records.len() == 2 && records.iter().all(|r| r.origin.is_none())
    })
    .await;
    wait_until("the mac has them from the new phone", || {
        let records = mac.engine.health_records();
        records.len() == 2 && records.iter().all(|r| r.origin == Some(second_id))
    })
    .await;
    settle().await;
    assert_eq!(ids(&mac.engine), ["after", "before"]);
}
