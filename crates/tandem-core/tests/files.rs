//! End-to-end tests of looking at the files of another device: real engines, real QUIC, loopback.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use tandem_core::files::{FilePolicy, FsCode, Share};
use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::{DeviceId, Engine, EngineConfig, Error};

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
    // Whatever the machine has in its home folder is not part of a test.
    engine.set_file_default_policy(FilePolicy { shares: Vec::new(), ..FilePolicy::default() }).unwrap();
    Node { engine, _dir: dir }
}

async fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + Duration::from_secs(45);
    while !condition() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

/// A host with something to look at, a client that is connected to it, and the folder that is shared.
async fn pair_up() -> (Node, Node, tempfile::TempDir) {
    let host = node("Phone").await;
    let client = node("Mac").await;
    let offer = host.engine.create_pairing_offer().unwrap();
    client.engine.pair_with_uri(&offer.uri).await.unwrap();
    let (h, c) = (host.engine.id(), client.engine.id());
    wait_until("connected", || host.engine.is_connected(&c) && client.engine.is_connected(&h)).await;
    let shared = tempfile::tempdir().unwrap();
    host.engine.set_file_default_policy(policy(shared.path())).unwrap();
    (host, client, shared)
}

fn policy(dir: &Path) -> FilePolicy {
    FilePolicy { shares: vec![Share { name: "Docs".into(), path: dir.to_string_lossy().into_owned(), write: true }], ..FilePolicy::default() }
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

fn code_of(error: Error) -> FsCode {
    match error {
        Error::Files { code, .. } => code,
        other => panic!("expected an answer from the files, got: {other}"),
    }
}

fn host_id(host: &Node) -> DeviceId {
    host.engine.id()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn another_device_can_look_read_and_change_what_is_shared() {
    let (host, client, shared) = pair_up().await;
    std::fs::write(shared.path().join("b.txt"), "second").unwrap();
    std::fs::write(shared.path().join("A.txt"), "first").unwrap();
    std::fs::create_dir(shared.path().join("sub")).unwrap();
    std::fs::write(shared.path().join("sub/inner.txt"), "inside").unwrap();
    std::fs::write(shared.path().join(".hidden"), "secret").unwrap();

    let files = client.engine.files(host_id(&host));
    let roots = files.roots().await.unwrap();
    assert_eq!(roots.len(), 1);
    assert_eq!(roots[0].name, "Docs");
    assert!(roots[0].write);

    // The list of folders, then a folder: sorted without minding case, folders as folders, nothing hidden.
    let top = files.list("/").await.unwrap();
    assert_eq!(top.iter().map(|e| e.name.as_str()).collect::<Vec<_>>(), vec!["Docs"]);
    let items = files.list("/Docs").await.unwrap();
    assert_eq!(items.iter().map(|e| e.name.as_str()).collect::<Vec<_>>(), vec!["A.txt", "b.txt", "sub"]);
    assert!(items[2].dir);
    assert_eq!(items[0].size, 5);
    assert_eq!(files.stat("/Docs/sub/inner.txt").await.unwrap().size, 6);

    // Parts of a file.
    assert_eq!(files.read("/Docs/b.txt", 0, 1000).await.unwrap(), b"second");
    assert_eq!(files.read("/Docs/b.txt", 2, 3).await.unwrap(), b"con");
    assert_eq!(files.read("/Docs/b.txt", 100, 10).await.unwrap(), b"");

    // Putting a file there: it refuses to overwrite unless told to.
    let local = client._dir.path().join("note.txt");
    std::fs::write(&local, "hello from the mac").unwrap();
    files.upload(&local, "/Docs/sub/note.txt", false, |_, _| {}).await.unwrap();
    assert_eq!(std::fs::read(shared.path().join("sub/note.txt")).unwrap(), b"hello from the mac");
    let err = files.upload(&local, "/Docs/sub/note.txt", false, |_, _| {}).await.unwrap_err();
    assert_eq!(code_of(err), FsCode::Exists);
    std::fs::write(&local, "changed").unwrap();
    files.upload(&local, "/Docs/sub/note.txt", true, |_, _| {}).await.unwrap();
    assert_eq!(std::fs::read(shared.path().join("sub/note.txt")).unwrap(), b"changed");
    // No temporary files stay behind.
    let left: Vec<String> = std::fs::read_dir(shared.path().join("sub")).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
    assert!(left.iter().all(|n| !n.ends_with(".tandem-part")), "left behind: {left:?}");

    // Making, moving and removing.
    files.mkdir("/Docs/new").await.unwrap();
    files.rename("/Docs/sub/note.txt", "/Docs/new/moved.txt", false).await.unwrap();
    assert!(!shared.path().join("sub/note.txt").exists());
    assert_eq!(std::fs::read(shared.path().join("new/moved.txt")).unwrap(), b"changed");
    let err = files.remove("/Docs/new", false).await.unwrap_err();
    assert_eq!(code_of(err), FsCode::NotEmpty);
    files.remove("/Docs/new", true).await.unwrap();
    assert!(!shared.path().join("new").exists());
    files.remove("/Docs/b.txt", false).await.unwrap();
    assert!(!shared.path().join("b.txt").exists());

    // What happened is there to look back at, and looking is not in it.
    let log = host.engine.file_activity();
    assert!(log.iter().any(|a| a.action == "write" && a.ok && a.path == "/Docs/sub/note.txt"));
    assert!(log.iter().any(|a| a.action == "write" && !a.ok));
    assert!(log.iter().any(|a| a.action == "remove" && a.ok));
    assert!(!log.iter().any(|a| a.action == "list"));
    assert!(log.iter().all(|a| a.peer == client.engine.id()));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn big_files_arrive_whole_in_both_directions_and_a_download_carries_on() {
    let (host, client, shared) = pair_up().await;
    let data = noise(12 * 1024 * 1024 + 123, 3);
    std::fs::write(shared.path().join("big.bin"), &data).unwrap();
    let files = client.engine.files(host_id(&host));

    // Down, with progress that ends at the whole.
    let to = client._dir.path().join("big-copy.bin");
    let mut last = (0, 0);
    files.download("/Docs/big.bin", &to, |done, total| last = (done, total)).await.unwrap();
    assert_eq!(std::fs::read(&to).unwrap(), data);
    assert_eq!(last, (data.len() as u64, data.len() as u64));

    // A download that was cut short carries on from what it has.
    let resumed = client._dir.path().join("resumed.bin");
    let part = client._dir.path().join("resumed.bin.tandem-part");
    std::fs::write(&part, &data[..5_000_000]).unwrap();
    let mut first = None;
    files.download("/Docs/big.bin", &resumed, |done, _| { first.get_or_insert(done); }).await.unwrap();
    assert_eq!(first, Some(5_000_000), "it must start where the earlier try ended");
    assert_eq!(std::fs::read(&resumed).unwrap(), data);
    assert!(!part.exists());

    // Up.
    let up = client._dir.path().join("up.bin");
    std::fs::write(&up, noise(9 * 1024 * 1024 + 7, 9)).unwrap();
    files.upload(&up, "/Docs/up.bin", false, |_, _| {}).await.unwrap();
    assert_eq!(std::fs::read(shared.path().join("up.bin")).unwrap(), std::fs::read(&up).unwrap());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn what_the_policy_says_no_to_is_refused() {
    let (host, client, shared) = pair_up().await;
    std::fs::write(shared.path().join("keep.txt"), "mine").unwrap();
    std::fs::create_dir(shared.path().join(".private")).unwrap();
    let files = client.engine.files(host_id(&host));
    let local = client._dir.path().join("x.txt");
    std::fs::write(&local, "x").unwrap();

    // Paths that go where they should not.
    for bad in ["/Docs/../x", "/Docs/a/../../x", "Docs/keep.txt", "/Nothing/keep.txt", "/Docs/.private/x"] {
        let err = files.read(bad, 0, 10).await.unwrap_err();
        assert!(matches!(code_of(err), FsCode::Outside | FsCode::NotFound), "{bad} must not be readable");
    }
    assert!(files.list("/Docs").await.unwrap().iter().all(|e| !e.name.starts_with('.')));

    // Read only: nothing is created, changed, moved or removed.
    let mut read_only = policy(shared.path());
    read_only.write = false;
    host.engine.set_file_default_policy(read_only).unwrap();
    assert_eq!(code_of(files.upload(&local, "/Docs/x.txt", true, |_, _| {}).await.unwrap_err()), FsCode::Denied);
    assert_eq!(code_of(files.mkdir("/Docs/d").await.unwrap_err()), FsCode::Denied);
    assert_eq!(code_of(files.rename("/Docs/keep.txt", "/Docs/other.txt", false).await.unwrap_err()), FsCode::Denied);
    assert_eq!(files.read("/Docs/keep.txt", 0, 10).await.unwrap(), b"mine");
    assert!(files.list("/Docs").await.unwrap().iter().all(|e| e.readonly));

    // Changes are fine, removing is not.
    let mut no_delete = policy(shared.path());
    no_delete.delete = false;
    host.engine.set_file_default_policy(no_delete).unwrap();
    files.upload(&local, "/Docs/x.txt", false, |_, _| {}).await.unwrap();
    assert_eq!(code_of(files.remove("/Docs/keep.txt", false).await.unwrap_err()), FsCode::Denied);
    assert!(shared.path().join("keep.txt").exists());

    // A limit on what comes in.
    let mut small = policy(shared.path());
    small.max_upload = 4;
    host.engine.set_file_default_policy(small).unwrap();
    std::fs::write(&local, "too long for four").unwrap();
    assert_eq!(code_of(files.upload(&local, "/Docs/y.txt", false, |_, _| {}).await.unwrap_err()), FsCode::TooLarge);
    assert!(!shared.path().join("y.txt").exists());

    // And the master switch, for this device alone.
    let mut off = policy(shared.path());
    off.enabled = false;
    host.engine.set_file_policy(&client.engine.id(), off).unwrap();
    assert_eq!(code_of(files.roots().await.unwrap_err()), FsCode::Disabled);
    assert_eq!(code_of(files.list("/Docs").await.unwrap_err()), FsCode::Disabled);
    assert_eq!(code_of(files.read("/Docs/keep.txt", 0, 10).await.unwrap_err()), FsCode::Disabled);
    host.engine.clear_file_policy(&client.engine.id()).unwrap();
    assert!(files.roots().await.is_ok());
}

#[cfg(unix)]
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_link_inside_the_share_cannot_be_used_to_get_out() {
    let (host, client, shared) = pair_up().await;
    let outside = tempfile::tempdir().unwrap();
    std::fs::write(outside.path().join("secret.txt"), "not for you").unwrap();
    std::os::unix::fs::symlink(outside.path(), shared.path().join("door")).unwrap();
    std::os::unix::fs::symlink(outside.path().join("secret.txt"), shared.path().join("shortcut.txt")).unwrap();
    std::fs::write(shared.path().join("fine.txt"), "fine").unwrap();
    let files = client.engine.files(host_id(&host));

    assert_eq!(code_of(files.read("/Docs/door/secret.txt", 0, 100).await.unwrap_err()), FsCode::Outside);
    assert_eq!(code_of(files.read("/Docs/shortcut.txt", 0, 100).await.unwrap_err()), FsCode::Outside);
    assert_eq!(code_of(files.list("/Docs/door").await.unwrap_err()), FsCode::Outside);
    let local = client._dir.path().join("x.txt");
    std::fs::write(&local, "x").unwrap();
    assert_eq!(code_of(files.upload(&local, "/Docs/door/x.txt", true, |_, _| {}).await.unwrap_err()), FsCode::Outside);
    assert!(!outside.path().join("x.txt").exists());
    // The links are not even listed.
    let names: Vec<String> = files.list("/Docs").await.unwrap().into_iter().map(|e| e.name).collect();
    assert_eq!(names, vec!["fine.txt"]);
    let _ = PathBuf::new();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_device_that_is_not_connected_gets_an_honest_answer() {
    let host = node("Phone").await;
    let client = node("Mac").await;
    let offer = host.engine.create_pairing_offer().unwrap();
    client.engine.pair_with_uri(&offer.uri).await.unwrap();
    let id = host.engine.id();
    wait_until("connected", || client.engine.is_connected(&id)).await;
    host.engine.shutdown().await;
    wait_until("gone", || !client.engine.is_connected(&id)).await;
    let err = client.engine.files(id).roots().await.unwrap_err();
    assert!(matches!(err, Error::NotConnected), "got: {err}");
}
