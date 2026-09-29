//! Moving files between devices.
//!
//! The receiver pulls. It opens one stream per file and says where to start, so it
//! decides how many files run at once and can pick up a half-finished file after
//! the connection dropped. The sender only serves.
//!
//! Integrity is one BLAKE3 hash over the whole file, compared at the end. On a
//! resume both sides re-read the part they already have locally to keep the hash
//! honest, which costs a disk read and no network.

use std::collections::HashSet;
use std::io::SeekFrom;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use quinn::{RecvStream, SendStream};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::sync::Semaphore;
use tracing::{debug, info};

use crate::engine::{Inner, rand_u64};
use crate::error::{Error, Result};
use crate::events::{Event, TransferDone, TransferProgress};
use crate::ids::DeviceId;
use crate::proto::{
    self, FileAck, FileRequest, FileResponse, FileTrailer, MAX_SMALL_FRAME, Msg, STREAM_FILE, ShareItem,
    ShareOffer, ShareOrigin, ShareReply,
};
use crate::session::Session;

const CHUNK: usize = 256 * 1024;
const PROGRESS_EVERY: Duration = Duration::from_millis(100);
const CONCURRENT_FILES: usize = 3;
const MAX_ATTEMPTS: u32 = 8;

#[derive(Clone, Debug)]
pub struct OutgoingFile {
    /// What the platform can open: a path on desktop, a content URI on Android.
    pub source: String,
    pub name: String,
    /// Zero means "look it up".
    pub size: u64,
    pub mime: String,
}

#[derive(Clone, Debug)]
pub struct SendReport {
    pub offer: u64,
    pub sent_to: Vec<DeviceId>,
    pub offline: Vec<DeviceId>,
}

pub(crate) struct OutOffer {
    pub targets: HashSet<DeviceId>,
    pub items: Vec<OutgoingFile>,
    pub created: Instant,
}

pub(crate) struct InOffer {
    pub offer: ShareOffer,
    pub started: std::sync::atomic::AtomicBool,
}

enum PullError {
    /// Worth trying again, probably from where it stopped.
    Retry(String),
    Fatal(String),
}

impl Inner {
    pub(crate) async fn send_files(
        &self,
        targets: &[DeviceId],
        mut files: Vec<OutgoingFile>,
        origin: ShareOrigin,
    ) -> Result<SendReport> {
        if files.is_empty() {
            return Err(Error::invalid("nothing to send"));
        }
        for file in &mut files {
            if file.size == 0 {
                if let Ok(opened) = self.files.open_read(&file.source) {
                    file.size = opened.metadata().map(|m| m.len()).unwrap_or(0);
                }
            }
        }

        let (online, offline): (Vec<DeviceId>, Vec<DeviceId>) =
            targets.iter().copied().partition(|id| self.session_of(id).is_some());
        let offer_id = rand_u64();
        let items: Vec<ShareItem> = files
            .iter()
            .map(|f| ShareItem { name: f.name.clone(), size: f.size, mime: f.mime.clone(), modified: None })
            .collect();

        self.out_offers.lock().unwrap().insert(
            offer_id,
            OutOffer { targets: online.iter().copied().collect(), items: files, created: Instant::now() },
        );

        let offer = ShareOffer { id: offer_id, origin, items };
        let mut sent_to = Vec::new();
        for id in online {
            if self.send_to(&id, Msg::ShareOffer(offer.clone())).await.is_ok() {
                sent_to.push(id);
            }
        }
        Ok(SendReport { offer: offer_id, sent_to, offline })
    }

    pub(crate) fn on_share_offer(self: Arc<Self>, from: DeviceId, offer: ShareOffer) {
        self.emit(Event::ShareOffered { from, offer: offer.clone() });
        let auto = self.settings.read().unwrap().for_device(&from).auto_accept;
        let entry = Arc::new(InOffer { offer, started: std::sync::atomic::AtomicBool::new(false) });
        self.in_offers.lock().unwrap().insert((from, entry.offer.id), entry.clone());
        if auto {
            self.begin_receive(from, entry);
        }
    }

    /// Starts receiving an offer that was waiting for a yes.
    pub(crate) fn accept_offer(self: &Arc<Self>, from: DeviceId, offer_id: u64) -> Result<()> {
        let entry = self
            .in_offers
            .lock()
            .unwrap()
            .get(&(from, offer_id))
            .cloned()
            .ok_or_else(|| Error::invalid("that offer is gone"))?;
        self.begin_receive(from, entry);
        Ok(())
    }

    pub(crate) async fn decline_offer(&self, from: DeviceId, offer_id: u64) {
        self.in_offers.lock().unwrap().remove(&(from, offer_id));
        let reply = ShareReply { id: offer_id, accepted: false, reason: None };
        let _ = self.send_to(&from, Msg::ShareReply(reply)).await;
    }

    fn begin_receive(self: &Arc<Self>, from: DeviceId, entry: Arc<InOffer>) {
        if entry.started.swap(true, std::sync::atomic::Ordering::SeqCst) {
            return;
        }
        let this = self.clone();
        tokio::spawn(async move { this.receive_all(from, entry).await });
    }

    async fn receive_all(self: Arc<Self>, peer: DeviceId, entry: Arc<InOffer>) {
        let slots = Arc::new(Semaphore::new(CONCURRENT_FILES));
        let mut tasks = tokio::task::JoinSet::new();
        for (index, item) in entry.offer.items.iter().enumerate() {
            let Ok(permit) = slots.clone().acquire_owned().await else { break };
            let this = self.clone();
            let item = item.clone();
            let offer_id = entry.offer.id;
            tasks.spawn(async move {
                let _permit = permit;
                this.receive_item(peer, offer_id, index as u32, item).await;
            });
        }
        while tasks.join_next().await.is_some() {}
        self.in_offers.lock().unwrap().remove(&(peer, entry.offer.id));
    }

    async fn receive_item(self: Arc<Self>, peer: DeviceId, offer: u64, index: u32, item: ShareItem) {
        let temp = self.partial_path(peer, offer, index);
        let mut attempts = 0u32;
        let outcome: std::result::Result<(), String> = loop {
            let Some(session) = self.wait_for_session(peer, Duration::from_secs(600)).await else {
                break Err("the other device did not come back".to_string());
            };
            match self.pull_item(&session, offer, index, &item, &temp).await {
                Ok(()) => break Ok(()),
                Err(PullError::Fatal(reason)) => break Err(reason),
                Err(PullError::Retry(reason)) => {
                    attempts += 1;
                    debug!(%peer, offer, index, attempts, "transfer interrupted: {reason}");
                    if attempts >= MAX_ATTEMPTS {
                        break Err(reason);
                    }
                    tokio::time::sleep(Duration::from_millis(400 * attempts as u64)).await;
                }
            }
        };

        let done = match outcome {
            Ok(()) => {
                let files = self.files.clone();
                let name = item.name.clone();
                let mime = item.mime.clone();
                let path = temp.clone();
                let stored = tokio::task::spawn_blocking(move || files.store_download(&path, &name, &mime)).await;
                match stored {
                    Ok(Ok(location)) => TransferDone {
                        offer,
                        index,
                        peer,
                        incoming: true,
                        name: item.name.clone(),
                        size: item.size,
                        location: Some(location),
                        error: None,
                    },
                    Ok(Err(e)) => failed(peer, offer, index, &item, format!("could not save the file: {e}")),
                    Err(e) => failed(peer, offer, index, &item, e.to_string()),
                }
            }
            Err(reason) => {
                let _ = std::fs::remove_file(&temp);
                failed(peer, offer, index, &item, reason)
            }
        };
        info!(%peer, offer, name = %done.name, error = ?done.error, "incoming file finished");
        self.emit(Event::Finished(done));
    }

    async fn wait_for_session(&self, peer: DeviceId, timeout: Duration) -> Option<Arc<Session>> {
        let deadline = Instant::now() + timeout;
        loop {
            let notified = self.sessions_changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if let Some(session) = self.session_of(&peer) {
                if session.conn.close_reason().is_none() {
                    return Some(session);
                }
            }
            if self.circle.read().unwrap().member(&peer).is_none() {
                return None;
            }
            let remaining = deadline.checked_duration_since(Instant::now())?;
            tokio::select! {
                _ = notified => {}
                _ = tokio::time::sleep(remaining.min(Duration::from_secs(5))) => {}
                _ = self.cancel.cancelled() => return None,
            }
        }
    }

    fn partial_path(&self, peer: DeviceId, offer: u64, index: u32) -> PathBuf {
        let dir = self.store.dir().join("incoming");
        let _ = std::fs::create_dir_all(&dir);
        dir.join(format!("{peer}-{offer:016x}-{index}.part"))
    }

    async fn pull_item(
        &self,
        session: &Session,
        offer: u64,
        index: u32,
        item: &ShareItem,
        temp: &Path,
    ) -> std::result::Result<(), PullError> {
        let retry = |e: &dyn std::fmt::Display| PullError::Retry(e.to_string());

        let (mut send, mut recv) = session.conn.open_bi().await.map_err(|e| retry(&e))?;
        send.write_all(&[STREAM_FILE]).await.map_err(|e| retry(&e))?;

        let existing = std::fs::metadata(temp).map(|m| m.len()).unwrap_or(0);
        let mut offset = if item.size > 0 && existing > item.size { 0 } else { existing };
        proto::send(&mut send, &FileRequest { offer, index, offset }).await.map_err(|e| retry(&e))?;

        let response: FileResponse =
            proto::recv(&mut recv, MAX_SMALL_FRAME).await.map_err(|e| retry(&e))?;
        if !response.ok {
            return Err(PullError::Fatal(response.error.unwrap_or_else(|| "the other device refused".into())));
        }
        let size = response.size;
        if offset > size {
            offset = 0;
        }

        let mut file = tokio::fs::OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .open(temp)
            .await
            .map_err(|e| PullError::Fatal(format!("could not create the file: {e}")))?;
        if offset == 0 {
            file.set_len(0).await.map_err(|e| PullError::Fatal(e.to_string()))?;
        }

        let mut hasher = blake3::Hasher::new();
        let mut buf = vec![0u8; CHUNK];
        if offset > 0 {
            file.seek(SeekFrom::Start(0)).await.map_err(|e| PullError::Fatal(e.to_string()))?;
            let mut remaining = offset;
            while remaining > 0 {
                let want = remaining.min(buf.len() as u64) as usize;
                let n = file.read(&mut buf[..want]).await.map_err(|e| PullError::Fatal(e.to_string()))?;
                if n == 0 {
                    return Err(PullError::Fatal("the partial file is shorter than expected".into()));
                }
                hasher.update(&buf[..n]);
                remaining -= n as u64;
            }
        }
        file.seek(SeekFrom::Start(offset)).await.map_err(|e| PullError::Fatal(e.to_string()))?;

        let mut done = offset;
        let mut last_emit = Instant::now();
        self.progress(session.peer, offer, index, true, &item.name, done, size);
        while done < size {
            let want = (size - done).min(buf.len() as u64) as usize;
            let n = match recv.read(&mut buf[..want]).await.map_err(|e| retry(&e))? {
                Some(n) => n,
                None => return Err(PullError::Retry("the stream ended early".into())),
            };
            file.write_all(&buf[..n]).await.map_err(|e| PullError::Fatal(format!("could not write: {e}")))?;
            hasher.update(&buf[..n]);
            done += n as u64;
            if last_emit.elapsed() >= PROGRESS_EVERY {
                last_emit = Instant::now();
                self.progress(session.peer, offer, index, true, &item.name, done, size);
            }
        }

        let trailer: FileTrailer = proto::recv(&mut recv, MAX_SMALL_FRAME).await.map_err(|e| retry(&e))?;
        file.flush().await.map_err(|e| PullError::Fatal(e.to_string()))?;
        file.sync_all().await.map_err(|e| PullError::Fatal(e.to_string()))?;
        drop(file);

        let matches = *hasher.finalize().as_bytes() == trailer.blake3;
        let _ = proto::send(&mut send, &FileAck { ok: matches }).await;
        let _ = send.finish();
        if !matches {
            // Start over from nothing: a partial that fails its hash cannot be trusted.
            let _ = std::fs::remove_file(temp);
            return Err(PullError::Retry("checksum did not match".into()));
        }
        self.progress(session.peer, offer, index, true, &item.name, size, size);
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    fn progress(&self, peer: DeviceId, offer: u64, index: u32, incoming: bool, name: &str, done: u64, total: u64) {
        self.emit(Event::Progress(TransferProgress {
            offer,
            index,
            peer,
            incoming,
            name: name.to_string(),
            done,
            total,
        }));
    }

    pub(crate) async fn serve_file(
        self: Arc<Self>,
        peer: DeviceId,
        mut send: SendStream,
        mut recv: RecvStream,
    ) -> Result<()> {
        let request: FileRequest = proto::recv(&mut recv, MAX_SMALL_FRAME).await?;

        let item = {
            let offers = self.out_offers.lock().unwrap();
            offers
                .get(&request.offer)
                .filter(|o| o.targets.contains(&peer))
                .and_then(|o| o.items.get(request.index as usize).cloned())
        };
        let Some(item) = item else {
            let reply = FileResponse { ok: false, error: Some("that offer is no longer available".into()), size: 0 };
            proto::send(&mut send, &reply).await?;
            let _ = send.finish();
            return Ok(());
        };

        let opened = match self.files.open_read(&item.source) {
            Ok(file) => file,
            Err(e) => {
                let reply = FileResponse { ok: false, error: Some(format!("could not open the file: {e}")), size: 0 };
                proto::send(&mut send, &reply).await?;
                let _ = send.finish();
                return Ok(());
            }
        };
        let size = opened.metadata()?.len();
        if request.offset > size {
            let reply = FileResponse { ok: false, error: Some("resume point is past the end".into()), size };
            proto::send(&mut send, &reply).await?;
            let _ = send.finish();
            return Ok(());
        }
        proto::send(&mut send, &FileResponse { ok: true, error: None, size }).await?;

        let mut file = tokio::fs::File::from_std(opened);
        let mut hasher = blake3::Hasher::new();
        let mut buf = vec![0u8; CHUNK];

        // Read the part the receiver already has, without sending it, so the final
        // hash still covers the whole file.
        let mut skipped = 0u64;
        while skipped < request.offset {
            let want = (request.offset - skipped).min(buf.len() as u64) as usize;
            let n = file.read(&mut buf[..want]).await?;
            if n == 0 {
                return Err(Error::transfer("file is shorter than the resume point"));
            }
            hasher.update(&buf[..n]);
            skipped += n as u64;
        }

        let mut done = request.offset;
        let mut last_emit = Instant::now();
        self.progress(peer, request.offer, request.index, false, &item.name, done, size);
        while done < size {
            let want = (size - done).min(buf.len() as u64) as usize;
            let n = file.read(&mut buf[..want]).await?;
            if n == 0 {
                return Err(Error::transfer("file changed while sending"));
            }
            hasher.update(&buf[..n]);
            send.write_all(&buf[..n]).await.map_err(Error::transfer)?;
            done += n as u64;
            if last_emit.elapsed() >= PROGRESS_EVERY {
                last_emit = Instant::now();
                self.progress(peer, request.offer, request.index, false, &item.name, done, size);
            }
        }

        proto::send(&mut send, &FileTrailer { blake3: *hasher.finalize().as_bytes() }).await?;
        let ack: FileAck = proto::recv(&mut recv, MAX_SMALL_FRAME).await?;
        let _ = send.finish();
        let error = (!ack.ok).then(|| "the other device rejected the checksum".to_string());
        if ack.ok {
            self.progress(peer, request.offer, request.index, false, &item.name, size, size);
        }
        self.emit(Event::Finished(TransferDone {
            offer: request.offer,
            index: request.index,
            peer,
            incoming: false,
            name: item.name,
            size,
            location: None,
            error,
        }));
        Ok(())
    }

    /// Partial downloads nobody came back for are just clutter.
    pub(crate) fn clean_partials(&self) {
        let dir = self.store.dir().join("incoming");
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        let cutoff = std::time::SystemTime::now() - Duration::from_secs(3 * 24 * 3600);
        for entry in entries.flatten() {
            if let Ok(modified) = entry.metadata().and_then(|m| m.modified()) {
                if modified < cutoff {
                    let _ = std::fs::remove_file(entry.path());
                }
            }
        }
        let outgoing = self.store.dir().join("outgoing");
        if let Ok(entries) = std::fs::read_dir(outgoing) {
            let old = std::time::SystemTime::now() - Duration::from_secs(24 * 3600);
            for entry in entries.flatten() {
                if let Ok(modified) = entry.metadata().and_then(|m| m.modified()) {
                    if modified < old {
                        let _ = std::fs::remove_file(entry.path());
                    }
                }
            }
        }
    }
}

fn failed(peer: DeviceId, offer: u64, index: u32, item: &ShareItem, reason: String) -> TransferDone {
    TransferDone {
        offer,
        index,
        peer,
        incoming: true,
        name: item.name.clone(),
        size: item.size,
        location: None,
        error: Some(reason),
    }
}
