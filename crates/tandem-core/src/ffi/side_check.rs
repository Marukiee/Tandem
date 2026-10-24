//! Finding the side by sound for the apps (see `crate::side`): asking a device to listen, waiting for what it heard, and the quiet check
//! that a computer runs by itself, with its schedule (`crate::side_check`).

use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::events::Event;
use crate::ids::DeviceId;
use crate::side_check::{Reason, Schedule};

use super::{TandemEngine, TandemError, TandemSide, join_error, side_waits};

/// The quiet sweeps to play after `side_check_start`, as stereo samples like `tandem_side_pattern`, at the rate the sound card plays (so
/// nothing resamples them near the top of what it can carry). Empty when that rate is too low: then there is nothing to check with.
#[uniffi::export]
pub fn tandem_side_quiet_pattern(rate: u32) -> Vec<f32> {
    crate::side::quiet_pattern(rate)
}

/// The lowest rate a microphone can record the quiet sweeps at: a listener that records lower says nothing, so nothing is played.
#[uniffi::export]
pub fn tandem_side_quiet_min_rate() -> u32 {
    crate::side::QUIET_MIN_RATE
}

#[uniffi::export(async_runtime = "tokio")]
impl TandemEngine {
    /// Like `side_start`, for a check that nobody asked for: the device listens only when its microphone is free and allowed, and the app
    /// plays `tandem_side_quiet_pattern`. An error when the device did not get ready, which counts as not heard.
    pub async fn side_check_start(&self, target: String) -> Result<u64, TandemError> {
        self.side_probe(target, true).await
    }

    /// What the device heard of the quiet sweeps, as the side it stands on. Surer than `side_result` before it says a side, because what
    /// it says moves a screen without asking.
    pub async fn side_check_result(&self, id: u64) -> TandemSide {
        self.side_answer(id, crate::side::QUIET_SURE_DB).await
    }
}

impl TandemEngine {
    pub(super) async fn side_probe(&self, target: String, quiet: bool) -> Result<u64, TandemError> {
        let peer = DeviceId::parse(&target)?;
        let id = crate::engine::rand_u64();
        let mut events = self.engine.subscribe();
        self.send(vec![target], crate::proto::Msg::SideListen { id, quiet }).await?;
        let (tx, rx) = tokio::sync::oneshot::channel();
        let ready = self
            .runtime
            .spawn(async move {
                // The answer comes on the same stream of events as the "ready", so nothing that comes in between is missed.
                let got_ready = tokio::time::timeout(std::time::Duration::from_secs(4), async {
                    loop {
                        match events.recv().await {
                            Ok(Event::SideReady { from, id: got }) if from == peer && got == id => break true,
                            Ok(_) | Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                            Err(_) => break false,
                        }
                    }
                })
                .await
                .unwrap_or(false);
                if !got_ready {
                    return false;
                }
                tokio::spawn(async move {
                    let wait = std::time::Duration::from_millis(u64::from(crate::side::LISTEN_MS) + 3000);
                    let heard = tokio::time::timeout(wait, async move {
                        loop {
                            match events.recv().await {
                                Ok(Event::SideHeard { from, id: got, left, right, heard }) if from == peer && got == id => break Some((left, right, heard)),
                                Ok(_) | Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                                Err(_) => break None,
                            }
                        }
                    })
                    .await
                    .ok()
                    .flatten();
                    let _ = tx.send(heard);
                });
                true
            })
            .await
            .map_err(join_error)?;
        if !ready {
            return Err(TandemError::NotConnected);
        }
        side_waits().lock().unwrap().insert(id, rx);
        Ok(id)
    }

    pub(super) async fn side_answer(&self, id: u64, sure_db: f32) -> TandemSide {
        let Some(rx) = side_waits().lock().unwrap().remove(&id) else { return TandemSide::NotHeard };
        match self.runtime.spawn(rx).await {
            Ok(Ok(Some((left, right, true)))) => match crate::side::decide_with(crate::side::Heard { left, right }, sure_db) {
                crate::side::Side::Left => TandemSide::Left,
                crate::side::Side::Right => TandemSide::Right,
                crate::side::Side::Unsure => TandemSide::Unsure,
            },
            _ => TandemSide::NotHeard,
        }
    }
}

/// What makes a quiet check worth it (see `crate::side_check`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemSideReason {
    ScreenStarted,
    Moved,
    Connected,
    Woke,
    PointerOver,
    InUse,
}

impl From<TandemSideReason> for Reason {
    fn from(reason: TandemSideReason) -> Self {
        match reason {
            TandemSideReason::ScreenStarted => Reason::ScreenStarted,
            TandemSideReason::Moved => Reason::Moved,
            TandemSideReason::Connected => Reason::Connected,
            TandemSideReason::Woke => Reason::Woke,
            TandemSideReason::PointerOver => Reason::PointerOver,
            TandemSideReason::InUse => Reason::InUse,
        }
    }
}

/// When to check the side of each device by itself. One for the app; the clock is the wall clock, see `crate::side_check`.
#[derive(uniffi::Object)]
pub struct TandemSideSchedule {
    inner: Mutex<Schedule>,
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

#[uniffi::export]
impl TandemSideSchedule {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self { inner: Mutex::new(Schedule::default()) })
    }

    /// Whether to check `device` now; a yes counts as the start of that check.
    pub fn start(&self, device: String, reason: TandemSideReason) -> bool {
        self.inner.lock().unwrap().start(&device, reason.into(), now_ms())
    }

    /// How the check ended. Only a clear left or right counts as found: unsure, not heard and not ready make the next one wait longer.
    pub fn done(&self, device: String, side: TandemSide) {
        self.inner.lock().unwrap().done(&device, matches!(side, TandemSide::Left | TandemSide::Right), now_ms());
    }
}
