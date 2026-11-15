//! Sending between two other devices (P9). From A you tell B: send these files of yours to C. B checks that A may read them (its
//! `FilePolicy` for A, as in Files) and that C is in the circle, and sends them itself as an ordinary offer, with `requested_by: A`
//! in its journal and in the offer, so C can say on whose request it came. C decides as always for B. B tells A how far each file
//! is and how it ended (`ShareRelayUpdate`); A shows that as its own outgoing transfer to C. Stop on A goes through B.
//!
//! Both sides are off until an app turns them on: B answers only when its app lists `RELAY_CAP` in its caps, and A asks only a B
//! that says it. A B that says nothing within `ANSWER_WITHIN` (an older one that skips the message) is reported as `no answer`.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tokio::sync::oneshot;
use tracing::info;

use crate::engine::{Inner, rand_u64};
use crate::events::{Event, TransferDone, TransferProgress};
use crate::files::FsCode;
use crate::ids::DeviceId;
use crate::journal::Key;
use crate::proto::{Msg, ShareItem, ShareOrigin, ShareRelay, ShareRelayReply, ShareRelayUpdate};
use crate::transfer::OutgoingFile;

/// A device that sends files to another one when a third one asks. An app lists it in its caps (`EngineConfig::caps`) when it is
/// fine with that and shows such offers (its panel lists them with `requested_by`).
pub const RELAY_CAP: &str = "share.relay";

/// Why B did not send, or A could not ask. Fixed codes that every app translates.
pub const REASON_NOT_ALLOWED: &str = "not allowed";
pub const REASON_GONE: &str = crate::transfer::REASON_GONE;
pub const REASON_NOT_A_FILE: &str = "not a file";
pub const REASON_NOT_IN_CIRCLE: &str = "not in circle";
pub const REASON_NOT_CONNECTED: &str = "not connected";
pub const REASON_NO_ANSWER: &str = "no answer";
pub const REASON_UNSUPPORTED: &str = "unsupported";
pub const REASON_FAILED: &str = crate::transfer::REASON_FAILED;
pub const REASONS: &[&str] = &[
    REASON_NOT_ALLOWED,
    REASON_GONE,
    REASON_NOT_A_FILE,
    REASON_NOT_IN_CIRCLE,
    REASON_NOT_CONNECTED,
    REASON_NO_ANSWER,
    REASON_UNSUPPORTED,
    REASON_FAILED,
];

/// How long A waits for B to say yes or no.
const ANSWER_WITHIN: Duration = Duration::from_secs(15);
/// How long A keeps listening to a relay of which not every file ended (B may have lost A, and A never hears the end).
const KEPT: Duration = Duration::from_secs(24 * 3600);
/// More paths than this in one request are refused.
const MAX_PATHS: usize = 2_000;

/// What A asked and still listens to.
struct Asked {
    via: DeviceId,
    to: DeviceId,
    count: usize,
    ended: HashSet<u32>,
    since: Instant,
}

#[derive(Default)]
pub(crate) struct RelayState {
    /// A: by the number of B's offer.
    asked: Mutex<HashMap<u64, Asked>>,
    /// A: answers that are awaited, by the same number.
    answers: Mutex<HashMap<u64, oneshot::Sender<ShareRelayReply>>>,
}

/// What A hears back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayAnswer {
    pub ok: bool,
    /// The number of the offer at B, which is also the `offer` of the progress A shows (with C as the peer).
    pub offer: u64,
    pub queued: bool,
    pub reason: Option<String>,
    pub items: Vec<ShareItem>,
}

impl RelayAnswer {
    fn no(offer: u64, reason: &str) -> RelayAnswer {
        RelayAnswer { ok: false, offer, queued: false, reason: Some(reason.to_string()), items: Vec::new() }
    }
}

fn reply_no(id: u64, reason: &str) -> ShareRelayReply {
    ShareRelayReply { id, ok: false, reason: Some(reason.to_string()), queued: false, items: Vec::new() }
}

impl Inner {
    pub(crate) fn relay_on(&self) -> bool {
        self.cfg.caps.iter().any(|cap| cap == RELAY_CAP)
    }

    // ---- A: the device that asks ------------------------------------------------------------------------------------------------

    /// Asks `via` to send `paths` of its files to `to`, and waits for its answer.
    pub(crate) async fn send_between(&self, via: DeviceId, to: DeviceId, paths: Vec<String>) -> RelayAnswer {
        let id = rand_u64();
        if self.session_of(&via).is_none() {
            return RelayAnswer::no(id, REASON_NOT_CONNECTED);
        }
        if !self.peer_has_cap(&via, RELAY_CAP) {
            return RelayAnswer::no(id, REASON_UNSUPPORTED);
        }
        let (tell, told) = oneshot::channel();
        self.relay.answers.lock().unwrap().insert(id, tell);
        {
            let mut asked = self.relay.asked.lock().unwrap();
            asked.retain(|_, a| a.since.elapsed() < KEPT);
            asked.insert(id, Asked { via, to, count: usize::MAX, ended: HashSet::new(), since: Instant::now() });
        }
        let sent = self.send_to(&via, Msg::ShareRelay(ShareRelay { id, to, paths })).await;
        let reply = match sent {
            Ok(()) => tokio::time::timeout(ANSWER_WITHIN, told).await.ok().and_then(Result::ok),
            Err(_) => None,
        };
        self.relay.answers.lock().unwrap().remove(&id);
        let answer = match reply {
            Some(reply) if reply.ok => RelayAnswer { ok: true, offer: id, queued: reply.queued, reason: None, items: reply.items },
            Some(reply) => RelayAnswer::no(id, reason_code(reply.reason.as_deref())),
            None if sent.is_err() => RelayAnswer::no(id, REASON_NOT_CONNECTED),
            None => RelayAnswer::no(id, REASON_NO_ANSWER),
        };
        let mut asked = self.relay.asked.lock().unwrap();
        if answer.ok {
            if let Some(entry) = asked.get_mut(&id) {
                entry.count = answer.items.len();
                if entry.ended.len() >= entry.count {
                    asked.remove(&id);
                }
            }
        } else {
            asked.remove(&id);
        }
        info!(%via, %to, offer = id, ok = answer.ok, reason = ?answer.reason, "asked another device to send for this one");
        answer
    }

    pub(crate) fn on_share_relay_reply(&self, from: DeviceId, reply: ShareRelayReply) {
        let asked_that = self.relay.asked.lock().unwrap().get(&reply.id).is_some_and(|a| a.via == from);
        if !asked_that {
            return;
        }
        if let Some(tell) = self.relay.answers.lock().unwrap().remove(&reply.id) {
            let _ = tell.send(reply);
        }
    }

    /// B says how far a file is: A shows it as its own outgoing file to C. Only from the B that A asked.
    pub(crate) fn on_share_relay_update(&self, from: DeviceId, update: ShareRelayUpdate) {
        let to = {
            let mut asked = self.relay.asked.lock().unwrap();
            let Some(entry) = asked.get_mut(&update.id).filter(|a| a.via == from) else { return };
            // A late progress of a file that ended would make it look as if it went on.
            if entry.ended.contains(&update.index) {
                return;
            }
            let to = entry.to;
            if update.ended {
                entry.ended.insert(update.index);
                if entry.ended.len() >= entry.count {
                    asked.remove(&update.id);
                }
            }
            to
        };
        if update.ended {
            self.emit(Event::Finished(TransferDone {
                offer: update.id,
                index: update.index,
                peer: to,
                incoming: false,
                name: update.name,
                size: update.size,
                location: None,
                error: update.error.map(|code| crate::transfer::REASONS.iter().find(|known| **known == code).copied().unwrap_or(crate::transfer::REASON_FAILED).to_string()),
            }));
        } else {
            self.emit(Event::Progress(TransferProgress {
                offer: update.id,
                index: update.index,
                peer: to,
                incoming: false,
                name: update.name,
                done: update.done,
                total: update.size,
            }));
        }
    }

    /// Stop on A for a transfer that B sends for it: B is asked to stop it. Whether `offer` to `to` was one.
    pub(crate) async fn cancel_relay(&self, to: DeviceId, offer: u64) -> bool {
        let via = self.relay.asked.lock().unwrap().get(&offer).filter(|a| a.to == to).map(|a| a.via);
        let Some(via) = via else { return false };
        let _ = self.send_to(&via, Msg::ShareRelayCancel { id: offer }).await;
        true
    }

    // ---- B: the device that sends -----------------------------------------------------------------------------------------------

    pub(crate) fn on_share_relay(self: Arc<Self>, from: DeviceId, ask: ShareRelay) {
        tokio::spawn(async move {
            let reply = self.relay_for(from, &ask).await;
            info!(%from, to = %ask.to, offer = ask.id, ok = reply.ok, reason = ?reply.reason, "a request to send for another device");
            let _ = self.send_to(&from, Msg::ShareRelayReply(reply)).await;
        });
    }

    async fn relay_for(self: &Arc<Self>, from: DeviceId, ask: &ShareRelay) -> ShareRelayReply {
        if !self.relay_on() {
            return reply_no(ask.id, REASON_UNSUPPORTED);
        }
        if ask.paths.is_empty() || ask.paths.len() > MAX_PATHS || ask.to == self.my_id || self.journal.read(Key::Out(ask.id), |_| ()).is_some() {
            return reply_no(ask.id, REASON_FAILED);
        }
        let in_circle = self.circle.read().unwrap().members().iter().any(|member| member.id == ask.to);
        if !in_circle {
            return reply_no(ask.id, REASON_NOT_IN_CIRCLE);
        }
        let mut files = Vec::new();
        for path in &ask.paths {
            let real = match self.file_service.readable_file(&from, path) {
                Ok(real) => real,
                Err(FsCode::NotFound) => return reply_no(ask.id, REASON_GONE),
                Err(FsCode::IsDir | FsCode::NotDir) => return reply_no(ask.id, REASON_NOT_A_FILE),
                Err(_) => return reply_no(ask.id, REASON_NOT_ALLOWED),
            };
            let name = real.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
            let mime = crate::quickshare::service::mime_of(&name).to_string();
            files.push(OutgoingFile { source: real.to_string_lossy().into_owned(), name, size: 0, mime });
        }
        let report = match self.send_files_as(&[ask.to], files, ShareOrigin::Files, ask.id, Some(from)).await {
            Ok(report) => report,
            Err(e) => {
                info!(%from, offer = ask.id, "could not send for another device: {e}");
                return reply_no(ask.id, REASON_FAILED);
            }
        };
        let sent = report.sent_to.contains(&ask.to);
        let queued = report.queued.contains(&ask.to);
        if !sent && !queued {
            return reply_no(ask.id, REASON_NOT_CONNECTED);
        }
        let items = self.journal.read(Key::Out(ask.id), |record| record.share_offer().items).unwrap_or_default();
        ShareRelayReply { id: ask.id, ok: true, reason: None, queued: !sent, items }
    }

    pub(crate) async fn on_share_relay_cancel(&self, from: DeviceId, offer: u64) {
        let peers = self.journal.read(Key::Out(offer), |record| {
            (record.requested_by == Some(from)).then(|| record.peers.iter().map(|p| p.device).collect::<Vec<_>>())
        });
        for peer in peers.flatten().unwrap_or_default() {
            self.cancel_transfer(peer, offer, false).await;
            // A file that was never pulled ends without an event here, so A hears it now; A skips what it heard already.
            let cancelled: Vec<ShareRelayUpdate> = self
                .journal
                .read(Key::Out(offer), |record| {
                    let Some(progress) = record.peer(&peer) else { return Vec::new() };
                    progress
                        .items
                        .iter()
                        .zip(&record.items)
                        .enumerate()
                        .filter(|(_, (p, _))| p.state == crate::journal::ItemState::Cancelled)
                        .map(|(index, (p, item))| ShareRelayUpdate {
                            id: offer,
                            index: index as u32,
                            name: item.name.clone(),
                            done: p.done,
                            size: item.size,
                            ended: true,
                            error: Some(crate::transfer::REASON_CANCELLED.to_string()),
                        })
                        .collect()
                })
                .unwrap_or_default();
            for update in cancelled {
                let _ = self.send_to(&from, Msg::ShareRelayUpdate(update)).await;
            }
        }
    }

    /// B tells the A that asked for an offer how far a file of it is, and how it ended. Called with every event; a progress that does
    /// not fit in the channel is left out (the next one comes soon), an end always goes.
    pub(crate) fn tell_requester(&self, event: &Event) {
        let (offer, update) = match event {
            Event::Progress(p) if !p.incoming => (
                p.offer,
                ShareRelayUpdate { id: p.offer, index: p.index, name: p.name.clone(), done: p.done, size: p.total, ended: false, error: None },
            ),
            Event::Finished(d) if !d.incoming => (
                d.offer,
                ShareRelayUpdate { id: d.offer, index: d.index, name: d.name.clone(), done: d.size, size: d.size, ended: true, error: d.error.clone() },
            ),
            _ => return,
        };
        let Some(Some(asker)) = self.journal.read(Key::Out(offer), |record| record.requested_by) else { return };
        let Some(session) = self.session_of(&asker) else { return };
        let ended = update.ended;
        if let Err(tokio::sync::mpsc::error::TrySendError::Full(msg)) = session.tx.try_send(Msg::ShareRelayUpdate(update))
            && ended
        {
            tokio::spawn(async move {
                let _ = session.tx.send(msg).await;
            });
        }
    }
}

fn reason_code(reason: Option<&str>) -> &'static str {
    reason.and_then(|r| REASONS.iter().find(|code| **code == r).copied()).unwrap_or(REASON_FAILED)
}
