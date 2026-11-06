//! A nudge from a computer reaches the phone, and only the phone. Real engines over loopback.

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::Event;
use tandem_core::ids::Platform;
use tandem_core::nudge::{self, NudgeError};
use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::{Engine, EngineConfig};

async fn start(dir: &Path, name: &str, platform: Platform) -> Engine {
    let data = dir.join(name);
    let mut cfg = EngineConfig::new(&data, name);
    cfg.platform = platform;
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: dir.join("downloads") });
    Engine::start(cfg, secrets, files).await.unwrap()
}

async fn pair(showing: &Engine, scanning: &Engine) {
    let offer = showing.create_pairing_offer().unwrap();
    scanning.pair_with_uri(&offer.uri).await.unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(45);
    while !showing.devices().iter().any(|d| d.id == scanning.id() && d.online) {
        assert!(std::time::Instant::now() < deadline, "not connected");
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_nudge_goes_to_the_phone_and_not_twice_in_a_row() {
    let dir = tempfile::tempdir().unwrap();
    let laptop = start(dir.path(), "Laptop", Platform::Linux).await;
    let other = start(dir.path(), "Other", Platform::Windows).await;
    pair(&laptop, &other).await;
    // Another computer is no one to tell.
    assert_eq!(laptop.nudge("Render klaar").await, Err(NudgeError::NoPhone));

    let phone = start(dir.path(), "Phone", Platform::Android).await;
    pair(&laptop, &phone).await;
    let mut at_phone = phone.subscribe();
    let mut at_other = other.subscribe();

    // Through the channel of this machine, as the command does it.
    let channel = dir.path().join("Laptop");
    let serving = tokio::spawn({
        let laptop = laptop.clone();
        let channel = channel.clone();
        async move { nudge::serve(laptop, &channel).await }
    });
    let ask = |text: &'static str| {
        let channel = channel.clone();
        async move { tokio::task::spawn_blocking(move || nudge::ask(&channel, text)).await.unwrap() }
    };
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    let mut sent = ask("Render\nklaar").await;
    while sent == Err("Tandem is not running".to_string()) && std::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(40)).await;
        sent = ask("Render\nklaar").await;
    }
    assert_eq!(sent, Ok(()));

    let got = tokio::time::timeout(Duration::from_secs(30), async {
        loop {
            if let Ok(Event::Notification { from, notification }) = at_phone.recv().await {
                return (from, notification);
            }
        }
    })
    .await
    .expect("it arrives");
    assert_eq!(got.0, laptop.id());
    assert_eq!(got.1.app_id, nudge::APP_ID);
    assert_eq!(got.1.text, "Render klaar");
    assert_eq!(got.1.title, "Laptop");

    assert_eq!(ask("Nog een").await, Err(NudgeError::TooSoon.to_string()));
    assert_eq!(ask("  ").await, Err(NudgeError::Empty.to_string()));

    // The other computer heard nothing.
    let heard = tokio::time::timeout(Duration::from_millis(400), async {
        loop {
            if let Ok(Event::Notification { .. }) = at_other.recv().await {
                return;
            }
        }
    })
    .await;
    assert!(heard.is_err());
    serving.abort();
}
