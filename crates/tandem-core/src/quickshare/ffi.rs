//! Quick Share for the apps (Kotlin and Swift), through UniFFI. One object that is started when the person turns it on and
//! stopped when they turn it off.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use super::service::{Peer, QuickShare, Sink};
use super::transfer::{DeviceKind, Introduction, Outcome, Outgoing, Received, TextKind};
use crate::ffi::{OwnedRuntime, TandemError};

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemQsKind {
    Unknown,
    Phone,
    Tablet,
    Laptop,
}

impl From<TandemQsKind> for DeviceKind {
    fn from(kind: TandemQsKind) -> DeviceKind {
        match kind {
            TandemQsKind::Unknown => DeviceKind::Unknown,
            TandemQsKind::Phone => DeviceKind::Phone,
            TandemQsKind::Tablet => DeviceKind::Tablet,
            TandemQsKind::Laptop => DeviceKind::Laptop,
        }
    }
}

impl From<DeviceKind> for TandemQsKind {
    fn from(kind: DeviceKind) -> TandemQsKind {
        match kind {
            DeviceKind::Unknown => TandemQsKind::Unknown,
            DeviceKind::Phone => TandemQsKind::Phone,
            DeviceKind::Tablet => TandemQsKind::Tablet,
            DeviceKind::Laptop => TandemQsKind::Laptop,
        }
    }
}

/// A device that can be sent to.
#[derive(Clone, Debug, uniffi::Record)]
pub struct TandemQsPeer {
    pub id: String,
    pub name: String,
    pub kind: TandemQsKind,
}

impl From<&Peer> for TandemQsPeer {
    fn from(peer: &Peer) -> TandemQsPeer {
        TandemQsPeer { id: peer.id.clone(), name: peer.name.clone(), kind: peer.kind.into() }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemQsTextKind {
    Text,
    Url,
    Address,
    Phone,
}

impl From<TextKind> for TandemQsTextKind {
    fn from(kind: TextKind) -> TandemQsTextKind {
        match kind {
            TextKind::Text => TandemQsTextKind::Text,
            TextKind::Url => TandemQsTextKind::Url,
            TextKind::Address => TandemQsTextKind::Address,
            TextKind::Phone => TandemQsTextKind::Phone,
        }
    }
}

/// Text that is announced: what it is and the start of it. The whole text comes once the transfer is accepted.
#[derive(Clone, Debug, uniffi::Record)]
pub struct TandemQsTextInfo {
    pub kind: TandemQsTextKind,
    pub title: String,
}

/// Text that came in: a link, a note.
#[derive(Clone, Debug, uniffi::Record)]
pub struct TandemQsText {
    pub kind: TandemQsTextKind,
    pub text: String,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct TandemQsFile {
    pub name: String,
    pub mime: String,
    pub size: u64,
}

/// What the app hears. Every call comes from a thread of the service and must not wait.
#[uniffi::export(with_foreign)]
pub trait TandemQuickShareSink: Send + Sync {
    fn peer_found(&self, peer: TandemQsPeer);
    fn peer_lost(&self, id: String);
    /// Somebody wants to send these files. Answer with `respond` and this id; no answer in two minutes is a no.
    fn incoming(&self, id: u64, sender: String, pin: String, files: Vec<TandemQsFile>, texts: Vec<TandemQsTextInfo>);
    fn progress(&self, id: u64, done: u64, total: u64);
    /// An incoming transfer is complete; these are the saved files and the texts that came with them.
    fn received(&self, id: u64, paths: Vec<String>, texts: Vec<TandemQsText>);
    /// The digits to compare with the screen of the other device, for a transfer this device started.
    fn pin(&self, id: u64, pin: String);
    /// An outgoing transfer is over. `refused` when the other side said no.
    fn sent(&self, id: u64, refused: bool);
    fn failed(&self, id: u64, reason: String);
}

struct Adapter(Arc<dyn TandemQuickShareSink>);

impl Sink for Adapter {
    fn peer_found(&self, peer: Peer) {
        self.0.peer_found((&peer).into());
    }
    fn peer_lost(&self, id: String) {
        self.0.peer_lost(id);
    }
    fn incoming(&self, id: u64, introduction: Introduction) {
        let files = introduction.files.iter().map(|f| TandemQsFile { name: f.name.clone(), mime: f.mime.clone(), size: f.size }).collect();
        let texts = introduction.texts.iter().map(|t| TandemQsTextInfo { kind: t.kind.into(), title: t.title.clone() }).collect();
        self.0.incoming(id, introduction.sender, introduction.pin, files, texts);
    }
    fn progress(&self, id: u64, done: u64, total: u64) {
        self.0.progress(id, done, total);
    }
    fn received(&self, id: u64, received: Received) {
        let paths = received.files.iter().map(|p| p.to_string_lossy().into_owned()).collect();
        let texts = received.texts.into_iter().map(|t| TandemQsText { kind: t.kind.into(), text: t.text }).collect();
        self.0.received(id, paths, texts);
    }
    fn pin(&self, id: u64, pin: String) {
        self.0.pin(id, pin);
    }
    fn sent(&self, id: u64, outcome: Outcome) {
        self.0.sent(id, outcome == Outcome::Refused);
    }
    fn failed(&self, id: u64, reason: String) {
        self.0.failed(id, reason);
    }
}

#[derive(uniffi::Object)]
pub struct TandemQuickShare {
    runtime: OwnedRuntime,
    service: Mutex<Option<QuickShare>>,
}

#[uniffi::export]
impl TandemQuickShare {
    /// Starts listening and being found under `device_name`.
    #[uniffi::constructor]
    pub fn start(device_name: String, kind: TandemQsKind, sink: Arc<dyn TandemQuickShareSink>) -> Result<Arc<TandemQuickShare>, TandemError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("tandem-quickshare")
            .enable_all()
            .build()
            .map_err(|e| TandemError::Failed { reason: e.to_string() })?;
        let service = runtime
            .block_on(QuickShare::start(&device_name, kind.into(), Arc::new(Adapter(sink))))
            .map_err(TandemError::from)?;
        Ok(Arc::new(TandemQuickShare { runtime: OwnedRuntime(Some(runtime)), service: Mutex::new(Some(service)) }))
    }

    /// The answer to an incoming transfer: a folder takes the files in, nothing turns it down.
    pub fn respond(&self, id: u64, folder: Option<String>) {
        if let Some(service) = self.service.lock().unwrap().as_ref() {
            service.respond(id, folder.map(PathBuf::from));
        }
    }

    pub fn peers(&self) -> Vec<TandemQsPeer> {
        self.service.lock().unwrap().as_ref().map(|s| s.peers().iter().map(TandemQsPeer::from).collect()).unwrap_or_default()
    }

    /// Sends files to a device that was found. Gives the id the sink will use for it.
    pub fn send(&self, peer_id: String, own_name: String, own_kind: TandemQsKind, paths: Vec<String>) -> Result<u64, TandemError> {
        let files: Vec<Outgoing> = paths
            .into_iter()
            .map(|p| Outgoing::from_path(PathBuf::from(p)))
            .collect::<std::io::Result<_>>()
            .map_err(|e| TandemError::Failed { reason: e.to_string() })?;
        let guard = self.service.lock().unwrap();
        let service = guard.as_ref().ok_or(TandemError::NotConnected)?;
        let _enter = self.runtime.enter();
        service.send(&peer_id, &own_name, own_kind.into(), files).map_err(TandemError::from)
    }

    /// Sends a link or a note to a device that was found.
    pub fn send_text(&self, peer_id: String, own_name: String, own_kind: TandemQsKind, text: String) -> Result<u64, TandemError> {
        let guard = self.service.lock().unwrap();
        let service = guard.as_ref().ok_or(TandemError::NotConnected)?;
        let _enter = self.runtime.enter();
        service.send_text(&peer_id, &own_name, own_kind.into(), text).map_err(TandemError::from)
    }

    /// Stops listening and being found.
    pub fn stop(&self) {
        if let Some(mut service) = self.service.lock().unwrap().take() {
            service.stop();
        }
    }
}
