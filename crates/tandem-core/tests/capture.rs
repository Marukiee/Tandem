//! "Insert from phone": a request goes to the phone, the picture comes back as a file share that
//! carries the id of the request. Real engines, real QUIC, loopback.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::Event;
use tandem_core::platform::DesktopFiles;
use tandem_core::proto::{CaptureCancel, CaptureKind, CaptureRequest, CaptureWhy, Msg, ShareOrigin};
use tandem_core::store::{DeviceSettings, FileSecretStore, Store};
use tandem_core::{DeviceId, Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    _dir: tempfile::TempDir,
    _downloads: PathBuf,
}

async fn node(name: &str) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    let downloads = dir.path().join("downloads");
    let mut cfg = EngineConfig::new(&data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: downloads.clone() });
    let engine = Engine::start(cfg, secrets, files).await.unwrap();
    Node { engine, _dir: dir, _downloads: downloads }
}

async fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + Duration::from_secs(20);
    while !condition() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

async fn expect<T>(rx: &mut Receiver<Event>, what: &str, mut pick: impl FnMut(&Event) -> Option<T>) -> T {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
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

async fn paired() -> (Node, Node) {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let offer = phone.engine.create_pairing_offer().unwrap();
    mac.engine.pair_with_uri(&offer.uri).await.unwrap();
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;
    (phone, mac)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_picture_comes_back_tagged_with_the_request() {
    let (phone, mac) = paired().await;
    let mut phone_events = phone.engine.subscribe();
    let mut mac_events = mac.engine.subscribe();

    let sent = mac
        .engine
        .send_msg(&[phone.engine.id()], Msg::CaptureRequest(CaptureRequest { id: 4242, kind: CaptureKind::Document }))
        .await;
    assert_eq!(sent, vec![phone.engine.id()]);

    let (from, id, kind) = expect(&mut phone_events, "capture request", |e| match e {
        Event::CaptureRequested { from, id, kind } => Some((*from, *id, *kind)),
        _ => None,
    })
    .await;
    assert_eq!((from, id, kind), (mac.engine.id(), 4242, CaptureKind::Document));

    let jpeg = b"\xFF\xD8\xFF\xE0 not really a picture".to_vec();
    phone
        .engine
        .send_bytes(&[mac.engine.id()], "scan.jpg", "image/jpeg", jpeg.clone(), ShareOrigin::Capture(id))
        .await
        .unwrap();

    let origin = expect(&mut mac_events, "the offer", |e| match e {
        Event::ShareOffered { offer, .. } => Some(offer.origin),
        _ => None,
    })
    .await;
    assert_eq!(origin, ShareOrigin::Capture(4242));

    let done = expect(&mut mac_events, "the file", |e| match e {
        Event::Finished(done) if done.incoming => Some(done.clone()),
        _ => None,
    })
    .await;
    assert_eq!(done.error, None);
    assert_eq!(std::fs::read(done.location.unwrap()).unwrap(), jpeg);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_answer_waits_for_a_yes_when_auto_accept_is_off_for_the_phone() {
    // The Mac decides whether to take a capture answer, so the offer must reach it as an offer
    // that can be accepted by hand, with its request id intact.
    let (phone, mac) = paired().await;
    mac.engine
        .set_device_settings(&phone.engine.id(), DeviceSettings { clipboard: true, auto_accept: false, notifications: true })
        .unwrap();
    let mut mac_events = mac.engine.subscribe();

    phone
        .engine
        .send_bytes(&[mac.engine.id()], "photo.jpg", "image/jpeg", b"pixels".to_vec(), ShareOrigin::Capture(7))
        .await
        .unwrap();
    let (from, offer) = expect(&mut mac_events, "the offer", |e| match e {
        Event::ShareOffered { from, offer } => Some((*from, offer.clone())),
        _ => None,
    })
    .await;
    assert_eq!(offer.origin, ShareOrigin::Capture(7));
    mac.engine.accept_offer(from, offer.id).unwrap();
    let done = expect(&mut mac_events, "the file", |e| match e {
        Event::Finished(done) if done.incoming => Some(done.clone()),
        _ => None,
    })
    .await;
    assert_eq!(done.error, None);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_cancel_travels_both_ways_with_its_reason() {
    let (phone, mac) = paired().await;
    let mut phone_events = phone.engine.subscribe();
    let mut mac_events = mac.engine.subscribe();

    mac.engine
        .send_msg(&[phone.engine.id()], Msg::CaptureCancel(CaptureCancel { id: 5, why: CaptureWhy::Cancelled }))
        .await;
    let got = expect(&mut phone_events, "the cancel", |e| match e {
        Event::CaptureCancelled { id, why, .. } => Some((*id, *why)),
        _ => None,
    })
    .await;
    assert_eq!(got, (5, CaptureWhy::Cancelled));

    phone
        .engine
        .send_msg(&[mac.engine.id()], Msg::CaptureCancel(CaptureCancel { id: 6, why: CaptureWhy::Refused }))
        .await;
    let got = expect(&mut mac_events, "the refusal", |e| match e {
        Event::CaptureCancelled { id, why, .. } => Some((*id, *why)),
        _ => None,
    })
    .await;
    assert_eq!(got, (6, CaptureWhy::Refused));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_kind_the_phone_does_not_know_is_answered_at_once() {
    let (phone, mac) = paired().await;
    let mut phone_events = phone.engine.subscribe();
    let mut mac_events = mac.engine.subscribe();

    mac.engine
        .send_msg(&[phone.engine.id()], Msg::CaptureRequest(CaptureRequest { id: 8, kind: CaptureKind::Other }))
        .await;
    let got = expect(&mut mac_events, "the refusal", |e| match e {
        Event::CaptureCancelled { id, why, .. } => Some((*id, *why)),
        _ => None,
    })
    .await;
    assert_eq!(got, (8, CaptureWhy::Unavailable));

    // The phone's app never heard of it.
    while let Ok(event) = phone_events.try_recv() {
        assert!(!matches!(event, Event::CaptureRequested { .. }));
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_request_to_a_device_that_is_not_there_reaches_nobody() {
    let (phone, mac) = paired().await;
    let stranger = DeviceId::from_bytes([9; 16]);
    let reached = mac
        .engine
        .send_msg(&[stranger], Msg::CaptureRequest(CaptureRequest { id: 1, kind: CaptureKind::Photo }))
        .await;
    assert!(reached.is_empty());
    let _ = phone;
}
