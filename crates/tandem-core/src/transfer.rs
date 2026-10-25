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
use tokio_util::sync::CancellationToken;
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
    /// Set when the person on either side stops the transfer: what is being served stops at the next piece.
    pub cancel: CancellationToken,
}

pub(crate) struct InOffer {
    pub offer: ShareOffer,
    pub started: std::sync::atomic::AtomicBool,
    /// Set when the person on either side stops the transfer: every file of it stops and its partial files go.
    pub cancel: CancellationToken,
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
            OutOffer { targets: online.iter().copied().collect(), items: files, created: Instant::now(), cancel: CancellationToken::new() },
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
        let auto = self.settings.read().unwrap().for_device(&from).auto_accept;
        let event = Event::ShareOffered { from, offer: offer.clone() };
        let entry = Arc::new(InOffer { offer, started: std::sync::atomic::AtomicBool::new(false), cancel: CancellationToken::new() });
        self.in_offers.lock().unwrap().insert((from, entry.offer.id), entry.clone());
        // After the insert: an app that accepts straight from the event must find the offer.
        self.emit(event);
        if auto {
            self.begin_receive(from, entry);
        }
    }

    /// Stops a transfer, from either side, and tells the other device so it stops too. The files already done stay.
    pub(crate) async fn cancel_transfer(&self, peer: DeviceId, offer: u64, incoming: bool) {
        if incoming {
            if let Some(entry) = self.in_offers.lock().unwrap().get(&(peer, offer)) {
                entry.cancel.cancel();
            }
        } else if let Some(out) = self.out_offers.lock().unwrap().get_mut(&offer) {
            out.targets.remove(&peer);
            out.cancel.cancel();
        }
        let _ = self.send_to(&peer, Msg::ShareCancel { id: offer }).await;
    }

    /// The other device stopped a transfer.
    pub(crate) fn on_share_cancel(&self, peer: DeviceId, offer: u64) {
        if let Some(entry) = self.in_offers.lock().unwrap().get(&(peer, offer)) {
            entry.cancel.cancel();
        }
        if let Some(out) = self.out_offers.lock().unwrap().get_mut(&offer) {
            if out.targets.remove(&peer) && out.targets.is_empty() {
                out.cancel.cancel();
            }
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
            let cancel = entry.cancel.clone();
            let clipboard_on = self.settings.read().unwrap().for_device(&peer).clipboard;
            let for_clipboard = kept_for_clipboard(&entry.offer, clipboard_on);
            tasks.spawn(async move {
                let _permit = permit;
                this.receive_item(peer, offer_id, index as u32, item, for_clipboard, cancel).await;
            });
        }
        while tasks.join_next().await.is_some() {}
        self.in_offers.lock().unwrap().remove(&(peer, entry.offer.id));
    }

    async fn receive_item(
        self: Arc<Self>,
        peer: DeviceId,
        offer: u64,
        index: u32,
        item: ShareItem,
        for_clipboard: bool,
        cancel: CancellationToken,
    ) {
        let temp = self.partial_path(peer, offer, index);
        let attempt = async {
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
        outcome
        };
        // Stopped by the person: the file in the making goes with it.
        let outcome = tokio::select! {
            outcome = attempt => outcome,
            _ = cancel.cancelled() => Err("cancelled".to_string()),
        };

        let done = match outcome {
            Ok(()) => {
                let files = self.files.clone();
                let name = item.name.clone();
                let mime = item.mime.clone();
                let path = temp.clone();
                let clipboard_dir = self.clipboard_dir(peer, offer);
                let stored = tokio::task::spawn_blocking(move || {
                    if for_clipboard { keep_for_clipboard(&path, &clipboard_dir, &name) } else { files.store_download(&path, &name, &mime) }
                })
                .await;
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

    /// Where a picture that came for the clipboard waits until the app has put it there. Not Downloads: it is a copy, not a file that
    /// came in, and a folder of its own per offer keeps the name it had without two of them meeting.
    fn clipboard_dir(&self, peer: DeviceId, offer: u64) -> PathBuf {
        self.store.dir().join(CLIPBOARD_DIR).join(format!("{peer}-{offer:016x}"))
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

        let (item, cancel) = {
            let offers = self.out_offers.lock().unwrap();
            match offers.get(&request.offer).filter(|o| o.targets.contains(&peer)) {
                Some(o) => (o.items.get(request.index as usize).cloned(), Some(o.cancel.clone())),
                None => (None, None),
            }
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
            if cancel.as_ref().is_some_and(|c| c.is_cancelled()) {
                self.emit(Event::Finished(TransferDone {
                    offer: request.offer,
                    index: request.index,
                    peer,
                    incoming: false,
                    name: item.name.clone(),
                    size,
                    location: None,
                    error: Some("cancelled".into()),
                }));
                return Err(Error::transfer("cancelled"));
            }
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
        // Pictures for the clipboard are removed by the app once they are on it; this catches what a crash or a quit left behind.
        if let Ok(entries) = std::fs::read_dir(self.store.dir().join(CLIPBOARD_DIR)) {
            let old = std::time::SystemTime::now() - CLIPBOARD_KEPT;
            for entry in entries.flatten() {
                if entry.metadata().and_then(|m| m.modified()).is_ok_and(|modified| modified < old) {
                    let _ = std::fs::remove_dir_all(entry.path()).or_else(|_| std::fs::remove_file(entry.path()));
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

/// The folder in the data folder for pictures on their way to the clipboard.
pub const CLIPBOARD_DIR: &str = "clipboard";
/// As large as a clipboard offer that the apps take without asking.
pub const CLIPBOARD_LIMIT: u64 = 50 * 1024 * 1024;
/// How long such a picture may stay at most. Android needs the file for as long as its clipboard points at it, and clears its
/// clipboard after an hour.
const CLIPBOARD_KEPT: Duration = Duration::from_secs(3600);

/// A picture that was copied (or a screenshot) on a device that shares the clipboard with this one. It goes to the clipboard here
/// and nowhere else, so it is not stored with the downloads, where it would pile up, and where the screenshot watcher of a phone
/// would take a screenshot that came in for a new one and send it back. Files that were copied do go to the downloads: pasted,
/// they have to be somewhere.
pub(crate) fn kept_for_clipboard(offer: &ShareOffer, clipboard_on: bool) -> bool {
    clipboard_on
        && offer.origin == ShareOrigin::Clipboard
        && offer.items.len() == 1
        && offer.items[0].size <= CLIPBOARD_LIMIT
        && is_picture(&offer.items[0])
}

fn is_picture(item: &ShareItem) -> bool {
    if item.mime.starts_with("image/") {
        return true;
    }
    let ext = Path::new(&item.name).extension().map(|e| e.to_string_lossy().to_ascii_lowercase()).unwrap_or_default();
    matches!(ext.as_str(), "png" | "jpg" | "jpeg" | "gif" | "webp" | "heic" | "heif" | "tif" | "tiff" | "bmp")
}

/// Moves the finished picture into its own folder under the clipboard folder, under the name it came with.
fn keep_for_clipboard(temp: &Path, dir: &Path, name: &str) -> std::io::Result<String> {
    std::fs::create_dir_all(dir)?;
    let target = dir.join(safe_name(name));
    std::fs::rename(temp, &target)?;
    Ok(target.to_string_lossy().into_owned())
}

/// A name that stays inside its folder.
fn safe_name(name: &str) -> String {
    let cleaned: String =
        name.chars().map(|c| if matches!(c, '/' | '\\' | ':') || c.is_control() { '_' } else { c }).collect::<String>();
    let cleaned = cleaned.trim().trim_start_matches('.');
    if cleaned.is_empty() { "picture.png".to_string() } else { cleaned.chars().take(160).collect() }
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

#[cfg(test)]
mod clipboard_tests {
    use super::*;

    fn offer(origin: ShareOrigin, items: &[(&str, &str, u64)]) -> ShareOffer {
        ShareOffer {
            id: 1,
            origin,
            items: items
                .iter()
                .map(|(name, mime, size)| ShareItem { name: name.to_string(), size: *size, mime: mime.to_string(), modified: None })
                .collect(),
        }
    }

    #[test]
    fn only_one_copied_picture_skips_the_downloads() {
        assert!(kept_for_clipboard(&offer(ShareOrigin::Clipboard, &[("Screenshot.png", "image/png", 10)]), true));
        assert!(kept_for_clipboard(&offer(ShareOrigin::Clipboard, &[("Shot.JPG", "", 10)]), true));
        // The clipboard is off for that device: the app asks about it like other files, so it has to be where files go.
        assert!(!kept_for_clipboard(&offer(ShareOrigin::Clipboard, &[("Screenshot.png", "image/png", 10)]), false));
        assert!(!kept_for_clipboard(&offer(ShareOrigin::Screenshot, &[("Screenshot.png", "image/png", 10)]), true));
        assert!(!kept_for_clipboard(&offer(ShareOrigin::Clipboard, &[("notes.txt", "text/plain", 10)]), true));
        assert!(!kept_for_clipboard(&offer(ShareOrigin::Clipboard, &[("a.png", "image/png", 10), ("b.png", "image/png", 10)]), true));
        assert!(!kept_for_clipboard(&offer(ShareOrigin::Clipboard, &[("huge.png", "image/png", CLIPBOARD_LIMIT + 1)]), true));
    }

    #[test]
    fn a_name_cannot_leave_its_folder() {
        assert_eq!(safe_name("../../etc/passwd"), "_.._etc_passwd");
        assert_eq!(safe_name(".hidden.png"), "hidden.png");
        assert_eq!(safe_name("  "), "picture.png");
        assert_eq!(safe_name("Screenshot 10.00.png"), "Screenshot 10.00.png");
    }
}
