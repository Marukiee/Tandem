//! End-to-end tests: real engines, real QUIC, loopback.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::Event;
use tandem_core::platform::DesktopFiles;
use tandem_core::proto::{Battery, ShareOrigin, Status};
use tandem_core::store::{DeviceSettings, FileSecretStore, Store};
use tandem_core::transfer::OutgoingFile;
use tandem_core::{DeviceId, Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    data: PathBuf,
    downloads: PathBuf,
    _dir: tempfile::TempDir,
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
    Node { engine, data, downloads, _dir: dir }
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

async fn pair(showing: &Node, scanning: &Node) {
    let offer = showing.engine.create_pairing_offer().unwrap();
    let joined = scanning.engine.pair_with_uri(&offer.uri).await.unwrap();
    assert_eq!(joined, showing.engine.id());
}

fn random_bytes(len: usize, seed: u8) -> Vec<u8> {
    // Cheap deterministic noise; compresses badly enough to be a fair test.
    let mut state = 0x9E37_79B9_7F4A_7C15u64 ^ seed as u64;
    (0..len)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            (state >> 24) as u8
        })
        .collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn pairing_joins_the_circle_and_connects() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;

    wait_until("phone sees mac online", || online(&phone, mac.engine.id())).await;
    wait_until("mac sees phone online", || online(&mac, phone.engine.id())).await;

    let seen = phone.engine.devices();
    assert_eq!(seen.len(), 1);
    assert_eq!(seen[0].name, "Mac");
    assert!(mac.engine.is_member() && phone.engine.is_member());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_old_or_wrong_pairing_code_is_refused() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let stale = phone.engine.create_pairing_offer().unwrap();
    let _fresh = phone.engine.create_pairing_offer().unwrap();
    let err = mac.engine.pair_with_uri(&stale.uri).await.unwrap_err();
    assert!(err.to_string().contains("did not match"), "unexpected error: {err}");
    assert!(mac.engine.devices().is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_code_works_only_once() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let other = node("Other").await;
    let offer = phone.engine.create_pairing_offer().unwrap();
    mac.engine.pair_with_uri(&offer.uri).await.unwrap();
    assert!(other.engine.pair_with_uri(&offer.uri).await.is_err());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn clipboard_syncs_and_does_not_echo() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;

    let mut mac_events = mac.engine.subscribe();
    let mut phone_events = phone.engine.subscribe();

    phone.engine.clipboard_changed("hello from the phone", false).await;
    let text = expect(&mut mac_events, "clipboard on the mac", |e| match e {
        Event::Clipboard { text, .. } => Some(text.clone()),
        _ => None,
    })
    .await;
    assert_eq!(text, "hello from the phone");

    // The mac writes it to its clipboard, which the app reports back as a change.
    // That must not travel back.
    mac.engine.clipboard_changed("hello from the phone", false).await;
    let echoed = tokio::time::timeout(Duration::from_millis(600), async {
        loop {
            if let Ok(Event::Clipboard { .. }) = phone_events.recv().await {
                return true;
            }
        }
    })
    .await
    .is_ok();
    assert!(!echoed, "the clipboard bounced back");

    // A real change afterwards does go through.
    mac.engine.clipboard_changed("second", true).await;
    let (text, is_url) = expect(&mut phone_events, "second clipboard", |e| match e {
        Event::Clipboard { text, is_url, .. } => Some((text.clone(), *is_url)),
        _ => None,
    })
    .await;
    assert_eq!((text.as_str(), is_url), ("second", true));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_clipboard_can_be_switched_off_per_device() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;

    mac.engine
        .set_device_settings(&phone.engine.id(), DeviceSettings { clipboard: false, ..Default::default() })
        .unwrap();
    let mut mac_events = mac.engine.subscribe();
    phone.engine.clipboard_changed("secret", false).await;
    let arrived = tokio::time::timeout(Duration::from_millis(700), async {
        loop {
            if let Ok(Event::Clipboard { .. }) = mac_events.recv().await {
                return;
            }
        }
    })
    .await
    .is_ok();
    assert!(!arrived);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn files_arrive_intact() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;

    let source_dir = tempfile::tempdir().unwrap();
    let big = random_bytes(6 * 1024 * 1024 + 123, 1);
    let small = b"tiny".to_vec();
    let empty: Vec<u8> = Vec::new();
    let mut files = Vec::new();
    for (name, data) in [("big.bin", &big), ("small.txt", &small), ("empty.dat", &empty)] {
        let path = source_dir.path().join(name);
        std::fs::write(&path, data).unwrap();
        files.push(OutgoingFile {
            source: path.to_string_lossy().into_owned(),
            name: name.to_string(),
            size: 0,
            mime: "application/octet-stream".into(),
        });
    }

    let mut mac_events = mac.engine.subscribe();
    let report = phone
        .engine
        .send_files(&[mac.engine.id()], files, ShareOrigin::Files)
        .await
        .unwrap();
    assert_eq!(report.sent_to, vec![mac.engine.id()]);

    let mut finished = std::collections::HashMap::new();
    while finished.len() < 3 {
        let done = expect(&mut mac_events, "incoming file finished", |e| match e {
            Event::Finished(done) if done.incoming => Some(done.clone()),
            _ => None,
        })
        .await;
        assert_eq!(done.error, None, "transfer of {} failed", done.name);
        finished.insert(done.name.clone(), done.location.unwrap());
    }

    assert_eq!(std::fs::read(&finished["big.bin"]).unwrap(), big);
    assert_eq!(std::fs::read(&finished["small.txt"]).unwrap(), small);
    assert_eq!(std::fs::read(&finished["empty.dat"]).unwrap(), empty);
    assert!(mac.downloads.join("big.bin").exists());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_send_to_several_devices_reaches_all_of_them() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let laptop = node("Laptop").await;
    pair(&phone, &mac).await;
    pair(&phone, &laptop).await;
    wait_until("phone sees both", || online(&phone, mac.engine.id()) && online(&phone, laptop.engine.id())).await;

    let mut mac_events = mac.engine.subscribe();
    let mut laptop_events = laptop.engine.subscribe();
    let report = phone
        .engine
        .send_bytes(
            &[mac.engine.id(), laptop.engine.id()],
            "screenshot.png",
            "image/png",
            random_bytes(200_000, 4),
            ShareOrigin::Screenshot,
        )
        .await
        .unwrap();
    assert_eq!(report.sent_to.len(), 2);

    for events in [&mut mac_events, &mut laptop_events] {
        let done = expect(events, "screenshot delivered", |e| match e {
            Event::Finished(done) if done.incoming => Some(done.clone()),
            _ => None,
        })
        .await;
        assert_eq!(done.error, None);
        assert_eq!(std::fs::read(done.location.unwrap()).unwrap().len(), 200_000);
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_interrupted_download_resumes_from_where_it_stopped() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;

    // Ask before receiving, so the test can leave a half-finished file behind first.
    mac.engine
        .set_device_settings(&phone.engine.id(), DeviceSettings { auto_accept: false, ..Default::default() })
        .unwrap();

    let data = random_bytes(3 * 1024 * 1024 + 7, 9);
    let source_dir = tempfile::tempdir().unwrap();
    let path = source_dir.path().join("movie.bin");
    std::fs::write(&path, &data).unwrap();

    let mut mac_events = mac.engine.subscribe();
    phone
        .engine
        .send_files(
            &[mac.engine.id()],
            vec![OutgoingFile {
                source: path.to_string_lossy().into_owned(),
                name: "movie.bin".into(),
                size: 0,
                mime: "video/mp4".into(),
            }],
            ShareOrigin::Files,
        )
        .await
        .unwrap();
    let offer_id = expect(&mut mac_events, "offer", |e| match e {
        Event::ShareOffered { offer, .. } => Some(offer.id),
        _ => None,
    })
    .await;

    let partial_dir = mac.data.join("incoming");
    std::fs::create_dir_all(&partial_dir).unwrap();
    let partial = partial_dir.join(format!("{}-{:016x}-0.part", phone.engine.id(), offer_id));
    std::fs::write(&partial, &data[..1_500_000]).unwrap();

    mac.engine.accept_offer(phone.engine.id(), offer_id).unwrap();
    let mut first_progress = None;
    let done = expect(&mut mac_events, "resumed download", |e| match e {
        Event::Progress(p) if p.incoming && first_progress.is_none() => {
            first_progress = Some(p.done);
            None
        }
        Event::Finished(done) if done.incoming => Some(done.clone()),
        _ => None,
    })
    .await;
    assert_eq!(done.error, None);
    assert_eq!(first_progress, Some(1_500_000), "it should have started from the partial file");
    assert_eq!(std::fs::read(done.location.unwrap()).unwrap(), data);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_damaged_partial_file_is_detected_and_redone() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;
    mac.engine
        .set_device_settings(&phone.engine.id(), DeviceSettings { auto_accept: false, ..Default::default() })
        .unwrap();

    let data = random_bytes(1_000_000, 3);
    let source_dir = tempfile::tempdir().unwrap();
    let path = source_dir.path().join("doc.bin");
    std::fs::write(&path, &data).unwrap();

    let mut mac_events = mac.engine.subscribe();
    phone
        .engine
        .send_files(
            &[mac.engine.id()],
            vec![OutgoingFile { source: path.to_string_lossy().into_owned(), name: "doc.bin".into(), size: 0, mime: String::new() }],
            ShareOrigin::Files,
        )
        .await
        .unwrap();
    let offer_id = expect(&mut mac_events, "offer", |e| match e {
        Event::ShareOffered { offer, .. } => Some(offer.id),
        _ => None,
    })
    .await;

    // A partial file whose contents are not the start of the real file.
    let partial_dir = mac.data.join("incoming");
    std::fs::create_dir_all(&partial_dir).unwrap();
    let partial = partial_dir.join(format!("{}-{:016x}-0.part", phone.engine.id(), offer_id));
    std::fs::write(&partial, random_bytes(400_000, 77)).unwrap();

    mac.engine.accept_offer(phone.engine.id(), offer_id).unwrap();
    let done = expect(&mut mac_events, "redone download", |e| match e {
        Event::Finished(done) if done.incoming => Some(done.clone()),
        _ => None,
    })
    .await;
    assert_eq!(done.error, None, "it should recover on its own");
    assert_eq!(std::fs::read(done.location.unwrap()).unwrap(), data);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn status_reaches_the_other_device() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;

    phone
        .engine
        .update_status(Status { battery: Some(Battery { level: 64, charging: true, power_save: false }), ..Default::default() })
        .await;
    wait_until("battery arrives", || {
        mac.engine
            .devices()
            .iter()
            .any(|d| d.status.battery.is_some_and(|b| b.level == 64 && b.charging))
    })
    .await;

    // A device that connects later gets the current status without asking.
    let late = node("Late").await;
    pair(&phone, &late).await;
    wait_until("late device gets the battery", || {
        late.engine
            .devices()
            .iter()
            .any(|d| d.status.battery.is_some_and(|b| b.level == 64))
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_third_device_finds_the_first_through_the_second() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let laptop = node("Laptop").await;

    // The phone and the laptop never pair with each other.
    pair(&phone, &mac).await;
    wait_until("phone and mac connect", || online(&phone, mac.engine.id())).await;
    pair(&mac, &laptop).await;

    wait_until("phone learns about the laptop", || {
        phone.engine.devices().iter().any(|d| d.id == laptop.engine.id())
    })
    .await;
    wait_until("laptop and phone connect directly", || {
        online(&phone, laptop.engine.id()) && online(&laptop, phone.engine.id())
    })
    .await;
    assert_eq!(laptop.engine.devices().len(), 2);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_removed_device_is_disconnected_and_cannot_return() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let laptop = node("Laptop").await;
    pair(&phone, &mac).await;
    pair(&phone, &laptop).await;
    wait_until("all connected", || {
        online(&phone, mac.engine.id()) && online(&phone, laptop.engine.id()) && online(&mac, laptop.engine.id())
    })
    .await;

    let mut laptop_events = laptop.engine.subscribe();
    mac.engine.remove_device(laptop.engine.id()).await.unwrap();

    // The removal spreads to the phone, and the laptop learns it is out.
    wait_until("phone forgets the laptop", || {
        phone.engine.devices().iter().all(|d| d.id != laptop.engine.id())
    })
    .await;
    expect(&mut laptop_events, "laptop removed", |e| matches!(e, Event::RemovedFromCircle).then_some(())).await;
    assert!(!laptop.engine.is_member());
    wait_until("mac no longer online with laptop", || !online(&mac, laptop.engine.id())).await;

    // It keeps trying, and keeps being refused.
    tokio::time::sleep(Duration::from_millis(1500)).await;
    assert!(!online(&mac, laptop.engine.id()));
    assert!(!online(&phone, laptop.engine.id()));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_stranger_is_dropped_before_anything_is_processed() {
    use tandem_core::identity::Identity;
    use tandem_core::net;
    use tandem_core::tls::{ALPN_MAIN, Tuning};

    let phone = node("Phone").await;
    // Who the phone is, as a scanner of its QR code would know.
    let offer = phone.engine.create_pairing_offer().unwrap();
    assert!(offer.uri.starts_with("tandem://pair/1?d="));

    let stranger = Identity::generate().unwrap();
    let endpoint = net::make_endpoint(&stranger, 0, Tuning::default()).unwrap();
    let addr: std::net::SocketAddr = format!("127.0.0.1:{}", phone.engine.port()).parse().unwrap();
    // The stranger does not know the phone's key, so it cannot pin it and connects blind.
    let connection = net::dial(&endpoint, &stranger, None, ALPN_MAIN, addr, Tuning::default(), Duration::from_secs(5))
        .await
        .unwrap();
    let reason = tokio::time::timeout(Duration::from_secs(5), connection.closed()).await.unwrap();
    match reason {
        quinn::ConnectionError::ApplicationClosed(close) => assert_eq!(u64::from(close.error_code), 1),
        other => panic!("expected the phone to close the connection, got {other:?}"),
    }
    assert!(phone.engine.devices().is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_dialer_refuses_a_server_with_the_wrong_key() {
    use tandem_core::identity::Identity;
    use tandem_core::net;
    use tandem_core::tls::{ALPN_MAIN, Tuning};

    let phone = node("Phone").await;
    let me = Identity::generate().unwrap();
    let endpoint = net::make_endpoint(&me, 0, Tuning::default()).unwrap();
    let addr: std::net::SocketAddr = format!("127.0.0.1:{}", phone.engine.port()).parse().unwrap();
    let someone_else = Identity::generate().unwrap().public_key();
    let result = net::dial(&endpoint, &me, Some(someone_else), ALPN_MAIN, addr, Tuning::default(), Duration::from_secs(5)).await;
    assert!(result.is_err(), "the handshake must fail when the key is not the pinned one");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_circle_survives_a_restart() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    let mac_id = mac.engine.id();
    phone.engine.shutdown().await;

    // Start the phone again from the same folder.
    let mut cfg = EngineConfig::new(&phone.data, "Phone");
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(&phone.data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: phone.downloads.clone() });
    let again = Engine::start(cfg, secrets, files).await.unwrap();
    assert_eq!(again.devices().len(), 1);
    assert_eq!(again.devices()[0].id, mac_id);

    // It must also find its way back on its own, from the addresses it saved.
    wait_until("phone reconnects after a restart", || {
        again.devices().iter().any(|d| d.id == mac_id && d.online)
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn only_circle_members_can_sign_for_the_hotspot() {
    use tandem_core::hotspot::{ACTION_ON, AUTH_PREFIX, auth_message};

    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let stranger = node("Stranger").await;
    pair(&phone, &mac).await;

    let mac_id = mac.engine.id().to_string();
    let challenge = [9u8; 16];
    let message = auth_message(&challenge, &mac_id, ACTION_ON, 1_700_000_000_000);
    let signature = mac.engine.sign_message(&message);
    assert_eq!(signature.len(), 64);

    assert!(phone.engine.verify_member(&mac.engine.id(), &message, &signature));

    // A different challenge, action, time or device id makes it a different message.
    let other_challenge = auth_message(&[8u8; 16], &mac_id, ACTION_ON, 1_700_000_000_000);
    assert!(!phone.engine.verify_member(&mac.engine.id(), &other_challenge, &signature));
    let other_action = auth_message(&challenge, &mac_id, 2, 1_700_000_000_000);
    assert!(!phone.engine.verify_member(&mac.engine.id(), &other_action, &signature));
    let other_time = auth_message(&challenge, &mac_id, ACTION_ON, 1_700_000_000_001);
    assert!(!phone.engine.verify_member(&mac.engine.id(), &other_time, &signature));

    // A signature that was cut short or damaged.
    assert!(!phone.engine.verify_member(&mac.engine.id(), &message, &signature[..63]));
    assert!(!phone.engine.verify_member(&mac.engine.id(), &message, &[]));
    let mut flipped = signature.clone();
    flipped[10] ^= 1;
    assert!(!phone.engine.verify_member(&mac.engine.id(), &message, &flipped));

    // A valid signature from someone outside the circle, and one that claims to be the mac.
    let stranger_signature = stranger.engine.sign_message(&message);
    assert!(!phone.engine.verify_member(&stranger.engine.id(), &message, &stranger_signature));
    assert!(!phone.engine.verify_member(&mac.engine.id(), &message, &stranger_signature));

    // The same key signing other bytes (a circle statement, say) is not a request.
    let mut other_purpose = b"tandem-circle-statement-v1".to_vec();
    other_purpose.extend_from_slice(&message[AUTH_PREFIX.len()..]);
    let cross = mac.engine.sign_message(&other_purpose);
    assert!(!phone.engine.verify_member(&mac.engine.id(), &message, &cross));

    // Once the mac is removed its old signatures stop working.
    phone.engine.remove_device(mac.engine.id()).await.unwrap();
    assert!(!phone.engine.verify_member(&mac.engine.id(), &message, &signature));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_removed_device_vouches_for_nobody() {
    use tandem_core::hotspot::{ACTION_ON, auth_message};

    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;

    let message = auth_message(&[1u8; 16], &mac.engine.id().to_string(), ACTION_ON, 5);
    let signature = mac.engine.sign_message(&message);
    assert!(phone.engine.verify_member(&mac.engine.id(), &message, &signature));

    phone.engine.remove_device(mac.engine.id()).await.unwrap();
    wait_until("mac learns it was removed", || !mac.engine.is_member()).await;

    // The mac no longer trusts anyone, not even the phone that removed it.
    let from_phone = phone.engine.sign_message(&message);
    assert!(!mac.engine.verify_member(&phone.engine.id(), &message, &from_phone));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn add_address_reconnects_without_waiting_for_the_backoff() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;
    let mac_id = mac.engine.id();

    mac.engine.shutdown().await;
    wait_until("phone notices the mac is gone", || !online(&phone, mac_id)).await;

    // The mac comes back on another port and has forgotten where the phone is, like a
    // Mac that just joined the phone's hotspot.
    let _ = std::fs::remove_file(mac.data.join("addresses.cbor"));
    let mut cfg = EngineConfig::new(&mac.data, "Mac");
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(&mac.data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: mac.downloads.clone() });
    let back = Engine::start(cfg, secrets, files).await.unwrap();
    assert_eq!(back.id(), mac_id);

    // Nothing to dial, so nothing happens yet.
    tokio::time::sleep(Duration::from_millis(600)).await;
    assert!(!online(&phone, mac_id));

    let addr = format!("127.0.0.1:{}", back.port());
    phone.engine.add_address(&mac_id, &addr).unwrap();
    wait_until("phone reaches the mac at the new address", || online(&phone, mac_id)).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn add_address_refuses_strangers_and_nonsense() {
    let phone = node("Phone").await;
    let stranger = node("Stranger").await;
    let err = phone.engine.add_address(&stranger.engine.id(), "127.0.0.1:47820").unwrap_err();
    assert!(matches!(err, tandem_core::Error::NotTrusted));

    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    assert!(phone.engine.add_address(&mac.engine.id(), "not an address").is_err());
    assert!(phone.engine.add_address(&mac.engine.id(), "192.168.43.1").is_err());
    assert!(phone.engine.add_address(&mac.engine.id(), "192.168.43.1:47820").is_ok());
}
