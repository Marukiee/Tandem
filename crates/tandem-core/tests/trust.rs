//! What a device of the circle may do on another (P6): refused where it comes in, said back to the device that asked, kept over a
//! restart, and told to the others in the status. Real engines, real QUIC, loopback.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::Event;
use tandem_core::platform::DesktopFiles;
use tandem_core::proto::{InputMsg, Msg};
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::trust::{Permission, TrustLevel};
use tandem_core::{DeviceId, Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    dir: tempfile::TempDir,
}

async fn start(dir: &Path, name: &str) -> Engine {
    let data = dir.join("data");
    let downloads: PathBuf = dir.join("downloads");
    let mut cfg = EngineConfig::new(&data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: downloads });
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

/// Nothing that `pick` wants comes within a moment.
async fn nothing(rx: &mut Receiver<Event>, what: &str, mut pick: impl FnMut(&Event) -> bool) {
    let until = tokio::time::Instant::now() + Duration::from_millis(800);
    while let Ok(Ok(event)) = tokio::time::timeout_at(until, rx.recv()).await {
        assert!(!pick(&event), "{what} came through: {event:?}");
    }
}

fn online(node: &Node, id: DeviceId) -> bool {
    node.engine.devices().iter().any(|d| d.id == id && d.online)
}

async fn pair(a: &Node, b: &Node) -> (DeviceId, DeviceId) {
    let offer = a.engine.create_pairing_offer().unwrap();
    b.engine.pair_with_uri(&offer.uri).await.unwrap();
    let (a_id, b_id) = (a.engine.id(), b.engine.id());
    wait_until("connected", || online(a, b_id) && online(b, a_id)).await;
    (a_id, b_id)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_unlock_comes_through_by_default_and_is_refused_and_said_back_once_switched_off() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let (phone_id, mac_id) = pair(&phone, &mac).await;
    let mut mac_events = mac.engine.subscribe();
    let mut phone_events = phone.engine.subscribe();

    // Default: as before P6.
    assert!(mac.engine.denied_permissions(&phone_id).is_empty());
    phone.engine.send_msg(&[mac_id], Msg::Unlock {}).await;
    expect(&mut mac_events, "the unlock", |e| matches!(e, Event::Unlock { from } if *from == phone_id).then_some(())).await;

    // Refused: the app on the Mac never hears it, and the phone is told why.
    mac.engine.set_permission(&phone_id, Permission::Unlock, false).unwrap();
    phone.engine.send_msg(&[mac_id], Msg::Unlock {}).await;
    let (permission, reason) = expect(&mut phone_events, "the refusal", |e| match e {
        Event::Refused { from, permission, reason } if *from == mac_id => Some((*permission, reason.clone())),
        _ => None,
    })
    .await;
    assert_eq!((permission, reason.as_str()), (Permission::Unlock, tandem_core::trust::REASON_NOT_ALLOWED));
    nothing(&mut mac_events, "a refused unlock", |e| matches!(e, Event::Unlock { .. })).await;

    // Another permission is not touched by it.
    phone.engine.send_msg(&[mac_id], Msg::Lock {}).await;
    expect(&mut mac_events, "the lock", |e| matches!(e, Event::Lock { from } if *from == phone_id).then_some(())).await;

    // Allowed again.
    mac.engine.set_permission(&phone_id, Permission::Unlock, true).unwrap();
    phone.engine.send_msg(&[mac_id], Msg::Unlock {}).await;
    expect(&mut mac_events, "the unlock once allowed", |e| matches!(e, Event::Unlock { from } if *from == phone_id).then_some(())).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn control_is_refused_for_input_messages_and_for_pointer_datagrams() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let (phone_id, mac_id) = pair(&phone, &mac).await;
    let mut mac_events = mac.engine.subscribe();

    let pointer = || tandem_core::session::pointer_datagram(3, 4);
    phone.engine.send_datagram(&mac_id, pointer()).unwrap();
    expect(&mut mac_events, "a pointer move", |e| matches!(e, Event::Input { from, .. } if *from == phone_id).then_some(())).await;

    mac.engine.set_trust_level(&phone_id, TrustLevel::Limited).unwrap();
    assert!(!mac.engine.allowed(&phone_id, Permission::Control));
    for _ in 0..5 {
        phone.engine.send_datagram(&mac_id, pointer()).unwrap();
    }
    phone.engine.send_msg(&[mac_id], Msg::Input(InputMsg::Pointer { dx: 1, dy: 1 })).await;
    nothing(&mut mac_events, "refused input", |e| matches!(e, Event::Input { .. })).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn notifications_do_not_go_to_a_device_that_may_not_see_them() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let (_, mac_id) = pair(&phone, &mac).await;
    let removed = Msg::NotificationRemoved { key: "k".into() };

    assert_eq!(phone.engine.send_msg(&[mac_id], removed.clone()).await, vec![mac_id]);
    phone.engine.set_permission(&mac_id, Permission::SeeNotifications, false).unwrap();
    assert!(phone.engine.send_msg(&[mac_id], removed).await.is_empty());
    // Other messages still go.
    assert_eq!(phone.engine.send_msg(&[mac_id], Msg::Lock {}).await, vec![mac_id]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn permissions_survive_a_restart_and_the_others_see_the_table() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let (phone_id, mac_id) = pair(&phone, &mac).await;

    mac.engine.set_trust_level(&phone_id, TrustLevel::SendOnly).unwrap();
    mac.engine.set_permission(&phone_id, Permission::Calls, true).unwrap();
    assert_eq!(mac.engine.trust_level(&phone_id), TrustLevel::Custom);
    // The file policy holds two of them: switched there, not in a second place.
    assert!(!mac.engine.file_policy(&phone_id).enabled);

    // The phone hears the table and can draw what the Mac lets it do.
    let denied_by_mac = |node: &Node| {
        node.engine.trust_web().into_iter().find(|e| e.from == mac_id && e.to == phone_id).and_then(|e| e.denied)
    };
    wait_until("the table of the Mac on the phone", || denied_by_mac(&phone).is_some_and(|d| !d.is_empty())).await;
    let seen = denied_by_mac(&phone).unwrap();
    assert!(seen.contains(&Permission::Unlock) && !seen.contains(&Permission::Calls));
    assert_eq!(seen.len(), Permission::ALL.len() - 1);
    // What the phone lets the Mac do: nothing switched off.
    let phone_to_mac = phone.engine.trust_web().into_iter().find(|e| e.from == phone_id && e.to == mac_id).unwrap();
    assert_eq!(phone_to_mac.denied, Some(Vec::new()));

    let before = mac.engine.denied_permissions(&phone_id);
    mac.engine.shutdown().await;
    let again = start(mac.dir.path(), "Mac").await;
    assert_eq!(again.denied_permissions(&phone_id), before);
    assert!(!again.allowed(&phone_id, Permission::BrowseFiles));
    again.shutdown().await;
}

/// Each request with a permission of its own: it arrives by default, is refused and said back once switched off, and arrives again
/// once switched back on.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn every_gated_request_arrives_by_default_is_refused_when_off_and_arrives_again_when_on() {
    use tandem_core::pointer_share::PointerShareMsg;
    use tandem_core::proto::{CallAction, CallActionKind};
    use tandem_core::wifi::WifiMsg;

    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let (phone_id, mac_id) = pair(&phone, &mac).await;
    let mut mac_events = mac.engine.subscribe();
    let mut phone_events = phone.engine.subscribe();

    type Arrived = fn(&Event) -> bool;
    let cases: Vec<(Permission, Msg, Arrived)> = vec![
        (Permission::Lock, Msg::Lock {}, |e| matches!(e, Event::Lock { .. })),
        (Permission::Unlock, Msg::Unlock {}, |e| matches!(e, Event::Unlock { .. })),
        (Permission::WifiPassword, Msg::Wifi(WifiMsg::Ask { ssid: "Home".into() }), |e| matches!(e, Event::Wifi { msg: WifiMsg::Ask { .. }, .. })),
        (Permission::Calls, Msg::Dial { number: "0612345678".into() }, |e| matches!(e, Event::Dial { .. })),
        (Permission::Calls, Msg::CallAction(CallAction { id: "1".into(), action: CallActionKind::Hangup }), |e| matches!(e, Event::CallAction { .. })),
        (Permission::Restart, Msg::OpenSettings { page: "restart".into() }, |e| matches!(e, Event::OpenSettings { .. })),
        (Permission::Control, Msg::PointerShare(PointerShareMsg::Carry { text: "hi".into() }), |e| matches!(e, Event::PointerShare { .. })),
    ];
    let mut said_before = std::collections::HashSet::new();
    for (permission, msg, arrived) in cases {
        let what = format!("{msg:?}");
        phone.engine.send_msg(&[mac_id], msg.clone()).await;
        expect(&mut mac_events, &format!("{what} by default"), |e| arrived(e).then_some(())).await;

        mac.engine.set_permission(&phone_id, permission, false).unwrap();
        phone.engine.send_msg(&[mac_id], msg.clone()).await;
        // A refusal of the same kind is said once in ten seconds (the second call message is refused without a word).
        if !said_before.insert(permission) {
            nothing(&mut mac_events, &format!("a refused {what}"), arrived).await;
            mac.engine.set_permission(&phone_id, permission, true).unwrap();
            continue;
        }
        let said = expect(&mut phone_events, &format!("the refusal of {what}"), |e| match e {
            Event::Refused { from, permission: p, .. } if *from == mac_id => Some(*p),
            _ => None,
        })
        .await;
        assert_eq!(said, permission, "{what}");
        nothing(&mut mac_events, &format!("a refused {what}"), arrived).await;

        mac.engine.set_permission(&phone_id, permission, true).unwrap();
        phone.engine.send_msg(&[mac_id], msg).await;
        expect(&mut mac_events, &format!("{what} once allowed again"), |e| arrived(e).then_some(())).await;
    }
}
