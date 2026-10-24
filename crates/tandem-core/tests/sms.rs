//! Messages: a computer asks the phone for its conversations and gets the answer back from the same call, the phone's
//! switch for that computer is kept in the core, and changes only go to computers that may read along. Real engines, real
//! QUIC, loopback.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::Event;
use tandem_core::platform::DesktopFiles;
use tandem_core::proto::Msg;
use tandem_core::sms::{SmsConversation, SmsMessage, SmsMsg, SmsRefusal, SmsStatus};
use tandem_core::store::{DeviceSettings, FileSecretStore, Store};
use tandem_core::{DeviceId, Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    _dir: tempfile::TempDir,
    _downloads: PathBuf,
}

async fn node(name: &str, caps: &[&str]) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    let downloads = dir.path().join("downloads");
    let mut cfg = EngineConfig::new(&data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    cfg.caps = caps.iter().map(|c| c.to_string()).collect();
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: downloads.clone() });
    let engine = Engine::start(cfg, secrets, files).await.unwrap();
    Node { engine, _dir: dir, _downloads: downloads }
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

/// Nothing of `what` arrives for a moment.
async fn quiet(rx: &mut Receiver<Event>, what: &str, mut pick: impl FnMut(&Event) -> bool) {
    let deadline = tokio::time::Instant::now() + Duration::from_millis(600);
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(remaining, rx.recv()).await {
            Ok(Ok(event)) => assert!(!pick(&event), "{what} arrived: {event:?}"),
            Ok(Err(_)) | Err(_) => return,
        }
    }
}

fn online(node: &Node, id: DeviceId) -> bool {
    node.engine.devices().iter().any(|d| d.id == id && d.online)
}

/// The phone, and a computer paired to it that has said what it can do.
async fn paired(computer_caps: &[&str]) -> (Node, Node) {
    let phone = node("Phone", &["sms"]).await;
    let mac = node("Mac", computer_caps).await;
    let offer = phone.engine.create_pairing_offer().unwrap();
    mac.engine.pair_with_uri(&offer.uri).await.unwrap();
    let mac_id = mac.engine.id();
    wait_until("connected and introduced", || {
        online(&mac, phone.engine.id())
            && phone.engine.devices().iter().any(|d| d.id == mac_id && d.online && computer_caps.iter().all(|c| d.caps.iter().any(|k| k == c)))
    })
    .await;
    (phone, mac)
}

fn conversation(thread: u64) -> SmsConversation {
    SmsConversation {
        thread,
        addresses: vec!["+31612345678".into()],
        name: "Anna".into(),
        snippet: "Zie je morgen".into(),
        date: 1_700_000_000_000,
        unread: 1,
        from_me: false,
    }
}

/// Answers every question of the computer the way the app on the phone would.
fn answer_like_a_phone(phone: &Engine) {
    let engine = phone.clone();
    let mut events = phone.subscribe();
    tokio::spawn(async move {
        while let Ok(event) = events.recv().await {
            let Event::Sms { from, msg } = event else { continue };
            let answer = match msg {
                SmsMsg::ListConversations { req, .. } => SmsMsg::Conversations { req, items: vec![conversation(7)], more: false },
                SmsMsg::Send { req, .. } => SmsMsg::Sent { req, thread: Some(7), error: None },
                _ => continue,
            };
            engine.sms_answer(from, answer).await.unwrap();
        }
    });
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_computer_gets_the_answer_to_its_own_question() {
    let (phone, mac) = paired(&["sms.view"]).await;
    answer_like_a_phone(&phone.engine);

    let answer = mac.engine.sms_ask(phone.engine.id(), |req| SmsMsg::ListConversations { req, before: 0, limit: 0 }).await.unwrap();
    match answer {
        SmsMsg::Conversations { items, more, .. } => assert_eq!((items, more), (vec![conversation(7)], false)),
        other => panic!("wrong answer: {other:?}"),
    }

    let sent = mac
        .engine
        .sms_ask(phone.engine.id(), |req| SmsMsg::Send { req, to: vec!["+31612345678".into()], text: "Hoi".into() })
        .await
        .unwrap();
    assert!(matches!(sent, SmsMsg::Sent { thread: Some(7), error: None, .. }));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_computer_that_is_switched_off_is_refused_before_the_app_hears_of_it_but_may_ask_to_be_let_in() {
    let (phone, mac) = paired(&["sms.view"]).await;
    phone.engine.set_device_settings(&mac.engine.id(), DeviceSettings { messages: false, ..Default::default() }).unwrap();
    let mut phone_events = phone.engine.subscribe();

    let answer = mac.engine.sms_ask(phone.engine.id(), |req| SmsMsg::ListConversations { req, before: 0, limit: 10 }).await.unwrap();
    assert!(matches!(answer, SmsMsg::Refused { why: SmsRefusal::Off, .. }), "got {answer:?}");
    quiet(&mut phone_events, "the question", |e| matches!(e, Event::Sms { msg, .. } if msg.request().is_some())).await;

    // Asking to be let in reaches the person, once in a while and not on every press.
    mac.engine.sms_ask_access(phone.engine.id()).await.unwrap();
    let from = expect(&mut phone_events, "the ask", |e| match e {
        Event::Sms { from, msg: SmsMsg::AskAccess } => Some(*from),
        _ => None,
    })
    .await;
    assert_eq!(from, mac.engine.id());
    mac.engine.sms_ask_access(phone.engine.id()).await.unwrap();
    quiet(&mut phone_events, "a second ask", |e| matches!(e, Event::Sms { msg: SmsMsg::AskAccess, .. })).await;

    // Switched on again, the same question gets through.
    phone.engine.set_device_settings(&mac.engine.id(), DeviceSettings::default()).unwrap();
    answer_like_a_phone(&phone.engine);
    let answer = mac.engine.sms_ask(phone.engine.id(), |req| SmsMsg::ListConversations { req, before: 0, limit: 10 }).await.unwrap();
    assert!(matches!(answer, SmsMsg::Conversations { .. }), "got {answer:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_device_without_messages_says_so_at_once() {
    let (phone, mac) = paired(&["sms.view"]).await;
    // The computer itself has no messages: the phone asking it gets an answer straight from the core.
    let answer = phone.engine.sms_ask(mac.engine.id(), |req| SmsMsg::ListConversations { req, before: 0, limit: 10 }).await.unwrap();
    assert!(matches!(answer, SmsMsg::Refused { why: SmsRefusal::Unavailable, .. }), "got {answer:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_text_that_cannot_go_is_turned_down_on_both_sides() {
    let (phone, mac) = paired(&["sms.view"]).await;
    let mut phone_events = phone.engine.subscribe();
    let mut mac_events = mac.engine.subscribe();

    // The computer does not even ask.
    let local = mac.engine.sms_ask(phone.engine.id(), |req| SmsMsg::Send { req, to: vec!["+3161".into()], text: " ".into() }).await;
    assert!(local.is_err());

    // A computer that asks anyway gets the answer from the core of the phone, and the app is not bothered.
    let raw = SmsMsg::Send { req: 77, to: vec![], text: "hoi".into() };
    assert_eq!(mac.engine.send_msg(&[phone.engine.id()], Msg::Sms(raw)).await, vec![phone.engine.id()]);
    let error = expect(&mut mac_events, "the refusal", |e| match e {
        Event::Sms { msg: SmsMsg::Sent { req: 77, error, .. }, .. } => Some(error.clone()),
        _ => None,
    })
    .await;
    assert!(error.is_some());
    quiet(&mut phone_events, "the send", |e| matches!(e, Event::Sms { msg: SmsMsg::Send { .. }, .. })).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn changes_go_only_to_computers_that_show_messages_and_may() {
    let phone = node("Phone", &["sms"]).await;
    let mac = node("Mac", &["sms.view"]).await;
    let old = node("Old PC", &["clipboard"]).await;
    let guest = node("Guest", &["sms.view"]).await;
    for computer in [&mac, &old, &guest] {
        let offer = phone.engine.create_pairing_offer().unwrap();
        computer.engine.pair_with_uri(&offer.uri).await.unwrap();
    }
    let ids = [mac.engine.id(), old.engine.id(), guest.engine.id()];
    wait_until("all connected", || ids.iter().all(|id| phone.engine.devices().iter().any(|d| d.id == *id && d.online && !d.caps.is_empty()))).await;
    phone.engine.set_device_settings(&guest.engine.id(), DeviceSettings::guest()).unwrap();
    assert_eq!(phone.engine.sms_readers(), vec![mac.engine.id()]);

    let mut mac_events = mac.engine.subscribe();
    let mut guest_events = guest.engine.subscribe();
    let message = SmsMessage {
        id: 41,
        mms: false,
        thread: 7,
        address: String::new(),
        body: "Sent from the phone itself".into(),
        date: 1_700_000_000_500,
        status: SmsStatus::Sent,
        read: true,
        pictures: vec![],
    };
    let reached = phone.engine.sms_changed(vec![conversation(7)], vec![message.clone()]).await;
    assert_eq!(reached, vec![mac.engine.id()]);
    let got = expect(&mut mac_events, "the change", |e| match e {
        Event::Sms { msg: SmsMsg::Changed { messages, .. }, .. } => Some(messages.clone()),
        _ => None,
    })
    .await;
    assert_eq!(got, vec![message]);
    assert!(got[0].from_me());
    quiet(&mut guest_events, "the change", |e| matches!(e, Event::Sms { .. })).await;

    // Nothing to tell, nothing sent.
    assert!(phone.engine.sms_changed(vec![], vec![]).await.is_empty());
}
