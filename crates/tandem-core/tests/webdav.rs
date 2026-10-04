//! The files of another device as a WebDAV share, asked the way Finder asks: real engines, real QUIC, loopback, and a
//! plain HTTP client that talks to the server on this machine.

#![cfg(feature = "webdav")]

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use data_encoding::BASE64;
use tandem_core::files::{FilePolicy, Share};
use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::webdav::WebDavShare;
use tandem_core::{Engine, EngineConfig};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

struct Node {
    engine: Engine,
    _dir: tempfile::TempDir,
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
    let deadline = std::time::Instant::now() + Duration::from_secs(20);
    while !condition() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

fn policy(dir: &Path) -> FilePolicy {
    FilePolicy { shares: vec![Share { name: "Docs".into(), path: dir.to_string_lossy().into_owned(), write: true }], ..FilePolicy::default() }
}

struct Reply {
    status: u16,
    headers: String,
    body: Vec<u8>,
}

/// One request, the way a simple client makes it, with the password of the share unless it says otherwise.
async fn ask(share: &WebDavShare, method: &str, path: &str, extra: &[(&str, &str)], body: &[u8], password: Option<&str>) -> Reply {
    let port: u16 = share.url.split(':').nth(2).unwrap().split('/').next().unwrap().parse().unwrap();
    let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    let mut head = format!("{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\nContent-Length: {}\r\n", body.len());
    if let Some(password) = password {
        head.push_str(&format!("Authorization: Basic {}\r\n", BASE64.encode(format!("{}:{password}", share.user).as_bytes())));
    }
    for (name, value) in extra {
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    head.push_str("\r\n");
    stream.write_all(head.as_bytes()).await.unwrap();
    stream.write_all(body).await.unwrap();
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).await.unwrap();
    let split = raw.windows(4).position(|w| w == b"\r\n\r\n").expect("a reply with headers");
    let headers = String::from_utf8_lossy(&raw[..split]).into_owned();
    let status = headers.split(' ').nth(1).unwrap().parse().unwrap();
    let mut body = raw[split + 4..].to_vec();
    // The server may chunk what it sends.
    if headers.to_lowercase().contains("transfer-encoding: chunked") {
        body = unchunk(&body);
    }
    Reply { status, headers, body }
}

fn unchunk(mut data: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    loop {
        let line_end = data.windows(2).position(|w| w == b"\r\n").unwrap();
        let size = usize::from_str_radix(std::str::from_utf8(&data[..line_end]).unwrap().trim(), 16).unwrap();
        data = &data[line_end + 2..];
        if size == 0 {
            return out;
        }
        out.extend_from_slice(&data[..size]);
        data = &data[size + 2..];
    }
}

async fn set_up() -> (Node, Node, tempfile::TempDir, WebDavShare) {
    let host = node("Phone").await;
    let client = node("Mac").await;
    host.engine.set_file_default_policy(FilePolicy { shares: Vec::new(), ..FilePolicy::default() }).unwrap();
    let offer = host.engine.create_pairing_offer().unwrap();
    client.engine.pair_with_uri(&offer.uri).await.unwrap();
    let (h, c) = (host.engine.id(), client.engine.id());
    wait_until("connected", || host.engine.is_connected(&c) && client.engine.is_connected(&h)).await;
    let shared = tempfile::tempdir().unwrap();
    host.engine.set_file_default_policy(policy(shared.path())).unwrap();
    let share = tandem_core::webdav::serve(client.engine.files(h), "My Phone").await.unwrap();
    (host, client, shared, share)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_program_that_knows_the_password_can_use_the_files_like_a_drive() {
    let (_host, _client, shared, share) = set_up().await;
    std::fs::write(shared.path().join("a.txt"), "hello drive").unwrap();
    std::fs::create_dir(shared.path().join("sub")).unwrap();
    let pw = Some(share.password.as_str());
    let root = "/My%20Phone";
    assert!(share.url.ends_with("/My%20Phone"));

    // Without the password, or under another name, there is nothing.
    assert_eq!(ask(&share, "PROPFIND", &format!("{root}/"), &[("Depth", "0")], b"", None).await.status, 401);
    assert_eq!(ask(&share, "PROPFIND", &format!("{root}/"), &[("Depth", "0")], b"", Some("wrong")).await.status, 401);

    // What a client asks first, and what it says it can do.
    let options = ask(&share, "OPTIONS", &format!("{root}/"), &[], b"", pw).await;
    assert_eq!(options.status, 200, "{} {}", options.headers, String::from_utf8_lossy(&options.body));
    assert!(options.headers.to_lowercase().contains("dav: 1, 2") || options.headers.to_lowercase().contains("dav: 1,2"), "{}", options.headers);

    // The list: the shared folder first, then what is in it.
    let top = ask(&share, "PROPFIND", &format!("{root}/"), &[("Depth", "1")], b"", pw).await;
    assert_eq!(top.status, 207);
    assert!(String::from_utf8_lossy(&top.body).contains("/My%20Phone/Docs/"), "{}", String::from_utf8_lossy(&top.body));
    let inside = ask(&share, "PROPFIND", &format!("{root}/Docs/"), &[("Depth", "1")], b"", pw).await;
    let text = String::from_utf8_lossy(&inside.body).into_owned();
    assert!(text.contains("a.txt") && text.contains("sub"), "{text}");

    // Reading, whole and a part.
    let whole = ask(&share, "GET", &format!("{root}/Docs/a.txt"), &[], b"", pw).await;
    assert_eq!((whole.status, whole.body.as_slice()), (200, b"hello drive".as_slice()));
    let part = ask(&share, "GET", &format!("{root}/Docs/a.txt"), &[("Range", "bytes=6-10")], b"", pw).await;
    assert_eq!((part.status, part.body.as_slice()), (206, b"drive".as_slice()));
    assert_eq!(ask(&share, "GET", &format!("{root}/Docs/none.txt"), &[], b"", pw).await.status, 404);

    // Writing, making, moving, removing.
    let put = ask(&share, "PUT", &format!("{root}/Docs/sub/new.txt"), &[], b"written through the drive", pw).await;
    assert!(put.status == 201 || put.status == 204, "{} {}", put.status, put.headers);
    assert_eq!(std::fs::read(shared.path().join("sub/new.txt")).unwrap(), b"written through the drive");
    assert_eq!(ask(&share, "MKCOL", &format!("{root}/Docs/made"), &[], b"", pw).await.status, 201);
    assert!(shared.path().join("made").is_dir());
    let moved = ask(&share, "MOVE", &format!("{root}/Docs/sub/new.txt"), &[("Destination", &format!("http://127.0.0.1{root}/Docs/made/moved.txt"))], b"", pw).await;
    assert!(moved.status == 201 || moved.status == 204, "{} {}", moved.status, moved.headers);
    assert_eq!(std::fs::read(shared.path().join("made/moved.txt")).unwrap(), b"written through the drive");
    assert!(!shared.path().join("sub/new.txt").exists());
    let gone = ask(&share, "DELETE", &format!("{root}/Docs/made/moved.txt"), &[], b"", pw).await;
    assert!(gone.status == 204 || gone.status == 200, "{}", gone.status);
    assert!(!shared.path().join("made/moved.txt").exists());
    // Finder takes a lock before it writes, and writes with the lock's token.
    let lock_body = br#"<?xml version="1.0" encoding="utf-8"?><D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner><D:href>finder</D:href></D:owner></D:lockinfo>"#;
    let lock = ask(&share, "LOCK", &format!("{root}/Docs/locked.txt"), &[("Timeout", "Second-60"), ("Content-Type", "application/xml")], lock_body, pw).await;
    assert!(lock.status == 200 || lock.status == 201, "{} {}", lock.status, lock.headers);
    let token = lock
        .headers
        .lines()
        .find(|l| l.to_lowercase().starts_with("lock-token:"))
        .map(|l| l.split_once(':').unwrap().1.trim().to_string())
        .expect("a lock token");
    let locked_put = ask(&share, "PUT", &format!("{root}/Docs/locked.txt"), &[("If", &format!("({token})"))], b"under a lock", pw).await;
    assert!(locked_put.status == 201 || locked_put.status == 204, "{} {}", locked_put.status, locked_put.headers);
    assert_eq!(std::fs::read(shared.path().join("locked.txt")).unwrap(), b"under a lock");
    let unlock = ask(&share, "UNLOCK", &format!("{root}/Docs/locked.txt"), &[("Lock-Token", &token)], b"", pw).await;
    assert_eq!(unlock.status, 204, "{}", unlock.headers);
    // No temporary files stay behind on the phone.
    let leftovers: Vec<String> = walk(shared.path());
    assert!(leftovers.iter().all(|n| !n.contains("tandem-part")), "left behind: {leftovers:?}");
    share.stop();
}

fn walk(dir: &Path) -> Vec<String> {
    let mut out = Vec::new();
    for entry in std::fs::read_dir(dir).unwrap() {
        let entry = entry.unwrap();
        out.push(entry.file_name().to_string_lossy().into_owned());
        if entry.path().is_dir() {
            out.extend(walk(&entry.path()));
        }
    }
    out
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_big_file_goes_through_the_drive_both_ways_and_the_policy_still_holds() {
    let (host, _client, shared, share) = set_up().await;
    let pw = Some(share.password.as_str());
    let data: Vec<u8> = (0..6_000_000u32).map(|i| (i.wrapping_mul(2654435761) >> 13) as u8).collect();
    let put = ask(&share, "PUT", "/My%20Phone/Docs/big.bin", &[], &data, pw).await;
    assert!(put.status == 201 || put.status == 204, "{}", put.status);
    assert_eq!(std::fs::read(shared.path().join("big.bin")).unwrap(), data);
    let got = ask(&share, "GET", "/My%20Phone/Docs/big.bin", &[], b"", pw).await;
    assert_eq!(got.status, 200);
    assert_eq!(got.body, data);

    // What the phone does not allow, the drive does not either.
    let mut read_only = policy(shared.path());
    read_only.write = false;
    host.engine.set_file_default_policy(read_only).unwrap();
    let refused = ask(&share, "PUT", "/My%20Phone/Docs/nope.txt", &[], b"no", pw).await;
    assert!(refused.status == 403 || refused.status == 405 || refused.status == 409 || refused.status == 500, "{}", refused.status);
    assert!(!shared.path().join("nope.txt").exists());
    assert_eq!(ask(&share, "MKCOL", "/My%20Phone/Docs/nodir", &[], b"", pw).await.status >= 400, true);
    let mut off = policy(shared.path());
    off.enabled = false;
    host.engine.set_file_default_policy(off).unwrap();
    assert!(ask(&share, "GET", "/My%20Phone/Docs/big.bin", &[], b"", pw).await.status >= 400);
    share.stop();
}
