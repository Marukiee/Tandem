//! Looking for a newer Tandem on GitHub, fetching it and installing it over this one. On Windows that is the installer; on Linux it is
//! the AppImage, which replaces the file it is run from and starts again (a .deb or .rpm belongs to the package manager, so those only
//! hear that there is a newer version).
//!
//! Fetching goes through curl.exe, which Windows has carried since 2018: it uses the certificates, the proxy and the
//! other settings of the system, and it keeps a second TLS stack out of this program. What is installed is checked
//! twice, against the SHA-256 of the release and against the signature of the update key, the key the Mac app uses
//! too, so a file that was swapped on the way never runs.

use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use base64::Engine as _;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter};

use crate::settings;

const LATEST: &str = "https://api.github.com/repos/Marukiee/Tandem/releases/latest";
const DOWNLOADS: &str = "https://github.com/Marukiee/Tandem/releases/download/";
/// The file of a release that is the program, by system.
fn installer_name() -> &'static str {
    if cfg!(windows) { "Tandem-Windows-x64-setup.exe" } else { "Tandem-Linux-x64.AppImage" }
}

/// Whether this program can replace itself: the installer on Windows, the AppImage on Linux when it was started from one.
pub fn can_update_itself() -> bool {
    cfg!(windows) || appimage_path().is_some()
}

/// The AppImage file that is running, when this is one.
fn appimage_path() -> Option<PathBuf> {
    if cfg!(windows) {
        return None;
    }
    std::env::var_os("APPIMAGE").map(PathBuf::from).filter(|p| p.is_file())
}
/// The public half of the update key. The Mac app has the same file.
const PUBLIC_KEY: &str = include_str!("../../../macos/update-public-key.txt");

/// How long after a check the next one may be asked for without a button: the app is opened and closed often.
const EVERY: Duration = Duration::from_secs(6 * 3600);

#[derive(Clone, Debug, PartialEq)]
pub struct Release {
    pub version: String,
    pub notes: String,
    pub page: String,
    pub size: u64,
    installer: String,
    hash_url: Option<String>,
    signature_url: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub enum State {
    #[default]
    Idle,
    Checking,
    UpToDate,
    Available(Release),
    Downloading { release: Release, done: u64 },
    Installing(Release),
    Failed { reason: String, release: Option<Release> },
}

static STATE: Mutex<State> = Mutex::new(State::Idle);
static LAST_CHECK: Mutex<Option<Instant>> = Mutex::new(None);

fn set(app: &AppHandle, state: State) {
    *STATE.lock().unwrap() = state;
    let _ = app.emit("update", snapshot());
}

/// What the windows are told: a word for the stage and what goes with it.
pub fn snapshot() -> Value {
    match &*STATE.lock().unwrap() {
        State::Idle => json!({ "state": "idle" }),
        State::Checking => json!({ "state": "checking" }),
        State::UpToDate => json!({ "state": "up-to-date" }),
        State::Available(r) => json!({ "state": "available", "version": r.version, "notes": r.notes, "page": r.page, "size": r.size }),
        State::Downloading { release, done } => json!({
            "state": "downloading",
            "version": release.version,
            "progress": if release.size > 0 { (*done as f64 / release.size as f64).clamp(0.0, 1.0) } else { 0.0 },
        }),
        State::Installing(r) => json!({ "state": "installing", "version": r.version }),
        State::Failed { reason, release } => json!({
            "state": "failed",
            "reason": reason,
            "version": release.as_ref().map(|r| r.version.clone()),
            "page": release.as_ref().map(|r| r.page.clone()),
        }),
    }
}

/// Looks now and then while Tandem runs, when the person has not switched that off.
pub fn start(app: AppHandle) {
    // Windows and an AppImage update themselves; a package of the distribution is the package manager's.
    if !cfg!(windows) && !cfg!(target_os = "linux") {
        return;
    }
    std::thread::Builder::new()
        .name("tandem-update".into())
        .spawn(move || {
            // The app is busy starting for a moment, and a check is not urgent.
            std::thread::sleep(Duration::from_secs(45));
            loop {
                if settings::get(&app).auto_update {
                    check(&app, false);
                }
                std::thread::sleep(EVERY);
            }
        })
        .ok();
}

/// Asks GitHub for the newest release. A check nobody asked for stays quiet when it fails: an offline PC is not news.
pub fn check(app: &AppHandle, manual: bool) {
    if !cfg!(windows) && !cfg!(target_os = "linux") {
        return;
    }
    {
        let busy = matches!(&*STATE.lock().unwrap(), State::Checking | State::Downloading { .. } | State::Installing(_));
        if busy {
            return;
        }
        let mut last = LAST_CHECK.lock().unwrap();
        if !manual && last.is_some_and(|at| at.elapsed() < EVERY / 2) {
            return;
        }
        *last = Some(Instant::now());
    }
    set(app, State::Checking);
    let current = app.package_info().version.to_string();
    match fetch_latest() {
        Ok(release) if is_newer(&release.version, &current) => {
            log::info!("version {} is out, this is {current}", release.version);
            set(app, State::Available(release));
        }
        Ok(_) => set(app, State::UpToDate),
        Err(reason) => {
            log::warn!("could not look for an update: {reason}");
            if manual {
                set(app, State::Failed { reason, release: None });
            } else {
                set(app, State::Idle);
            }
        }
    }
}

fn fetch_latest() -> Result<Release, String> {
    let body = curl(&["--header", "Accept: application/vnd.github+json", LATEST])?;
    parse_release(&String::from_utf8_lossy(&body))
}

/// The release feed, reduced to what the updater needs.
pub fn parse_release(json: &str) -> Result<Release, String> {
    let root: Value = serde_json::from_str(json).map_err(|e| format!("the release feed is not readable: {e}"))?;
    let tag = root["tag_name"].as_str().filter(|t| !t.is_empty()).ok_or("the release feed has no version")?;
    let assets = root["assets"].as_array().cloned().unwrap_or_default();
    let find = |name: &str| assets.iter().find(|a| a["name"] == name);
    let name = installer_name();
    let installer = find(name).ok_or("that release has no download for this system")?;
    let url = |asset: Option<&Value>| asset.and_then(|a| a["browser_download_url"].as_str()).map(str::to_string);
    let installer_url = url(Some(installer)).ok_or("the download has no address")?;
    if !is_release_download(&installer_url) {
        return Err("the download is not on the Tandem releases page".into());
    }
    Ok(Release {
        version: tag.trim_start_matches(['v', 'V']).to_string(),
        notes: root["body"].as_str().unwrap_or("").trim().to_string(),
        page: root["html_url"].as_str().unwrap_or("https://github.com/Marukiee/Tandem/releases/latest").to_string(),
        size: installer["size"].as_u64().unwrap_or(0),
        installer: installer_url,
        hash_url: url(find(&format!("{name}.sha256"))).filter(|u| is_release_download(u)),
        signature_url: url(find(&format!("{name}.sig"))).filter(|u| is_release_download(u)),
    })
}

/// Only files from the releases of Tandem itself are downloaded and run.
pub fn is_release_download(url: &str) -> bool {
    url.starts_with(DOWNLOADS)
}

/// Whether `candidate` is a later version than `current`, read the way a person reads them: 0.1.10 comes after 0.1.9.
pub fn is_newer(candidate: &str, current: &str) -> bool {
    let numbers = |text: &str| -> Vec<u64> {
        text.trim_start_matches(['v', 'V'])
            .split(['-', '+'])
            .next()
            .unwrap_or("")
            .split('.')
            .map(|part| part.chars().take_while(char::is_ascii_digit).collect::<String>().parse().unwrap_or(0))
            .collect()
    };
    let (a, b) = (numbers(candidate), numbers(current));
    let size = a.len().max(b.len());
    (0..size).map(|i| (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0))).find(|(x, y)| x != y).is_some_and(|(x, y)| x > y)
}

/// The hash from a `.sha256` file as `sha256sum` writes it: the hash, then the name.
pub fn parse_hash(text: &str) -> Option<String> {
    let first = text.split_whitespace().next()?.to_ascii_lowercase();
    (first.len() == 64 && first.bytes().all(|b| b.is_ascii_hexdigit())).then_some(first)
}

fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

/// Whether `signature` (base64) is the update key's signature over `bytes`.
pub fn signed_by_the_update_key(bytes: &[u8], signature: &str) -> bool {
    let engine = base64::engine::general_purpose::STANDARD;
    let (Ok(key), Ok(signature)) = (engine.decode(PUBLIC_KEY.trim()), engine.decode(signature.trim())) else { return false };
    let Ok(key) = <[u8; 32]>::try_from(key.as_slice()) else { return false };
    tandem_core::identity::verify_signature(&key, bytes, &signature)
}

// ---- Installing ---------------------------------------------------------------------

/// Downloads the release that is waiting, checks it and puts it in place: handed to its installer on Windows, written over the AppImage on
/// Linux. Either way the new program starts and this one ends.
pub fn install(app: &AppHandle) {
    let release = match &*STATE.lock().unwrap() {
        State::Available(r) | State::Failed { release: Some(r), .. } => r.clone(),
        _ => return,
    };
    // A package of the distribution (or a program that is not an AppImage) cannot replace itself: the page of the release is the way.
    if !can_update_itself() {
        set(app, State::Failed { reason: "This copy of Tandem comes from a package, so update it with your package manager or download the new version.".into(), release: Some(release) });
        return;
    }
    let app = app.clone();
    std::thread::spawn(move || match fetch_and_check(&app, &release) {
        Ok(file) => {
            set(&app, State::Installing(release.clone()));
            log::info!("installing {}", file.display());
            if let Err(reason) = put_in_place(&file) {
                set(&app, State::Failed { reason, release: Some(release) });
                return;
            }
            // Out of the way, so the new program can take over.
            std::thread::sleep(Duration::from_millis(800));
            app.exit(0);
        }
        Err(reason) => {
            log::warn!("the update failed: {reason}");
            set(&app, State::Failed { reason, release: Some(release) });
        }
    });
}

#[cfg(windows)]
fn put_in_place(installer: &Path) -> Result<(), String> {
    // Passive: it shows its own progress. Run: it starts Tandem when it is done. Update: it knows this program
    // is running and takes it away first.
    let mut command = Command::new(installer);
    command.args(["/P", "/R", "/UPDATE"]);
    command.spawn().map(|_| ()).map_err(|error| format!("the installer could not start: {error}"))
}

#[cfg(not(windows))]
fn put_in_place(new: &Path) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    let target = appimage_path().ok_or("This copy of Tandem is not an AppImage, so it cannot replace itself")?;
    // Next to the old one, so the swap is a rename inside one folder: the program that runs keeps its file, and the new one is whole
    // the moment it is there.
    let beside = target.with_extension("update");
    std::fs::copy(new, &beside).map_err(|e| format!("the new version cannot be put next to the old one: {e}"))?;
    std::fs::set_permissions(&beside, std::fs::Permissions::from_mode(0o755)).map_err(|e| e.to_string())?;
    std::fs::rename(&beside, &target).map_err(|e| format!("the new version cannot replace the old one: {e}"))?;
    // Started on its own, so it outlives this program.
    Command::new("setsid").arg(&target).arg("--minimized").spawn().map(|_| ()).map_err(|e| format!("the new version could not start: {e}"))
}

fn fetch_and_check(app: &AppHandle, release: &Release) -> Result<PathBuf, String> {
    let dir = std::env::temp_dir().join("Tandem-update");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).map_err(|e| format!("no room to download in {}: {e}", dir.display()))?;
    let target = dir.join(format!("Tandem-{}-{}", release.version, installer_name()));

    set(app, State::Downloading { release: release.clone(), done: 0 });
    download(&release.installer, &target, |done| {
        *STATE.lock().unwrap() = State::Downloading { release: release.clone(), done };
        let _ = app.emit("update", snapshot());
    })?;

    let bytes = std::fs::read(&target).map_err(|e| format!("the download cannot be read: {e}"))?;
    if let Some(url) = &release.hash_url {
        let expected = parse_hash(&String::from_utf8_lossy(&curl(&[url])?)).ok_or("the checksum of the release is not readable")?;
        if expected != sha256_hex(&bytes) {
            return Err("The download is damaged (the checksum does not match)".into());
        }
    }
    // Without a signature the file could be anyone's, so it is not run.
    let url = release.signature_url.as_ref().ok_or("This release is not signed, so Tandem will not install it by itself. Download it from the releases page.")?;
    let signature = String::from_utf8_lossy(&curl(&[url])?).to_string();
    if !signed_by_the_update_key(&bytes, &signature) {
        return Err("The download is not signed by Tandem, so it is not installed".into());
    }
    Ok(target)
}

// ---- curl ----------------------------------------------------------------------------

fn curl_program() -> PathBuf {
    let system = std::env::var_os("SystemRoot").map(|root| Path::new(&root).join("System32").join("curl.exe"));
    system.filter(|path| path.exists()).unwrap_or_else(|| PathBuf::from("curl"))
}

fn curl_command() -> Command {
    let mut command = Command::new(curl_program());
    command.args(["--silent", "--show-error", "--location", "--fail", "--user-agent", "Tandem"]);
    // Inside an AppImage the libraries of the package come first, and the curl of the system then finds a library of the wrong age.
    command.env_remove("LD_LIBRARY_PATH").env_remove("LD_PRELOAD");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // No console window flashing up for a program that has no window.
        command.creation_flags(0x0800_0000);
    }
    command
}

/// Fetches a small answer into memory.
fn curl(args: &[&str]) -> Result<Vec<u8>, String> {
    let output = curl_command()
        .args(["--max-time", "60"])
        .args(args)
        .output()
        .map_err(|e| format!("curl could not start: {e}"))?;
    if output.status.success() {
        Ok(output.stdout)
    } else {
        let why = String::from_utf8_lossy(&output.stderr).trim().to_string();
        Err(if why.is_empty() { "GitHub could not be reached".to_string() } else { why })
    }
}

/// Fetches a file, reporting how much of it is there about four times a second.
fn download(url: &str, target: &Path, mut progress: impl FnMut(u64)) -> Result<(), String> {
    let mut child = curl_command()
        .args(["--max-time", "900", "--output"])
        .arg(target)
        .arg(url)
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| format!("curl could not start: {e}"))?;
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                if status.success() {
                    return Ok(());
                }
                let mut why = String::new();
                if let Some(mut err) = child.stderr.take() {
                    use std::io::Read;
                    let _ = err.read_to_string(&mut why);
                }
                let why = why.trim();
                return Err(if why.is_empty() { "The download failed".to_string() } else { why.to_string() });
            }
            Ok(None) => {
                progress(std::fs::metadata(target).map(|m| m.len()).unwrap_or(0));
                std::thread::sleep(Duration::from_millis(250));
            }
            Err(error) => return Err(format!("the download stopped: {error}")),
        }
    }
}

pub fn dismiss(app: &AppHandle, version: &str) {
    settings::set_dismissed_update(app, version);
    let _ = app.emit("update", snapshot());
}

/// Used by the windows when they open, so they do not have to wait for the next change.
pub fn current(app: &AppHandle) -> Value {
    let mut value = snapshot();
    value["dismissed"] = json!(settings::get(app).dismissed_update);
    value
}

#[cfg(test)]
mod tests {
    use ring::signature::Ed25519KeyPair;

    use super::*;

    /// A feed with the files of both systems, so the test is the same wherever it runs.
    fn feed() -> String {
        let file = |name: &str, size: u64| format!(r#"{{ "name": "{name}", "size": {size}, "browser_download_url": "https://github.com/Marukiee/Tandem/releases/download/v0.1.27/{name}" }}"#);
        let files = [
            file("Tandem.apk", 100),
            file("Tandem-Windows-x64-setup.exe", 4_284_281),
            file("Tandem-Windows-x64-setup.exe.sha256", 95),
            file("Tandem-Windows-x64-setup.exe.sig", 90),
            file("Tandem-Linux-x64.AppImage", 4_284_281),
            file("Tandem-Linux-x64.AppImage.sha256", 95),
            file("Tandem-Linux-x64.AppImage.sig", 90),
        ];
        format!(
            r#"{{ "tag_name": "v0.1.27", "name": "Better panel", "html_url": "https://github.com/Marukiee/Tandem/releases/tag/v0.1.27", "body": "Notes\n\nSomething new", "assets": [{}] }}"#,
            files.join(",")
        )
    }

    #[test]
    fn the_feed_gives_the_installer_its_hash_and_its_signature() {
        let release = parse_release(&feed()).unwrap();
        assert_eq!(release.version, "0.1.27");
        assert_eq!(release.size, 4_284_281);
        assert!(release.installer.ends_with(&format!("/{}", installer_name())));
        assert!(release.hash_url.as_deref().unwrap().ends_with(".sha256"));
        assert!(release.signature_url.as_deref().unwrap().ends_with(".sig"));
        assert!(release.notes.contains("Something new"));
    }

    #[test]
    fn a_release_without_a_download_for_this_system_is_not_an_update() {
        let feed = r#"{ "tag_name": "v0.2.0", "assets": [ { "name": "Tandem.apk", "browser_download_url": "https://github.com/Marukiee/Tandem/releases/download/v0.2.0/Tandem.apk" } ] }"#;
        assert!(parse_release(feed).is_err());
        assert!(parse_release("not json").is_err());
    }

    #[test]
    fn an_installer_from_somewhere_else_is_refused() {
        let name = installer_name();
        let feed = feed().replace(
            &format!("https://github.com/Marukiee/Tandem/releases/download/v0.1.27/{name}\""),
            &format!("https://example.com/{name}\""),
        );
        assert!(parse_release(&feed).is_err());
        assert!(!is_release_download("https://github.com/Someone/Else/releases/download/v1/x.exe"));
        assert!(is_release_download("https://github.com/Marukiee/Tandem/releases/download/v1/x.exe"));
    }

    #[test]
    fn versions_are_read_like_a_person_reads_them() {
        assert!(is_newer("0.1.27", "0.1.26"));
        assert!(is_newer("0.1.10", "0.1.9"));
        assert!(is_newer("0.2.0", "0.1.99"));
        assert!(is_newer("v1.0", "0.9.9"));
        assert!(!is_newer("0.1.26", "0.1.26"));
        assert!(!is_newer("0.1.25", "0.1.26"));
        // A suffix does not count as a later number.
        assert!(!is_newer("0.1.26-preview", "0.1.26"));
    }

    #[test]
    fn a_hash_file_is_read_as_sha256sum_writes_it() {
        let hash = "ae4568472329beefdd803666fd1fcd8adce6ee53539287b9dcfbff627841bcdd";
        assert_eq!(parse_hash(&format!("{hash}  Tandem-Windows-x64-setup.exe\n")).as_deref(), Some(hash));
        assert_eq!(parse_hash(&format!("{}  x", hash.to_uppercase())).as_deref(), Some(hash));
        assert_eq!(parse_hash("short  x"), None);
        assert_eq!(parse_hash(""), None);
        // And the hash of nothing is the well known one.
        assert_eq!(sha256_hex(b""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    #[test]
    fn what_scripts_sign_update_py_signs_is_accepted() {
        // Made with the real update key by scripts/sign-update.py: the script and this check agree, so a release signed
        // in CI is a release the app accepts. Ed25519 signs the same bytes to the same signature every time.
        let signature = "csHxPkSYDsXIaRioOJuxbzSIPiEhkbsvL/s+oCjWEbMaHZJRct6R4S2U1VXuWacTAN15QUkgzO/LgO6BZ05ODQ==";
        assert!(signed_by_the_update_key(b"tandem update test fixture", signature));
        assert!(!signed_by_the_update_key(b"tandem update test fixturf", signature));
    }

    #[test]
    fn only_the_update_key_signs_an_installer() {
        // The update key is secret, so this tests the check with a key of its own: the public key of the app does
        // not match it, and so nothing it signs is accepted.
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&ring::rand::SystemRandom::new()).unwrap();
        let pair = Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap();
        let signature = base64::engine::general_purpose::STANDARD.encode(pair.sign(b"the installer").as_ref());
        assert!(!signed_by_the_update_key(b"the installer", &signature));
        assert!(!signed_by_the_update_key(b"the installer", "not base64 at all"));
        assert!(!signed_by_the_update_key(b"the installer", ""));
        // The public key that ships with the app is a real key.
        let shipped = base64::engine::general_purpose::STANDARD.decode(PUBLIC_KEY.trim()).unwrap();
        assert_eq!(shipped.len(), 32);
    }
}
