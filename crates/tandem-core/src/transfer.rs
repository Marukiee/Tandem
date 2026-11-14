//! Moving files between devices.
//!
//! The receiver pulls. It opens one stream per file and says where to start, so it
//! decides how many files run at once and can pick up a half-finished file after
//! the connection dropped. The sender only serves.
//!
//! Integrity is one BLAKE3 hash over the whole file, compared at the end. On a
//! resume both sides re-read the part they already have locally to keep the hash
//! honest, which costs a disk read and no network.

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
use crate::ids::now_ms;
use crate::journal::{ItemState, Key, PARTIAL_DIR, RecordItem, Run, Stage, TransferRecord, partial_name};
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

/// On mobile data, files above this are not taken without asking, also from a device that may send without asking (a large file from a
/// computer would otherwise cost the data of the phone without anyone seeing it). The same as the most a copy may be: what is copied
/// still comes by itself, anything larger is asked about.
pub const METERED_ASK_BYTES: u64 = 50 * 1024 * 1024;

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
        let mut changed = vec![None; files.len()];
        for (file, changed) in files.iter_mut().zip(changed.iter_mut()) {
            if file.size == 0
                && let Ok(opened) = self.files.open_read(&file.source) {
                    let meta = opened.metadata().ok();
                    file.size = meta.as_ref().map(|m| m.len()).unwrap_or(0);
                    *changed = meta.and_then(|m| m.modified().ok()).and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok());
                }
        }

        let (online, offline): (Vec<DeviceId>, Vec<DeviceId>) =
            targets.iter().copied().partition(|id| self.session_of(id).is_some());
        let offer_id = rand_u64();
        let items: Vec<RecordItem> = files
            .into_iter()
            .zip(changed)
            .map(|(f, changed)| RecordItem {
                name: f.name,
                size: f.size,
                mime: f.mime,
                modified: None,
                source: f.source,
                source_modified_ms: changed.map(|d| d.as_millis() as u64),
                path: String::new(),
            })
            .collect();
        let record = TransferRecord::outgoing(offer_id, origin, items, &online);
        let offer = record.share_offer();
        let key = Key::Out(offer_id);
        // In the journal before it goes out: a receiver that takes it without asking pulls at once.
        if !online.is_empty() {
            self.journal.insert(record);
        }

        let mut sent_to = Vec::new();
        for id in online {
            if self.send_to(&id, Msg::ShareOffer(offer.clone())).await.is_ok() {
                sent_to.push(id);
            } else {
                // It never got there, so nothing waits for it.
                self.journal.change(key, |record| record.peers.retain(|p| p.device != id));
            }
        }
        if sent_to.is_empty() {
            self.journal.remove(key);
        }
        Ok(SendReport { offer: offer_id, sent_to, offline })
    }

    pub(crate) fn on_share_offer(self: Arc<Self>, from: DeviceId, offer: ShareOffer) {
        let key = Key::In(from, offer.id);
        // Numbers are random, so this is the same offer said twice; the one that is here already counts.
        if self.journal.read(key, |_| ()).is_some() {
            debug!(%from, offer = offer.id, "an offer that is here already, left out");
            return;
        }
        let size: u64 = offer.items.iter().map(|item| item.size).sum();
        let held = self.metered.load(std::sync::atomic::Ordering::Relaxed) && size > METERED_ASK_BYTES;
        let auto = self.settings.read().unwrap().for_device(&from).auto_accept && !held;
        let run = self.journal.insert(TransferRecord::incoming(from, &offer, held));
        // After the insert: an app that accepts straight from the event must find the offer.
        self.emit(Event::ShareOffered { from, offer });
        if auto {
            self.begin_receive(from, key, run);
        }
    }

    /// Whether the offer `offer` of `from` waits for the person because this device is on mobile data (see `TransferRecord::held`).
    pub(crate) fn offer_held(&self, from: DeviceId, offer: u64) -> bool {
        self.journal.read(Key::In(from, offer), |record| record.held).unwrap_or(false)
    }

    /// Stops a transfer, from either side, and tells the other device so it stops too. The files already done stay.
    pub(crate) async fn cancel_transfer(&self, peer: DeviceId, offer: u64, incoming: bool) {
        if incoming {
            self.stop_incoming(peer, offer);
        } else {
            self.stop_outgoing(peer, offer, ItemState::Cancelled, None);
        }
        let _ = self.send_to(&peer, Msg::ShareCancel { id: offer }).await;
    }

    /// The other device stopped a transfer.
    pub(crate) fn on_share_cancel(&self, peer: DeviceId, offer: u64) {
        self.stop_incoming(peer, offer);
        self.stop_outgoing(peer, offer, ItemState::Cancelled, None);
    }

    /// Stops an incoming offer. Files being pulled stop and take their partial file with them. An offer that is not being pulled
    /// (it waited for a yes, or stopped halfway before a restart) has nothing that would end it, so it ends here.
    fn stop_incoming(&self, peer: DeviceId, offer: u64) {
        let key = Key::In(peer, offer);
        let Some(run) = self.journal.run(key) else { return };
        run.cancel.cancel();
        let idle = !run.started.load(std::sync::atomic::Ordering::SeqCst);
        let count = self
            .journal
            .change(key, |record| {
                if let Some(sender) = record.peer_mut(&peer) {
                    if idle {
                        sender.close(ItemState::Cancelled, None);
                    } else {
                        sender.stage = Stage::Closed;
                    }
                }
                record.items.len()
            })
            .unwrap_or(0);
        if idle {
            for index in 0..count {
                let _ = std::fs::remove_file(self.partial_path(peer, offer, index as u32));
            }
        }
    }

    /// Stops serving an outgoing offer to one receiver, and leaves the others running (R-B8). Returns the files that ended now.
    fn stop_outgoing(&self, peer: DeviceId, offer: u64, state: ItemState, error: Option<String>) -> Vec<(u32, RecordItem)> {
        let key = Key::Out(offer);
        let Some(run) = self.journal.run(key) else { return Vec::new() };
        run.stop_peer(&peer);
        self.journal
            .change(key, |record| {
                let ended = record.peer_mut(&peer).filter(|p| p.open()).map(|p| p.close(state, error)).unwrap_or_default();
                if record.peers.iter().all(|p| !p.open()) {
                    run.cancel.cancel();
                }
                ended.into_iter().map(|index| (index, record.items[index as usize].clone())).collect()
            })
            .unwrap_or_default()
    }

    /// The answer of a receiver to an offer. Only a no is sent: it was declined, or it expired there before anyone said yes. The
    /// sender hears it as one finished file each, so nothing waits there for an answer that came (R-B14).
    pub(crate) fn on_share_reply(&self, peer: DeviceId, reply: ShareReply) {
        debug!(%peer, offer = reply.id, accepted = reply.accepted, "share reply");
        if reply.accepted {
            return;
        }
        let reason = reply.reason.filter(|r| !r.trim().is_empty()).unwrap_or_else(|| "declined".to_string());
        let state = if reason == "expired" { ItemState::Expired } else { ItemState::Declined };
        for (index, item) in self.stop_outgoing(peer, reply.id, state, Some(reason.clone())) {
            self.emit(Event::Finished(TransferDone {
                offer: reply.id,
                index,
                peer,
                incoming: false,
                name: item.name,
                size: item.size,
                location: None,
                error: Some(reason.clone()),
            }));
        }
    }

    /// Starts receiving an offer that was waiting for a yes.
    pub(crate) fn accept_offer(self: &Arc<Self>, from: DeviceId, offer_id: u64) -> Result<()> {
        let key = Key::In(from, offer_id);
        let gone = || Error::invalid("that offer is gone");
        let run = self.journal.run(key).ok_or_else(gone)?;
        let stage = self.journal.read(key, |record| record.peer(&from).map(|p| p.stage)).flatten().ok_or_else(gone)?;
        // Stopped halfway before a restart: that is picked up on purpose (P2), never by a yes that was meant for the offer.
        if stage == Stage::Accepted && !run.started.load(std::sync::atomic::Ordering::SeqCst) {
            return Err(Error::invalid("that transfer stopped halfway"));
        }
        self.begin_receive(from, key, run);
        Ok(())
    }

    pub(crate) async fn decline_offer(&self, from: DeviceId, offer_id: u64) {
        let key = Key::In(from, offer_id);
        if let Some(run) = self.journal.run(key) {
            run.cancel.cancel();
        }
        // The person said no here, so there is nothing of it to show later either.
        self.journal.remove(key);
        let reply = ShareReply { id: offer_id, accepted: false, reason: None };
        let _ = self.send_to(&from, Msg::ShareReply(reply)).await;
    }

    fn begin_receive(self: &Arc<Self>, from: DeviceId, key: Key, run: Arc<Run>) {
        if run.started.swap(true, std::sync::atomic::Ordering::SeqCst) {
            return;
        }
        self.journal.change(key, |record| {
            if let Some(sender) = record.peer_mut(&from)
                && sender.stage == Stage::Offered {
                    sender.stage = Stage::Accepted;
                }
        });
        let this = self.clone();
        tokio::spawn(async move { this.receive_all(from, key, run).await });
    }

    async fn receive_all(self: Arc<Self>, peer: DeviceId, key: Key, run: Arc<Run>) {
        let Some((offer, done)) = self.journal.read(key, |record| {
            let done: Vec<bool> = record.peers[0].items.iter().map(|item| item.state == ItemState::Done).collect();
            (record.share_offer(), done)
        }) else {
            return;
        };
        let clipboard_on = self.settings.read().unwrap().for_device(&peer).clipboard;
        let for_clipboard = kept_for_clipboard(&offer, clipboard_on);
        let slots = Arc::new(Semaphore::new(CONCURRENT_FILES));
        let mut tasks = tokio::task::JoinSet::new();
        for (index, item) in offer.items.iter().enumerate() {
            // Already here (before a restart, when the rest is picked up later): not again.
            if done.get(index).copied().unwrap_or(false) {
                continue;
            }
            let Ok(permit) = slots.clone().acquire_owned().await else { break };
            let this = self.clone();
            let item = item.clone();
            let offer_id = offer.id;
            let cancel = run.cancel.clone();
            tasks.spawn(async move {
                let _permit = permit;
                this.receive_item(peer, offer_id, index as u32, item, for_clipboard, cancel).await;
            });
        }
        while tasks.join_next().await.is_some() {}
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
        let key = Key::In(peer, offer);
        let temp = self.partial_path(peer, offer, index);
        self.journal.touch(key, |record| {
            if let Some(progress) = record.item_mut(&peer, index)
                && !progress.state.is_final() {
                    progress.state = ItemState::Running;
                }
        });
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
        // Stopped by the person: the file in the making goes with it. The engine stops: it stays, for a resume after the start (P2).
        let outcome = tokio::select! {
            biased;
            _ = self.cancel.cancelled() => None,
            _ = cancel.cancelled() => Some(Err("cancelled".to_string())),
            outcome = attempt => Some(outcome),
        };
        let Some(outcome) = outcome.filter(|outcome| outcome.is_ok() || !self.cancel.is_cancelled()) else {
            let done = std::fs::metadata(&temp).map(|m| m.len()).unwrap_or(0);
            self.journal.change(key, |record| {
                if let Some(progress) = record.item_mut(&peer, index)
                    && !progress.state.is_final() {
                        progress.state = ItemState::Interrupted;
                        progress.done = done;
                    }
            });
            info!(%peer, offer, name = %item.name, done, "incoming file stopped halfway: the engine stops");
            return;
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
        self.journal.change(key, |record| {
            if let Some(progress) = record.item_mut(&peer, index) {
                progress.state = match done.error.as_deref() {
                    None => ItemState::Done,
                    Some("cancelled") => ItemState::Cancelled,
                    Some(_) => ItemState::Failed,
                };
                if progress.state == ItemState::Done {
                    progress.done = done.size;
                }
                progress.error = done.error.clone();
                progress.location = done.location.clone();
            }
        });
        info!(%peer, offer, name = %done.name, error = ?done.error, "incoming file finished");
        self.emit(Event::Finished(done));
    }

    /// Lets offers go that waited, stopped halfway or ended long enough ago (see `journal`), and says so for each file that ended
    /// now. A receiver that lets an offer go that waited for a yes tells the sender, so it ends there too (R-B14, R-B27).
    pub(crate) fn expire_transfers(self: &Arc<Self>) {
        for lapse in self.journal.expire(now_ms()) {
            let (peer, offer) = match lapse.key {
                Key::In(sender, offer) => (sender, offer),
                Key::Out(offer) => (lapse.peer, offer),
            };
            for (index, item) in lapse.items {
                if lapse.incoming {
                    let _ = std::fs::remove_file(self.partial_path(peer, offer, index));
                }
                self.emit(Event::Finished(TransferDone {
                    offer,
                    index,
                    peer,
                    incoming: lapse.incoming,
                    name: item.name,
                    size: item.size,
                    location: None,
                    error: ItemState::Expired.reason().map(str::to_string),
                }));
            }
            if lapse.tell_sender {
                let this = self.clone();
                tokio::spawn(async move {
                    let reply = ShareReply { id: offer, accepted: false, reason: ItemState::Expired.reason().map(str::to_string) };
                    let _ = this.send_to(&peer, Msg::ShareReply(reply)).await;
                });
            }
        }
    }

    async fn wait_for_session(&self, peer: DeviceId, timeout: Duration) -> Option<Arc<Session>> {
        let deadline = Instant::now() + timeout;
        loop {
            let notified = self.sessions_changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if let Some(session) = self.session_of(&peer)
                && session.conn.close_reason().is_none() {
                    return Some(session);
                }
            self.circle.read().unwrap().member(&peer)?;
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
        let dir = self.store.dir().join(PARTIAL_DIR);
        let _ = std::fs::create_dir_all(&dir);
        dir.join(partial_name(&peer, offer, index))
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
        // In memory only, for whoever asks how far it is; a restart takes the length of the partial file instead.
        let key = if incoming { Key::In(peer, offer) } else { Key::Out(offer) };
        self.journal.touch(key, |record| {
            if let Some(progress) = record.item_mut(&peer, index) {
                progress.done = done;
            }
        });
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
        let key = Key::Out(request.offer);
        let found = self
            .journal
            .read(key, |record| {
                record.peer(&peer).filter(|p| p.open())?;
                record.items.get(request.index as usize).cloned()
            })
            .flatten();
        let Some(item) = found else {
            let reply = FileResponse { ok: false, error: Some("that offer is no longer available".into()), size: 0 };
            proto::send(&mut send, &reply).await?;
            let _ = send.finish();
            return Ok(());
        };
        let cancel = self.journal.run(key).map(|run| run.peer_token(&peer));

        let opened = match self.files.open_read(&item.source) {
            Ok(file) => file,
            Err(e) => {
                let error = format!("could not open the file: {e}");
                self.served(key, peer, request.index, ItemState::Failed, Some(error.clone()));
                let reply = FileResponse { ok: false, error: Some(error), size: 0 };
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
        // The first pull of a receiver is its yes; that is a change worth writing. Serving itself is only in memory: a restart makes
        // it `Interrupted` anyway.
        let first = self
            .journal
            .touch(key, |record| {
                // Stopped since it was looked up: stays stopped, and the token below ends the serving at once.
                let receiver = record.peer_mut(&peer).filter(|p| p.open())?;
                let first = receiver.stage == Stage::Offered;
                receiver.stage = Stage::Accepted;
                let progress = receiver.items.get_mut(request.index as usize)?;
                progress.state = ItemState::Running;
                progress.error = None;
                Some(first)
            })
            .flatten();
        if first == Some(true) {
            self.journal.change(key, |_| ());
        }

        let outcome = self.stream_file(peer, &request, &item.name, opened, size, cancel.clone(), &mut send, &mut recv).await;
        let (state, error) = match &outcome {
            Ok(None) => (ItemState::Done, None),
            Ok(Some(error)) => (ItemState::Failed, Some(error.clone())),
            Err(_) if cancel.as_ref().is_some_and(|c| c.is_cancelled()) => (ItemState::Cancelled, None),
            // The connection broke or the file changed: the receiver tries again, from where it got.
            Err(_) => (ItemState::Interrupted, None),
        };
        self.served(key, peer, request.index, state, error);
        outcome.map(|_| ())
    }

    /// Writes how serving one file to one receiver ended, unless that receiver was closed meanwhile (stopped, declined), which says
    /// more than how the last piece went.
    fn served(&self, key: Key, peer: DeviceId, index: u32, state: ItemState, error: Option<String>) {
        self.journal.change(key, |record| {
            if let Some(progress) = record.item_mut(&peer, index)
                && !progress.state.is_final() {
                    progress.state = state;
                    progress.error = error.or_else(|| state.reason().map(str::to_string));
                }
        });
    }

    /// Sends one file, from where the receiver asked. Ok with the error when the receiver rejected the checksum.
    #[allow(clippy::too_many_arguments)]
    async fn stream_file(
        &self,
        peer: DeviceId,
        request: &FileRequest,
        name: &str,
        opened: std::fs::File,
        size: u64,
        cancel: Option<CancellationToken>,
        send: &mut SendStream,
        recv: &mut RecvStream,
    ) -> Result<Option<String>> {
        proto::send(send, &FileResponse { ok: true, error: None, size }).await?;

        // Read by position, never through the offset of the descriptor: Android hands out duplicates of one descriptor, which share
        // that offset, so two receivers of one file would move each other's reads (R-B6).
        let file = Arc::new(opened);
        let mut hasher = blake3::Hasher::new();
        let mut buf = vec![0u8; CHUNK];

        // Read the part the receiver already has, without sending it, so the final
        // hash still covers the whole file.
        let mut skipped = 0u64;
        while skipped < request.offset {
            let want = (request.offset - skipped).min(buf.len() as u64) as usize;
            let n;
            (buf, n) = read_chunk(&file, buf, skipped, want).await?;
            if n == 0 {
                return Err(Error::transfer("file is shorter than the resume point"));
            }
            hasher.update(&buf[..n]);
            skipped += n as u64;
        }

        let mut done = request.offset;
        let mut last_emit = Instant::now();
        self.progress(peer, request.offer, request.index, false, name, done, size);
        while done < size {
            let want = (size - done).min(buf.len() as u64) as usize;
            if cancel.as_ref().is_some_and(|c| c.is_cancelled()) {
                self.emit(Event::Finished(TransferDone {
                    offer: request.offer,
                    index: request.index,
                    peer,
                    incoming: false,
                    name: name.to_string(),
                    size,
                    location: None,
                    error: Some("cancelled".into()),
                }));
                return Err(Error::transfer("cancelled"));
            }
            let n;
            (buf, n) = read_chunk(&file, buf, done, want).await?;
            if n == 0 {
                return Err(Error::transfer("file changed while sending"));
            }
            hasher.update(&buf[..n]);
            send.write_all(&buf[..n]).await.map_err(Error::transfer)?;
            done += n as u64;
            if last_emit.elapsed() >= PROGRESS_EVERY {
                last_emit = Instant::now();
                self.progress(peer, request.offer, request.index, false, name, done, size);
            }
        }

        proto::send(&mut *send, &FileTrailer { blake3: *hasher.finalize().as_bytes() }).await?;
        // Every byte and the trailer are out. A receiver that closes the connection right after its last file can take the
        // acknowledgement down with it: that is not a failed send, and saying nothing left the file at 100% for good. A receiver that
        // really failed pulls again, and says so then.
        let ack: Option<FileAck> = proto::recv(&mut *recv, MAX_SMALL_FRAME).await.ok();
        let _ = send.finish();
        let rejected = ack.is_some_and(|a| !a.ok);
        let error = rejected.then(|| "the other device rejected the checksum".to_string());
        if !rejected {
            self.progress(peer, request.offer, request.index, false, name, size, size);
        }
        self.emit(Event::Finished(TransferDone {
            offer: request.offer,
            index: request.index,
            peer,
            incoming: false,
            name: name.to_string(),
            size,
            location: None,
            error: error.clone(),
        }));
        Ok(error)
    }

    /// Clears what nothing will come back for. Run at the start, before anything is received.
    pub(crate) fn clean_partials(&self) {
        // A partial file that no transfer in the journal points at can never be finished (it is from before the journal, or from a
        // crash between the two): it goes now instead of after days. The others go when their transfer does (R-B27).
        let in_use = self.journal.partials_in_use();
        if let Ok(entries) = std::fs::read_dir(self.store.dir().join(PARTIAL_DIR)) {
            for entry in entries.flatten() {
                if !in_use.contains(entry.file_name().to_string_lossy().as_ref()) {
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
                if let Ok(modified) = entry.metadata().and_then(|m| m.modified())
                    && modified < old {
                        let _ = std::fs::remove_file(entry.path());
                    }
            }
        }
    }
}

/// Reads up to `want` bytes at `offset` on a blocking thread, handing the buffer back with the count. Zero means the end.
async fn read_chunk(file: &Arc<std::fs::File>, mut buf: Vec<u8>, offset: u64, want: usize) -> Result<(Vec<u8>, usize)> {
    let file = file.clone();
    tokio::task::spawn_blocking(move || loop {
        match read_at(&file, &mut buf[..want], offset) {
            Ok(n) => return Ok((buf, n)),
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e.into()),
        }
    })
    .await
    .map_err(|e| Error::transfer(e.to_string()))?
}

#[cfg(unix)]
fn read_at(file: &std::fs::File, buf: &mut [u8], offset: u64) -> std::io::Result<usize> {
    std::os::unix::fs::FileExt::read_at(file, buf, offset)
}

/// `seek_read` moves the offset of the handle as well, which is harmless here: every read says where it starts.
#[cfg(windows)]
fn read_at(file: &std::fs::File, buf: &mut [u8], offset: u64) -> std::io::Result<usize> {
    std::os::windows::fs::FileExt::seek_read(file, buf, offset)
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
