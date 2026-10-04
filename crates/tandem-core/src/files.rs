//! Letting another device in the circle look at this device's files.
//!
//! The device that is looked at decides, and decides in here and not in the app: which folders it offers (shares),
//! and whether the other device may change or delete anything. Nothing outside a share can be reached. A path is
//! checked part by part, and what a link on disk points to must still lie inside the share.
//!
//! One request is one stream. The first byte is `STREAM_FS`, then the request, and for a write the bytes of the file.
//! The answer is a frame, and for a read the bytes after it. See docs/FILES.md.

use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

use quinn::{RecvStream, SendStream};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};

use crate::engine::{Inner, rand_u64};
use crate::error::{Error, Result};
use crate::ids::DeviceId;
use crate::proto::{self, MAX_CONTROL_FRAME, MAX_SMALL_FRAME, STREAM_FS};
use crate::store::Store;

const CHUNK: usize = 256 * 1024;
/// How many names one answer to a listing holds. A bigger folder comes in several answers.
const PAGE: u32 = 4000;
/// What a read into memory may be at most. A bigger file is downloaded to a file instead.
const MAX_IN_MEMORY: u64 = 64 * 1024 * 1024;
const ACTIVITY_KEPT: usize = 200;

fn yes() -> bool {
    true
}

fn page() -> u32 {
    PAGE
}

// ---- What a person chooses --------------------------------------------------------

/// A folder that is offered. The name is what the other device sees, the path is where it is on this one.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Share {
    pub name: String,
    pub path: String,
    /// Whether this folder may be changed at all. It can only narrow what the policy allows, never widen it.
    #[serde(default = "yes")]
    pub write: bool,
}

/// What one device may do with this device's files.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FilePolicy {
    /// The master switch. Off, the other device sees nothing and is told so.
    #[serde(default = "yes")]
    pub enabled: bool,
    /// The folders that are offered. None, and nothing is.
    #[serde(default)]
    pub shares: Vec<Share>,
    /// Creating, overwriting and renaming.
    #[serde(default = "yes")]
    pub write: bool,
    /// Removing.
    #[serde(default = "yes")]
    pub delete: bool,
    /// Files and folders that start with a dot, and the ones the system calls hidden.
    #[serde(default)]
    pub hidden: bool,
    /// The biggest file the other device may put here, in bytes. Nothing limits it when this is 0.
    #[serde(default)]
    pub max_upload: u64,
}

impl Default for FilePolicy {
    fn default() -> Self {
        FilePolicy { enabled: true, shares: Vec::new(), write: true, delete: true, hidden: false, max_upload: 0 }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
struct Policies {
    #[serde(default)]
    default: FilePolicy,
    #[serde(default)]
    devices: HashMap<String, FilePolicy>,
}

/// One thing another device did to the files here, for the person to look back at.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileActivity {
    pub at_ms: u64,
    pub peer: DeviceId,
    pub action: String,
    pub path: String,
    pub ok: bool,
    pub detail: String,
}

/// The policies, and what has happened. Lives in the engine.
pub struct FileService {
    store: Store,
    policies: RwLock<Policies>,
    activity: Mutex<VecDeque<FileActivity>>,
}

const POLICY_FILE: &str = "files.cbor";

impl FileService {
    pub fn new(store: Store) -> FileService {
        let policies = match store.read_cbor::<Policies>(POLICY_FILE) {
            Ok(Some(policies)) => policies,
            // Nothing chosen yet: everything allowed, which is what was asked for, but only the phone offers folders
            // from the start. A computer has no screen yet to show and change what it offers, and until it has one
            // nothing should leave it that a person has not seen being offered.
            _ => Policies {
                default: FilePolicy { shares: if cfg!(target_os = "android") { default_shares() } else { Vec::new() }, ..FilePolicy::default() },
                devices: HashMap::new(),
            },
        };
        FileService { store, policies: RwLock::new(policies), activity: Mutex::new(VecDeque::new()) }
    }

    /// What the device may do: its own choices, or the default for devices that have none.
    pub fn policy(&self, id: &DeviceId) -> FilePolicy {
        let policies = self.policies.read().unwrap();
        policies.devices.get(&id.to_string()).cloned().unwrap_or_else(|| policies.default.clone())
    }

    /// Whether the device has choices of its own.
    pub fn has_own_policy(&self, id: &DeviceId) -> bool {
        self.policies.read().unwrap().devices.contains_key(&id.to_string())
    }

    pub fn default_policy(&self) -> FilePolicy {
        self.policies.read().unwrap().default.clone()
    }

    pub fn set_policy(&self, id: &DeviceId, policy: FilePolicy) -> Result<()> {
        self.change(|policies| {
            policies.devices.insert(id.to_string(), policy);
        })
    }

    /// Back to the default for this device.
    pub fn clear_policy(&self, id: &DeviceId) -> Result<()> {
        self.change(|policies| {
            policies.devices.remove(&id.to_string());
        })
    }

    pub fn set_default_policy(&self, policy: FilePolicy) -> Result<()> {
        self.change(|policies| policies.default = policy)
    }

    fn change(&self, edit: impl FnOnce(&mut Policies)) -> Result<()> {
        let mut policies = self.policies.write().unwrap();
        edit(&mut policies);
        self.store.write_cbor(POLICY_FILE, &*policies)
    }

    pub fn activity(&self) -> Vec<FileActivity> {
        self.activity.lock().unwrap().iter().rev().cloned().collect()
    }

    fn note(&self, peer: DeviceId, action: &str, path: &str, result: &std::result::Result<(), FsFail>) {
        let mut log = self.activity.lock().unwrap();
        if log.len() >= ACTIVITY_KEPT {
            log.pop_front();
        }
        log.push_back(FileActivity {
            at_ms: now_ms(),
            peer,
            action: action.to_string(),
            path: path.to_string(),
            ok: result.is_ok(),
            detail: result.as_ref().err().map(|e| e.message.clone()).unwrap_or_default(),
        });
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// The folders worth offering: the ones people put things in. What an app puts in the default when it can show them.
pub fn default_shares() -> Vec<Share> {
    #[cfg(target_os = "android")]
    {
        // Needs the permission to see all files, which the app asks for.
        vec![Share { name: "Phone".into(), path: "/storage/emulated/0".into(), write: true }]
    }
    #[cfg(not(target_os = "android"))]
    {
        let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")).map(PathBuf::from);
        let Some(home) = home else { return Vec::new() };
        ["Downloads", "Documents", "Desktop"]
            .iter()
            .map(|name| (name, home.join(name)))
            .filter(|(_, path)| path.is_dir())
            .map(|(name, path)| Share { name: name.to_string(), path: path.to_string_lossy().into_owned(), write: true })
            .collect()
    }
}

// ---- On the wire ------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum FsRequest {
    /// The folders that are offered.
    Roots,
    List {
        path: String,
        #[serde(default)]
        offset: u64,
        #[serde(default = "page")]
        limit: u32,
    },
    Stat { path: String },
    /// `len` can be as big as you like: the answer holds what there is.
    Read { path: String, offset: u64, len: u64 },
    /// The bytes of the file follow the request. It lands as a whole or not at all.
    Write {
        path: String,
        size: u64,
        #[serde(default)]
        overwrite: bool,
    },
    Mkdir { path: String },
    Remove {
        path: String,
        #[serde(default)]
        recursive: bool,
    },
    Rename {
        from: String,
        to: String,
        #[serde(default)]
        overwrite: bool,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum FsResponse {
    Ok,
    Roots { roots: Vec<FsRoot> },
    Entries {
        items: Vec<FsEntry>,
        /// There are more after these: ask again from where they end.
        more: bool,
    },
    Entry(FsEntry),
    /// The answer to a read: this many bytes follow.
    Data { size: u64, modified_ms: u64, sent: u64 },
    Err { code: FsCode, message: String },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FsCode {
    NotFound,
    /// The policy says no to this.
    Denied,
    /// The master switch for this device is off.
    Disabled,
    /// A path that goes outside what is offered, or is not a path at all.
    Outside,
    Exists,
    NotEmpty,
    NotDir,
    IsDir,
    TooLarge,
    Unsupported,
    Io,
    #[serde(other)]
    Unknown,
}

impl FsCode {
    pub fn as_str(self) -> &'static str {
        match self {
            FsCode::NotFound => "not_found",
            FsCode::Denied => "denied",
            FsCode::Disabled => "disabled",
            FsCode::Outside => "outside",
            FsCode::Exists => "exists",
            FsCode::NotEmpty => "not_empty",
            FsCode::NotDir => "not_dir",
            FsCode::IsDir => "is_dir",
            FsCode::TooLarge => "too_large",
            FsCode::Unsupported => "unsupported",
            FsCode::Io => "io",
            FsCode::Unknown => "unknown",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FsRoot {
    pub name: String,
    /// Whether this one may be changed by the device that asks.
    pub write: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FsEntry {
    pub name: String,
    pub dir: bool,
    pub size: u64,
    pub modified_ms: u64,
    /// The device that asks may not change this one.
    pub readonly: bool,
}

#[derive(Debug, Clone)]
pub struct FsFail {
    pub code: FsCode,
    pub message: String,
    /// It went wrong after the bytes of a file had started to flow, so an answer frame can no longer be told apart from them.
    pub late: bool,
}

impl FsFail {
    fn new(code: FsCode, message: impl Into<String>) -> FsFail {
        FsFail { code, message: message.into(), late: false }
    }

    fn late(mut self) -> FsFail {
        self.late = true;
        self
    }

    fn io(err: &std::io::Error) -> FsFail {
        use std::io::ErrorKind::*;
        let code = match err.kind() {
            NotFound => FsCode::NotFound,
            PermissionDenied => FsCode::Denied,
            AlreadyExists => FsCode::Exists,
            DirectoryNotEmpty => FsCode::NotEmpty,
            NotADirectory => FsCode::NotDir,
            IsADirectory => FsCode::IsDir,
            _ => FsCode::Io,
        };
        FsFail { code, message: err.to_string(), late: false }
    }
}

impl From<FsFail> for Error {
    fn from(fail: FsFail) -> Error {
        Error::Files { code: fail.code, message: fail.message }
    }
}

// ---- Paths ------------------------------------------------------------------------

/// A path as the other device says it: `/Share/folder/file`.
fn parts(path: &str) -> std::result::Result<Vec<&str>, FsFail> {
    if !path.starts_with('/') {
        return Err(FsFail::new(FsCode::Outside, "a path starts with a slash"));
    }
    let mut out = Vec::new();
    for part in path.split('/') {
        if part.is_empty() {
            continue;
        }
        if part == "." || part == ".." {
            return Err(FsFail::new(FsCode::Outside, "a path does not go up or stay"));
        }
        if part.chars().any(|c| c == '\\' || c == '\0' || c.is_control()) {
            return Err(FsFail::new(FsCode::Outside, "that name has characters a name cannot have"));
        }
        // Where a colon means a drive or a stream, and a few names belong to the system.
        #[cfg(windows)]
        {
            if part.contains(':') || part.ends_with('.') || part.ends_with(' ') || is_reserved_name(part) {
                return Err(FsFail::new(FsCode::Outside, "that name is not allowed on Windows"));
            }
        }
        out.push(part);
    }
    Ok(out)
}

#[cfg(windows)]
fn is_reserved_name(part: &str) -> bool {
    let stem = part.split('.').next().unwrap_or("").to_ascii_uppercase();
    matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || (stem.len() == 4 && (stem.starts_with("COM") || stem.starts_with("LPT")) && stem.as_bytes()[3].is_ascii_digit())
}

fn is_hidden_name(name: &str) -> bool {
    name.starts_with('.')
}

#[cfg(windows)]
fn is_hidden_on_disk(meta: &std::fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const HIDDEN: u32 = 0x2;
    meta.file_attributes() & HIDDEN != 0
}

#[cfg(not(windows))]
fn is_hidden_on_disk(_meta: &std::fs::Metadata) -> bool {
    false
}

/// What a path points to on this device, once it has been checked.
struct Resolved {
    share: Share,
    /// The folder of the share, as the system spells it.
    root: PathBuf,
    /// Where it is. The file may not exist yet.
    real: PathBuf,
    /// The parts after the share's name.
    rest: Vec<String>,
}

/// What a request wants to do, which decides what the policy has to allow.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Need {
    Read,
    Change,
    Remove,
}

fn check_policy(policy: &FilePolicy, share: &Share, need: Need) -> std::result::Result<(), FsFail> {
    match need {
        Need::Read => Ok(()),
        Need::Change if policy.write && share.write => Ok(()),
        Need::Remove if policy.delete && share.write => Ok(()),
        Need::Change => Err(FsFail::new(FsCode::Denied, "this device is not allowed to change files here")),
        Need::Remove => Err(FsFail::new(FsCode::Denied, "this device is not allowed to remove files here")),
    }
}

/// `path` as a place on this device, if the policy offers it and it does not leave the share.
fn resolve(policy: &FilePolicy, path: &str, need: Need) -> std::result::Result<Resolved, FsFail> {
    if !policy.enabled {
        return Err(FsFail::new(FsCode::Disabled, "this device does not share its files with you"));
    }
    let names = parts(path)?;
    let Some((first, rest)) = names.split_first() else {
        return Err(FsFail::new(FsCode::Outside, "that is the list of folders, not a folder"));
    };
    let share = policy
        .shares
        .iter()
        .find(|s| s.name == *first)
        .cloned()
        .ok_or_else(|| FsFail::new(FsCode::NotFound, format!("there is no shared folder called {first}")))?;
    check_policy(policy, &share, need)?;
    if !policy.hidden && rest.iter().any(|part| is_hidden_name(part)) {
        return Err(FsFail::new(FsCode::NotFound, "not found"));
    }
    let root = std::fs::canonicalize(&share.path).map_err(|e| FsFail::new(FsCode::Io, format!("the folder {first} cannot be opened: {e}")))?;
    let mut real = root.clone();
    for part in rest {
        real.push(part);
    }
    inside(&root, &real)?;
    if !policy.hidden && real != root {
        if let Ok(meta) = std::fs::metadata(&real) {
            if is_hidden_on_disk(&meta) {
                return Err(FsFail::new(FsCode::NotFound, "not found"));
            }
        }
    }
    Ok(Resolved { share, root, real, rest: rest.iter().map(|s| s.to_string()).collect() })
}

/// The place has to be in the share, also after links on disk have been followed. A place that does not exist yet is
/// judged by the folder it would be in.
fn inside(root: &Path, real: &Path) -> std::result::Result<(), FsFail> {
    let outside = || FsFail::new(FsCode::Outside, "that leads outside the shared folder");
    match std::fs::canonicalize(real) {
        Ok(canonical) => {
            if canonical.starts_with(root) {
                Ok(())
            } else {
                Err(outside())
            }
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            // A link that points nowhere is no way in either: it does not canonicalize, and it is not a new name.
            if std::fs::symlink_metadata(real).is_ok() {
                return Err(outside());
            }
            let parent = real.parent().ok_or_else(outside)?;
            let canonical = std::fs::canonicalize(parent).map_err(|e| FsFail::io(&e))?;
            if canonical.starts_with(root) { Ok(()) } else { Err(outside()) }
        }
        Err(e) => Err(FsFail::io(&e)),
    }
}

fn entry_of(name: &str, meta: &std::fs::Metadata, share_writable: bool, policy: &FilePolicy) -> FsEntry {
    let modified_ms = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    FsEntry {
        name: name.to_string(),
        dir: meta.is_dir(),
        size: if meta.is_dir() { 0 } else { meta.len() },
        modified_ms,
        readonly: !(policy.write && share_writable) || meta.permissions().readonly(),
    }
}

// ---- The device that is looked at -------------------------------------------------

impl Inner {
    /// Answers one request of another device.
    pub(crate) async fn serve_fs(self: Arc<Self>, peer: DeviceId, mut send: SendStream, mut recv: RecvStream) -> Result<()> {
        let request: FsRequest = match proto::recv(&mut recv, MAX_SMALL_FRAME).await {
            Ok(request) => request,
            Err(_) => {
                let reply = FsResponse::Err { code: FsCode::Unsupported, message: "that request is not understood".into() };
                let _ = proto::send(&mut send, &reply).await;
                let _ = send.finish();
                return Ok(());
            }
        };
        let policy = self.file_service.policy(&peer);
        let (action, path) = describe(&request);
        let result = handle(&policy, request, &mut send, &mut recv).await;
        match &result {
            // The reader counts the bytes it was promised, so it is told by the stream breaking, not by a frame.
            Err(fail) if fail.late => {
                let _ = send.reset(1u32.into());
            }
            Err(fail) => {
                let reply = FsResponse::Err { code: fail.code, message: fail.message.clone() };
                let _ = proto::send(&mut send, &reply).await;
                let _ = send.finish();
            }
            Ok(()) => {
                let _ = send.finish();
            }
        }
        // Looking is not worth a line each; changing things is.
        if !matches!(action, "list" | "stat" | "roots") || result.is_err() {
            self.file_service.note(peer, action, &path, &result);
        }
        Ok(())
    }
}

fn describe(request: &FsRequest) -> (&'static str, String) {
    match request {
        FsRequest::Roots => ("roots", "/".into()),
        FsRequest::List { path, .. } => ("list", path.clone()),
        FsRequest::Stat { path } => ("stat", path.clone()),
        FsRequest::Read { path, .. } => ("read", path.clone()),
        FsRequest::Write { path, .. } => ("write", path.clone()),
        FsRequest::Mkdir { path } => ("mkdir", path.clone()),
        FsRequest::Remove { path, .. } => ("remove", path.clone()),
        FsRequest::Rename { from, to, .. } => ("rename", format!("{from} to {to}")),
    }
}

async fn blocking<T: Send + 'static>(work: impl FnOnce() -> std::result::Result<T, FsFail> + Send + 'static) -> std::result::Result<T, FsFail> {
    tokio::task::spawn_blocking(work)
        .await
        .map_err(|e| FsFail::new(FsCode::Io, format!("the work was cut short: {e}")))?
}

async fn reply(send: &mut SendStream, response: &FsResponse) -> std::result::Result<(), FsFail> {
    proto::send(send, response).await.map_err(|e| FsFail::new(FsCode::Io, e.to_string()))
}

async fn handle(
    policy: &FilePolicy,
    request: FsRequest,
    send: &mut SendStream,
    recv: &mut RecvStream,
) -> std::result::Result<(), FsFail> {
    match request {
        FsRequest::Roots => {
            if !policy.enabled {
                return Err(FsFail::new(FsCode::Disabled, "this device does not share its files with you"));
            }
            let roots = policy.shares.iter().map(|s| FsRoot { name: s.name.clone(), write: policy.write && s.write }).collect();
            reply(send, &FsResponse::Roots { roots }).await
        }
        FsRequest::List { path, offset, limit } => {
            if !policy.enabled {
                return Err(FsFail::new(FsCode::Disabled, "this device does not share its files with you"));
            }
            // The list of folders is not a folder of any share, so it is answered here.
            if parts(&path)?.is_empty() {
                let items: Vec<FsEntry> = policy
                    .shares
                    .iter()
                    .map(|s| FsEntry { name: s.name.clone(), dir: true, size: 0, modified_ms: 0, readonly: !(policy.write && s.write) })
                    .collect();
                return reply(send, &FsResponse::Entries { items, more: false }).await;
            }
            let resolved = resolve(policy, &path, Need::Read)?;
            let policy = policy.clone();
            let limit = limit.clamp(1, PAGE) as usize;
            let (items, more) = blocking(move || list(&policy, &resolved, offset as usize, limit)).await?;
            reply(send, &FsResponse::Entries { items, more }).await
        }
        FsRequest::Stat { path } => {
            if parts(&path)?.len() == 1 {
                // A share itself.
                let resolved = resolve(policy, &path, Need::Read)?;
                let meta = std::fs::metadata(&resolved.real).map_err(|e| FsFail::io(&e))?;
                let entry = entry_of(&resolved.share.name, &meta, resolved.share.write, policy);
                return reply(send, &FsResponse::Entry(entry)).await;
            }
            let resolved = resolve(policy, &path, Need::Read)?;
            let policy = policy.clone();
            let entry = blocking(move || {
                let meta = std::fs::metadata(&resolved.real).map_err(|e| FsFail::io(&e))?;
                let name = resolved.rest.last().cloned().unwrap_or_default();
                Ok(entry_of(&name, &meta, resolved.share.write, &policy))
            })
            .await?;
            reply(send, &FsResponse::Entry(entry)).await
        }
        FsRequest::Read { path, offset, len } => {
            let resolved = resolve(policy, &path, Need::Read)?;
            let real = resolved.real.clone();
            let (meta, file) = blocking(move || {
                let meta = std::fs::metadata(&real).map_err(|e| FsFail::io(&e))?;
                if meta.is_dir() {
                    return Err(FsFail::new(FsCode::IsDir, "that is a folder"));
                }
                let file = std::fs::File::open(&real).map_err(|e| FsFail::io(&e))?;
                Ok((meta, file))
            })
            .await?;
            let size = meta.len();
            let modified_ms = meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_millis() as u64).unwrap_or(0);
            let start = offset.min(size);
            let sent = len.min(size - start);
            reply(send, &FsResponse::Data { size, modified_ms, sent }).await?;
            let mut file = tokio::fs::File::from_std(file);
            file.seek(std::io::SeekFrom::Start(start)).await.map_err(|e| FsFail::io(&e).late())?;
            let mut left = sent;
            let mut buffer = vec![0u8; CHUNK];
            while left > 0 {
                let want = buffer.len().min(left as usize);
                let got = file.read(&mut buffer[..want]).await.map_err(|e| FsFail::io(&e).late())?;
                if got == 0 {
                    return Err(FsFail::new(FsCode::Io, "the file got shorter while it was being read").late());
                }
                send.write_all(&buffer[..got]).await.map_err(|e| FsFail::new(FsCode::Io, e.to_string()).late())?;
                left -= got as u64;
            }
            Ok(())
        }
        FsRequest::Write { path, size, overwrite } => {
            let resolved = resolve(policy, &path, Need::Change)?;
            if policy.max_upload > 0 && size > policy.max_upload {
                return Err(FsFail::new(FsCode::TooLarge, "this file is bigger than this device accepts"));
            }
            let target = resolved.real.clone();
            let (temp, exists) = {
                let target = target.clone();
                blocking(move || {
                    if resolved.rest.is_empty() {
                        return Err(FsFail::new(FsCode::IsDir, "that is a folder"));
                    }
                    let exists = match std::fs::symlink_metadata(&target) {
                        Ok(meta) if meta.is_dir() => return Err(FsFail::new(FsCode::IsDir, "that is a folder")),
                        Ok(_) => true,
                        Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
                        Err(e) => return Err(FsFail::io(&e)),
                    };
                    if exists && !overwrite {
                        return Err(FsFail::new(FsCode::Exists, "there is a file with that name already"));
                    }
                    let parent = target.parent().ok_or_else(|| FsFail::new(FsCode::Outside, "no folder to put it in"))?;
                    let name = target.file_name().and_then(|n| n.to_str()).unwrap_or("file");
                    let temp = parent.join(format!(".{name}.{:x}.tandem-part", rand_u64()));
                    Ok((temp, exists))
                })
                .await?
            };
            let _ = exists;
            let outcome = write_body(&temp, size, recv).await;
            match outcome {
                Ok(()) => {
                    let (from, to) = (temp.clone(), target.clone());
                    let moved = blocking(move || std::fs::rename(&from, &to).map_err(|e| FsFail::io(&e))).await;
                    if let Err(e) = moved {
                        let _ = std::fs::remove_file(&temp);
                        return Err(e);
                    }
                    reply(send, &FsResponse::Ok).await
                }
                Err(e) => {
                    let _ = std::fs::remove_file(&temp);
                    Err(e)
                }
            }
        }
        FsRequest::Mkdir { path } => {
            let resolved = resolve(policy, &path, Need::Change)?;
            blocking(move || {
                if resolved.rest.is_empty() {
                    return Err(FsFail::new(FsCode::Exists, "that is a shared folder already"));
                }
                std::fs::create_dir(&resolved.real).map_err(|e| FsFail::io(&e))
            })
            .await?;
            reply(send, &FsResponse::Ok).await
        }
        FsRequest::Remove { path, recursive } => {
            let resolved = resolve(policy, &path, Need::Remove)?;
            blocking(move || {
                if resolved.rest.is_empty() {
                    return Err(FsFail::new(FsCode::Denied, "a shared folder itself cannot be removed from here"));
                }
                let meta = std::fs::symlink_metadata(&resolved.real).map_err(|e| FsFail::io(&e))?;
                if meta.is_dir() {
                    if recursive {
                        std::fs::remove_dir_all(&resolved.real).map_err(|e| FsFail::io(&e))
                    } else {
                        std::fs::remove_dir(&resolved.real).map_err(|e| FsFail::io(&e))
                    }
                } else {
                    // A link is removed, not what it points to.
                    std::fs::remove_file(&resolved.real).map_err(|e| FsFail::io(&e))
                }
            })
            .await?;
            reply(send, &FsResponse::Ok).await
        }
        FsRequest::Rename { from, to, overwrite } => {
            let source = resolve(policy, &from, Need::Change)?;
            let target = resolve(policy, &to, Need::Change)?;
            blocking(move || {
                if source.rest.is_empty() || target.rest.is_empty() {
                    return Err(FsFail::new(FsCode::Denied, "a shared folder itself cannot be moved"));
                }
                if source.root != target.root {
                    return Err(FsFail::new(FsCode::Unsupported, "a file cannot be moved from one shared folder to another"));
                }
                if !overwrite && std::fs::symlink_metadata(&target.real).is_ok() {
                    return Err(FsFail::new(FsCode::Exists, "there is something with that name already"));
                }
                std::fs::rename(&source.real, &target.real).map_err(|e| FsFail::io(&e))
            })
            .await?;
            reply(send, &FsResponse::Ok).await
        }
    }
}

fn list(policy: &FilePolicy, resolved: &Resolved, offset: usize, limit: usize) -> std::result::Result<(Vec<FsEntry>, bool), FsFail> {
    let meta = std::fs::metadata(&resolved.real).map_err(|e| FsFail::io(&e))?;
    if !meta.is_dir() {
        return Err(FsFail::new(FsCode::NotDir, "that is a file, not a folder"));
    }
    let mut items = Vec::new();
    for entry in std::fs::read_dir(&resolved.real).map_err(|e| FsFail::io(&e))? {
        let Ok(entry) = entry else { continue };
        let name = entry.file_name().to_string_lossy().into_owned();
        if !policy.hidden && is_hidden_name(&name) {
            continue;
        }
        // What a link points to has to be in the share too, or the link is not shown.
        let path = entry.path();
        if std::fs::symlink_metadata(&path).map(|m| m.file_type().is_symlink()).unwrap_or(false) && inside(&resolved.root, &path).is_err() {
            continue;
        }
        let Ok(meta) = std::fs::metadata(&path) else { continue };
        if !policy.hidden && is_hidden_on_disk(&meta) {
            continue;
        }
        items.push(entry_of(&name, &meta, resolved.share.write, policy));
    }
    items.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()).then_with(|| a.name.cmp(&b.name)));
    let total = items.len();
    let items: Vec<FsEntry> = items.into_iter().skip(offset).take(limit).collect();
    Ok((items, offset + limit < total))
}

/// The bytes of a file that is being put here, into a temporary file next to where it will go.
async fn write_body(temp: &Path, size: u64, recv: &mut RecvStream) -> std::result::Result<(), FsFail> {
    let mut file = tokio::fs::File::create(temp).await.map_err(|e| FsFail::io(&e))?;
    let mut buffer = vec![0u8; CHUNK];
    let mut left = size;
    while left > 0 {
        let want = buffer.len().min(left as usize);
        let got = recv
            .read(&mut buffer[..want])
            .await
            .map_err(|e| FsFail::new(FsCode::Io, e.to_string()))?
            .ok_or_else(|| FsFail::new(FsCode::Io, "the file stopped coming before it was whole"))?;
        file.write_all(&buffer[..got]).await.map_err(|e| FsFail::io(&e))?;
        left -= got as u64;
    }
    file.flush().await.map_err(|e| FsFail::io(&e))?;
    file.sync_all().await.map_err(|e| FsFail::io(&e))?;
    Ok(())
}

// ---- The device that looks --------------------------------------------------------

/// Another device's files, as far as it lets this one see them.
pub struct FsClient {
    pub(crate) inner: Arc<Inner>,
    pub(crate) peer: DeviceId,
}

impl FsClient {
    /// Opens a stream, sends the request and reads the first answer. The rest of the answer, if there is one, is
    /// still on the stream that comes back with it.
    async fn ask(&self, request: &FsRequest) -> Result<(FsResponse, SendStream, RecvStream)> {
        let session = self.inner.session_of(&self.peer).ok_or(Error::NotConnected)?;
        let (mut send, mut recv) = session.conn.open_bi().await.map_err(Error::connection)?;
        send.write_all(&[STREAM_FS]).await.map_err(Error::connection)?;
        proto::send(&mut send, request).await?;
        // A write sends its bytes before the answer is read, so the answer is read by the caller then.
        if matches!(request, FsRequest::Write { .. }) {
            return Ok((FsResponse::Ok, send, recv));
        }
        let response: FsResponse = proto::recv(&mut recv, MAX_CONTROL_FRAME).await?;
        if let FsResponse::Err { code, message } = response {
            return Err(Error::Files { code, message });
        }
        Ok((response, send, recv))
    }

    fn unexpected() -> Error {
        Error::protocol("the other device answered something else than was asked")
    }

    /// The folders the other device offers.
    pub async fn roots(&self) -> Result<Vec<FsRoot>> {
        match self.ask(&FsRequest::Roots).await?.0 {
            FsResponse::Roots { roots } => Ok(roots),
            _ => Err(Self::unexpected()),
        }
    }

    /// What is in a folder, all of it.
    pub async fn list(&self, path: &str) -> Result<Vec<FsEntry>> {
        let mut all = Vec::new();
        loop {
            let request = FsRequest::List { path: path.to_string(), offset: all.len() as u64, limit: PAGE };
            match self.ask(&request).await?.0 {
                FsResponse::Entries { items, more } => {
                    let empty = items.is_empty();
                    all.extend(items);
                    if !more || empty {
                        return Ok(all);
                    }
                }
                _ => return Err(Self::unexpected()),
            }
        }
    }

    pub async fn stat(&self, path: &str) -> Result<FsEntry> {
        match self.ask(&FsRequest::Stat { path: path.to_string() }).await?.0 {
            FsResponse::Entry(entry) => Ok(entry),
            _ => Err(Self::unexpected()),
        }
    }

    /// Part of a file, into memory. For anything big, `download` is the way.
    pub async fn read(&self, path: &str, offset: u64, len: u64) -> Result<Vec<u8>> {
        if len > MAX_IN_MEMORY {
            return Err(Error::invalid("too much for memory, download it instead"));
        }
        let (response, _send, mut recv) = self.ask(&FsRequest::Read { path: path.to_string(), offset, len }).await?;
        let FsResponse::Data { sent, .. } = response else { return Err(Self::unexpected()) };
        let mut data = vec![0u8; sent as usize];
        recv.read_exact(&mut data).await.map_err(Error::connection)?;
        Ok(data)
    }

    /// A file to a place on this device. It goes to a temporary name first and takes its own when it is whole, and
    /// what an earlier try left behind is carried on from.
    pub async fn download(&self, path: &str, to: &Path, mut progress: impl FnMut(u64, u64) + Send) -> Result<()> {
        let part = {
            let mut name = to.file_name().map(|n| n.to_os_string()).unwrap_or_default();
            name.push(".tandem-part");
            to.with_file_name(name)
        };
        let have = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
        let (response, _send, mut recv) = self.ask(&FsRequest::Read { path: path.to_string(), offset: have, len: u64::MAX }).await?;
        let FsResponse::Data { size, sent, .. } = response else { return Err(Self::unexpected()) };
        let mut file = tokio::fs::OpenOptions::new().create(true).append(true).open(&part).await?;
        let mut buffer = vec![0u8; CHUNK];
        let mut done = have;
        let mut left = sent;
        progress(done, size);
        while left > 0 {
            let want = buffer.len().min(left as usize);
            let got = recv
                .read(&mut buffer[..want])
                .await
                .map_err(Error::connection)?
                .ok_or_else(|| Error::transfer("the file stopped coming before it was whole"))?;
            file.write_all(&buffer[..got]).await?;
            left -= got as u64;
            done += got as u64;
            progress(done, size);
        }
        file.flush().await?;
        drop(file);
        tokio::fs::rename(&part, to).await?;
        Ok(())
    }

    /// A file from this device to the other one. It lands there whole or not at all.
    pub async fn upload(&self, from: &Path, path: &str, overwrite: bool, mut progress: impl FnMut(u64, u64) + Send) -> Result<()> {
        let mut file = tokio::fs::File::open(from).await?;
        let size = file.metadata().await?.len();
        let (_, mut send, mut recv) = self.ask(&FsRequest::Write { path: path.to_string(), size, overwrite }).await?;
        let mut buffer = vec![0u8; CHUNK];
        let mut done = 0u64;
        progress(0, size);
        let mut write_failed = false;
        while done < size {
            let got = file.read(&mut buffer).await?;
            if got == 0 {
                return Err(Error::transfer("the file got shorter while it was being sent"));
            }
            if send.write_all(&buffer[..got]).await.is_err() {
                // The other device has said no and closed; its answer says why.
                write_failed = true;
                break;
            }
            done += got as u64;
            progress(done, size);
        }
        let _ = send.finish();
        let response: FsResponse = proto::recv(&mut recv, MAX_CONTROL_FRAME).await?;
        match response {
            FsResponse::Ok if !write_failed => Ok(()),
            FsResponse::Err { code, message } => Err(Error::Files { code, message }),
            _ => Err(Error::transfer("the other device did not take the file")),
        }
    }

    pub async fn mkdir(&self, path: &str) -> Result<()> {
        self.ask(&FsRequest::Mkdir { path: path.to_string() }).await.map(|_| ())
    }

    pub async fn remove(&self, path: &str, recursive: bool) -> Result<()> {
        self.ask(&FsRequest::Remove { path: path.to_string(), recursive }).await.map(|_| ())
    }

    pub async fn rename(&self, from: &str, to: &str, overwrite: bool) -> Result<()> {
        self.ask(&FsRequest::Rename { from: from.to_string(), to: to.to_string(), overwrite }).await.map(|_| ())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy_for(dir: &Path) -> FilePolicy {
        FilePolicy { shares: vec![Share { name: "Docs".into(), path: dir.to_string_lossy().into_owned(), write: true }], ..FilePolicy::default() }
    }

    #[test]
    fn paths_that_go_up_or_are_not_paths_are_refused() {
        for bad in ["Docs/a", "/Docs/../etc", "/Docs/./a", "/Docs/a\\b", "/Docs/a\0b"] {
            assert!(parts(bad).is_err(), "{bad} must be refused");
        }
        assert_eq!(parts("/Docs/a/b").unwrap(), vec!["Docs", "a", "b"]);
        assert_eq!(parts("/Docs//a/").unwrap(), vec!["Docs", "a"]);
        assert!(parts("/").unwrap().is_empty());
    }

    #[test]
    fn a_path_resolves_inside_its_share_only() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir(dir.path().join("sub")).unwrap();
        let policy = policy_for(dir.path());
        let resolved = resolve(&policy, "/Docs/sub/new.txt", Need::Read).unwrap();
        assert!(resolved.real.ends_with("sub/new.txt"));
        assert!(resolve(&policy, "/Other/x", Need::Read).is_err());
        assert!(resolve(&policy, "/", Need::Read).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn a_link_that_leaves_the_share_is_not_a_way_out() {
        let inside = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        std::fs::write(outside.path().join("secret.txt"), "no").unwrap();
        std::os::unix::fs::symlink(outside.path(), inside.path().join("door")).unwrap();
        std::os::unix::fs::symlink(outside.path().join("secret.txt"), inside.path().join("file-link")).unwrap();
        std::os::unix::fs::symlink(inside.path().join("nowhere"), inside.path().join("dangling")).unwrap();
        let policy = policy_for(inside.path());
        assert_eq!(resolve(&policy, "/Docs/door", Need::Read).err().map(|e| e.code), Some(FsCode::Outside));
        assert_eq!(resolve(&policy, "/Docs/door/secret.txt", Need::Read).err().map(|e| e.code), Some(FsCode::Outside));
        assert_eq!(resolve(&policy, "/Docs/file-link", Need::Read).err().map(|e| e.code), Some(FsCode::Outside));
        // A new name in a folder behind the link is no better.
        assert_eq!(resolve(&policy, "/Docs/door/new.txt", Need::Change).err().map(|e| e.code), Some(FsCode::Outside));
        // And a link to nowhere cannot be written through.
        assert_eq!(resolve(&policy, "/Docs/dangling", Need::Change).err().map(|e| e.code), Some(FsCode::Outside));
    }

    #[test]
    fn the_policy_decides_what_may_be_done() {
        let dir = tempfile::tempdir().unwrap();
        let mut policy = policy_for(dir.path());
        assert!(resolve(&policy, "/Docs/a", Need::Change).is_ok());
        policy.write = false;
        assert_eq!(resolve(&policy, "/Docs/a", Need::Change).err().map(|e| e.code), Some(FsCode::Denied));
        assert!(resolve(&policy, "/Docs/a", Need::Read).is_ok());
        policy.write = true;
        policy.delete = false;
        assert!(resolve(&policy, "/Docs/a", Need::Change).is_ok());
        assert_eq!(resolve(&policy, "/Docs/a", Need::Remove).err().map(|e| e.code), Some(FsCode::Denied));
        policy.shares[0].write = false;
        assert_eq!(resolve(&policy, "/Docs/a", Need::Change).err().map(|e| e.code), Some(FsCode::Denied));
        policy.enabled = false;
        assert_eq!(resolve(&policy, "/Docs/a", Need::Read).err().map(|e| e.code), Some(FsCode::Disabled));
    }

    #[test]
    fn hidden_things_are_out_of_reach_unless_allowed() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir(dir.path().join(".ssh")).unwrap();
        let mut policy = policy_for(dir.path());
        assert_eq!(resolve(&policy, "/Docs/.ssh/id", Need::Read).err().map(|e| e.code), Some(FsCode::NotFound));
        policy.hidden = true;
        assert!(resolve(&policy, "/Docs/.ssh/id", Need::Read).is_ok());
    }

    #[test]
    fn what_a_person_chose_is_kept_and_a_new_device_gets_the_default() {
        let dir = tempfile::tempdir().unwrap();
        let service = FileService::new(Store::new(dir.path()).unwrap());
        let stranger = DeviceId::from_bytes([7; 16]);
        assert!(service.policy(&stranger).enabled);
        let mut own = service.policy(&stranger);
        own.write = false;
        service.set_policy(&stranger, own).unwrap();
        assert!(!service.policy(&stranger).write);
        assert!(service.default_policy().write);

        let again = FileService::new(Store::new(dir.path()).unwrap());
        assert!(!again.policy(&stranger).write);
        again.clear_policy(&stranger).unwrap();
        assert!(again.policy(&stranger).write);
    }

    #[test]
    fn an_old_policy_without_the_newer_fields_still_reads() {
        // Only what an early version knew: the fields that were added later take their defaults.
        let mut bytes = Vec::new();
        ciborium::into_writer(&ciborium::value::Value::Map(vec![(ciborium::value::Value::Text("enabled".into()), ciborium::value::Value::Bool(false))]), &mut bytes).unwrap();
        let policy: FilePolicy = ciborium::from_reader(bytes.as_slice()).unwrap();
        assert!(!policy.enabled);
        assert!(policy.write && policy.delete && !policy.hidden);
        assert_eq!(policy.max_upload, 0);
    }
}
