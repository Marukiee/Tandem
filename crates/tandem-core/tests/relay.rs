//! Sending between two other devices (P9) with three real engines over loopback: A asks B to send a file of B to C. See
//! docs/TRANSFERS.md.

use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::{Event, TransferDone};
use tandem_core::files::{FilePolicy, Share};
use tandem_core::platform::DesktopFiles;
use tandem_core::relay::RELAY_CAP;
use tandem_core::store::{DeviceSettings, FileSecretStore, Store};
use tandem_core::{DeviceId, Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    dir: tempfile::TempDir,
}

async fn node(name: &str, relay: bool) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    let mut cfg = EngineConfig::new(&data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    if relay {
        cfg.caps.push(RELAY_CAP.into());
    }
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: dir.path().join("downloads") });
    let engine = Engine::start(cfg, secrets, files).await.unwrap();
    engine.set_file_default_policy(FilePolicy { shares: Vec::new(), ..FilePolicy::default() }).unwrap();
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

/// A (the Mac that asks), B (the phone with the file, which shares `Docs`) and C (the Linux pc), all in one circle.
async fn three(b_relays: bool) -> (Node, Node, Node, tempfile::TempDir) {
    let a = node("Mac", false).await;
    let b = node("Phone", b_relays).await;
    let c = node("Linux", false).await;
    for other in [&a, &c] {
        let offer = b.engine.create_pairing_offer().unwrap();
        other.engine.pair_with_uri(&offer.uri).await.unwrap();
    }
    let (ai, bi, ci) = (a.engine.id(), b.engine.id(), c.engine.id());
    wait_until("all connected", || {
        a.engine.is_connected(&bi) && b.engine.is_connected(&ai) && b.engine.is_connected(&ci) && c.engine.is_connected(&bi)
    })
    .await;
    let shared = tempfile::tempdir().unwrap();
    std::fs::create_dir(shared.path().join("sub")).unwrap();
    let policy = FilePolicy { shares: vec![Share { name: "Docs".into(), path: shared.path().to_string_lossy().into_owned(), write: false }], ..FilePolicy::default() };
    b.engine.set_file_default_policy(policy).unwrap();
    (a, b, c, shared)
}

fn noise(len: usize, seed: u8) -> Vec<u8> {
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
async fn a_asks_b_to_send_to_c_and_sees_it_go() {
    let (a, b, c, shared) = three(true).await;
    let data = noise(6 * 1024 * 1024 + 11, 1);
    std::fs::write(shared.path().join("sub/video.mp4"), &data).unwrap();
    let mut a_events = a.engine.subscribe();
    let mut c_events = c.engine.subscribe();

    let answer = a.engine.send_between(b.engine.id(), c.engine.id(), vec!["/Docs/sub/video.mp4".into()]).await;
    assert!(answer.ok, "{answer:?}");
    assert!(!answer.queued);
    assert_eq!(answer.items.len(), 1);
    assert_eq!(answer.items[0].size, data.len() as u64);

    // C gets an ordinary offer from B, and knows on whose request.
    let (from, offer) = expect(&mut c_events, "the offer at C", |e| match e {
        Event::ShareOffered { from, offer } => Some((*from, offer.clone())),
        _ => None,
    })
    .await;
    assert_eq!(from, b.engine.id());
    assert_eq!(offer.id, answer.offer);
    assert_eq!(offer.requested_by, Some(a.engine.id()));
    let at_c = expect(&mut c_events, "the file at C", |e| match e {
        Event::Finished(d) if d.incoming => Some(d.clone()),
        _ => None,
    })
    .await;
    assert!(at_c.error.is_none(), "{at_c:?}");
    assert_eq!(std::fs::read(at_c.location.unwrap()).unwrap(), data);
    let c_record = c.engine.transfers().into_iter().find(|t| t.offer == answer.offer).unwrap();
    assert_eq!(c_record.requested_by, Some(a.engine.id()));
    let b_record = b.engine.transfers().into_iter().find(|t| t.offer == answer.offer).unwrap();
    assert_eq!(b_record.requested_by, Some(a.engine.id()));
    assert!(a.engine.transfers().is_empty(), "A sends nothing itself");

    // A saw it as its own file going to C, with progress and an end.
    let mut progress = 0;
    let done: TransferDone = loop {
        match expect(&mut a_events, "the end at A", |e| match e {
            Event::Progress(p) if !p.incoming => Some(Err(p.clone())),
            Event::Finished(d) if !d.incoming => Some(Ok(d.clone())),
            _ => None,
        })
        .await
        {
            Err(p) => {
                assert_eq!((p.offer, p.peer, p.total), (answer.offer, c.engine.id(), data.len() as u64));
                progress += 1;
            }
            Ok(done) => break done,
        }
    };
    assert!(progress > 0, "A saw no progress");
    assert_eq!((done.offer, done.peer, done.error.clone(), done.name.as_str()), (answer.offer, c.engine.id(), None, "video.mp4"));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn b_refuses_what_a_may_not_read_and_a_device_outside_the_circle() {
    let (a, b, c, shared) = three(true).await;
    std::fs::write(shared.path().join("ok.txt"), b"ok").unwrap();
    std::fs::write(shared.path().join(".hidden"), b"no").unwrap();
    let outside_share = tempfile::tempdir().unwrap();
    std::fs::write(outside_share.path().join("secret.txt"), b"no").unwrap();
    let ask = |paths: &[&str], to: DeviceId| a.engine.send_between(b.engine.id(), to, paths.iter().map(|p| p.to_string()).collect());
    let ci = c.engine.id();

    let reason = |answer: tandem_core::relay::RelayAnswer| {
        assert!(!answer.ok);
        answer.reason.unwrap()
    };
    assert_eq!(reason(ask(&["/Docs/../secret.txt"], ci).await), "not allowed");
    // What A cannot see in Files is not there for A, as Files says it: a folder that is not shared, a path outside, a hidden file.
    assert_eq!(reason(ask(&["/Other/secret.txt"], ci).await), "gone");
    // A path of B's own disk is no way in: an unknown share on Unix, not a path at all on Windows (it has `\`).
    let own_disk = reason(ask(&[&format!("{}/secret.txt", outside_share.path().display())], ci).await);
    assert!(own_disk == "gone" || own_disk == "not allowed", "{own_disk}");
    assert_eq!(reason(ask(&["/Docs/.hidden"], ci).await), "gone");
    assert_eq!(reason(ask(&["/Docs/missing.txt"], ci).await), "gone");
    assert_eq!(reason(ask(&["/Docs/sub"], ci).await), "not a file");
    assert_eq!(reason(ask(&["/Docs/ok.txt"], DeviceId::from_bytes([9; 16])).await), "not in circle");
    // One bad path and nothing goes, not even the good one.
    assert_eq!(reason(ask(&["/Docs/ok.txt", "/Docs/missing.txt"], ci).await), "gone");
    assert!(b.engine.transfers().is_empty() && c.engine.transfers().is_empty());

    // A device that does not let A see its files lets A send none of them either.
    b.engine.set_file_default_policy(FilePolicy { enabled: false, ..FilePolicy::default() }).unwrap();
    assert_eq!(reason(ask(&["/Docs/ok.txt"], ci).await), "not allowed");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn stop_on_a_stops_it_at_c() {
    let (a, b, c, shared) = three(true).await;
    std::fs::write(shared.path().join("big.bin"), noise(2_000_000, 2)).unwrap();
    c.engine.set_device_settings(&b.engine.id(), DeviceSettings { auto_accept: false, ..Default::default() }).unwrap();
    let mut a_events = a.engine.subscribe();
    let mut c_events = c.engine.subscribe();
    let answer = a.engine.send_between(b.engine.id(), c.engine.id(), vec!["/Docs/big.bin".into()]).await;
    assert!(answer.ok, "{answer:?}");
    expect(&mut c_events, "the offer at C", |e| matches!(e, Event::ShareOffered { .. }).then_some(())).await;

    a.engine.cancel_transfer(c.engine.id(), answer.offer, false).await;
    let at_c = expect(&mut c_events, "the stop at C", |e| match e {
        Event::Finished(d) if d.incoming => Some(d.clone()),
        _ => None,
    })
    .await;
    assert_eq!(at_c.error.as_deref(), Some("cancelled"));
    let at_a = expect(&mut a_events, "the stop at A", |e| match e {
        Event::Finished(d) if !d.incoming => Some(d.clone()),
        _ => None,
    })
    .await;
    assert_eq!((at_a.offer, at_a.peer, at_a.error.as_deref()), (answer.offer, c.engine.id(), Some("cancelled")));
    assert!(std::fs::read_dir(c.dir.path().join("downloads")).map(|d| d.count() == 0).unwrap_or(true));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_device_that_does_not_relay_gets_a_clear_answer() {
    let (a, b, c, shared) = three(false).await;
    std::fs::write(shared.path().join("ok.txt"), b"ok").unwrap();
    let started = std::time::Instant::now();
    let answer = a.engine.send_between(b.engine.id(), c.engine.id(), vec!["/Docs/ok.txt".into()]).await;
    assert_eq!((answer.ok, answer.reason.as_deref()), (false, Some("unsupported")));
    assert!(started.elapsed() < Duration::from_secs(5), "known up front, not after a wait");
    assert!(b.engine.transfers().is_empty());
}
