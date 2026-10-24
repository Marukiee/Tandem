//! The small floating window of a call on a phone, like the call popup of WhatsApp: who it is, which phone, Answer and Decline while it
//! rings, Hang up while it goes on, and a running timer. The talking stays on the phone or its headset. One card per call, stacked, in
//! the top right corner until it is dragged somewhere else. A missed call shows for a few seconds and leaves a notification behind.
//!
//! The window is not focused when it comes, so a call does not take the keys from what is being typed. Under Wayland an app cannot
//! place its own windows or keep them on top, so there it lands where the desktop puts it, and it is focused, or it would not be seen.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use tandem_core::ffi::{TandemCall, TandemCallAction, TandemCallState};
use tauri::{AppHandle, Emitter, LogicalSize, Manager, PhysicalPosition, State, WebviewUrl, WebviewWindowBuilder};

use crate::{events, i18n, state::AppState};

const LABEL: &str = "call-card";
const CARD_WIDTH: f64 = 320.0;
const CARD_HEIGHT: f64 = 78.0;
const GAP: f64 = 10.0;
/// Room around the cards for their shadow, which the window would otherwise cut off in a straight line.
const MARGIN: f64 = 24.0;
/// How long the page takes to fade a card out; the window keeps its size until then.
const FADE: Duration = Duration::from_millis(400);
const RINGING_AT_MOST: Duration = Duration::from_secs(120);
const MISSED_SHOWN: Duration = Duration::from_secs(4);
const DIALED_IS_RECENT: Duration = Duration::from_secs(60);
/// A call declined here is no news when the phone reports it as missed a moment later.
const CLOSED_REMEMBERED: Duration = Duration::from_secs(600);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Ringing,
    Dialing,
    Active,
    Missed,
}

impl Phase {
    fn name(self) -> &'static str {
        match self {
            Phase::Ringing => "ringing",
            Phase::Dialing => "dialing",
            Phase::Active => "active",
            Phase::Missed => "missed",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Card {
    pub device: String,
    pub call: String,
    pub device_name: String,
    pub name: Option<String>,
    pub number: Option<String>,
    pub incoming: bool,
    pub phase: Phase,
    /// When the call was answered, by the clock of this computer: the phone's clock can be off by minutes.
    pub since_ms: Option<u64>,
    /// Changes with every update, so a timer set for an older state does nothing.
    stamp: u64,
}

impl Card {
    /// Two phones can give the same id to a call (it is a time in milliseconds), so the phone is part of it.
    pub fn key(&self) -> String {
        key(&self.device, &self.call)
    }
}

fn key(device: &str, call: &str) -> String {
    format!("{device}/{call}")
}

/// What the window should do after a message from a phone.
#[derive(Debug, Default, PartialEq)]
pub struct Outcome {
    pub changed: bool,
    /// Take the card away after this long, unless it changed in the meantime.
    pub expire: Option<(String, u64, Duration)>,
    /// A missed call to leave a notification for.
    pub missed: bool,
}

/// The cards and what is needed to keep them right. Kept apart from the window so it can be tested.
#[derive(Default)]
pub struct Book {
    pub cards: Vec<Card>,
    closed_here: HashMap<String, Instant>,
    dialed: Option<(String, String, Instant)>,
    stamps: u64,
}

impl Book {
    pub fn received(&mut self, from: &str, device_name: &str, call: &TandemCall, now_ms: u64, now: Instant) -> Outcome {
        let key = key(from, &call.id);
        let index = self.cards.iter().position(|c| c.key() == key);
        self.stamps += 1;
        let stamp = self.stamps;
        match call.state {
            TandemCallState::Ringing | TandemCallState::Dialing => {
                if self.closed_here.contains_key(&key) {
                    return Outcome::default();
                }
                let phase = if call.state == TandemCallState::Ringing && call.incoming { Phase::Ringing } else { Phase::Dialing };
                match index {
                    // Ringing is sent again once the phone has looked up the name: the same card, with the name.
                    Some(i) => {
                        let card = &mut self.cards[i];
                        fill(card, call, device_name);
                        if card.phase != Phase::Active {
                            card.phase = phase;
                        }
                        card.stamp = stamp;
                    }
                    None => {
                        let card = self.new_card(from, device_name, call, phase, None, stamp, now);
                        self.cards.push(card);
                    }
                }
                // A phone that stops ringing says so. One that went away says nothing, and its card should not stay forever.
                Outcome { changed: true, expire: Some((key, stamp, RINGING_AT_MOST)), missed: false }
            }
            TandemCallState::Active => {
                if self.closed_here.contains_key(&key) {
                    return Outcome::default();
                }
                match index {
                    Some(i) => {
                        let card = &mut self.cards[i];
                        fill(card, call, device_name);
                        if card.phase != Phase::Active {
                            card.phase = Phase::Active;
                            card.since_ms = Some(now_ms);
                        }
                        card.stamp = stamp;
                    }
                    None => {
                        let card = self.new_card(from, device_name, call, Phase::Active, Some(now_ms), stamp, now);
                        self.cards.push(card);
                    }
                }
                Outcome { changed: true, expire: None, missed: false }
            }
            TandemCallState::Ended => Outcome { changed: self.remove(&key), expire: None, missed: false },
            TandemCallState::Missed => {
                if self.closed_here.remove(&key).is_some() {
                    return Outcome { changed: self.remove(&key), expire: None, missed: false };
                }
                match index {
                    Some(i) => {
                        fill(&mut self.cards[i], call, device_name);
                        self.cards[i].phase = Phase::Missed;
                        self.cards[i].stamp = stamp;
                    }
                    None => {
                        let card = self.new_card(from, device_name, call, Phase::Missed, None, stamp, now);
                        self.cards.push(card);
                    }
                }
                Outcome { changed: true, expire: Some((key, stamp, MISSED_SHOWN)), missed: true }
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn new_card(&self, from: &str, device_name: &str, call: &TandemCall, phase: Phase, since_ms: Option<u64>, stamp: u64, now: Instant) -> Card {
        let mut card = Card {
            device: from.to_string(),
            call: call.id.clone(),
            device_name: device_name.to_string(),
            name: call.name.clone(),
            number: call.number.clone(),
            incoming: call.incoming,
            phase,
            since_ms,
            stamp,
        };
        // Android does not say whom it calls; a call this computer asked for is known here.
        if !call.incoming && call.number.is_none() {
            if let Some((device, number, at)) = &self.dialed {
                if device == from && now.duration_since(*at) < DIALED_IS_RECENT {
                    card.number = Some(number.clone());
                }
            }
        }
        card
    }

    /// Decline or Hang up here: the card goes at once, whether or not the phone could be reached, because a card that cannot be closed
    /// would be worse.
    pub fn close(&mut self, key: &str, now: Instant) -> bool {
        self.closed_here.retain(|_, at| now.duration_since(*at) < CLOSED_REMEMBERED);
        self.closed_here.insert(key.to_string(), now);
        self.remove(key)
    }

    /// A timer ran out; it counts only if nothing happened to the card since it was set.
    pub fn expire(&mut self, key: &str, stamp: u64) -> bool {
        if self.cards.iter().any(|c| c.key() == key && c.stamp == stamp) { self.remove(key) } else { false }
    }

    pub fn dialed(&mut self, device: &str, number: &str, now: Instant) {
        self.dialed = Some((device.to_string(), number.to_string(), now));
    }

    fn remove(&mut self, key: &str) -> bool {
        let before = self.cards.len();
        self.cards.retain(|c| c.key() != key);
        self.cards.len() != before
    }
}

fn fill(card: &mut Card, call: &TandemCall, device_name: &str) {
    card.device_name = device_name.to_string();
    if call.name.is_some() {
        card.name = call.name.clone();
    }
    if call.number.is_some() {
        card.number = call.number.clone();
    }
}

// ---- The window ------------------------------------------------------------------------------------------------------------------

static BOOK: LazyLock<Mutex<Book>> = LazyLock::new(|| Mutex::new(Book::default()));
/// Each change of the window counts up, so a shrink that waits for a fade does nothing when something newer came.
static ROUND: AtomicU64 = AtomicU64::new(0);
/// How many cards the window has room for now.
static ROOM: AtomicUsize = AtomicUsize::new(0);
/// Where the cards were dragged to, for as long as the app runs.
static PLACE: Mutex<Option<PhysicalPosition<i32>>> = Mutex::new(None);

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// What a phone says about a call.
pub fn received(app: &AppHandle, from: &str, call: &TandemCall) {
    let device_name = events::device_name(app, from);
    let outcome = BOOK.lock().unwrap().received(from, &device_name, call, now_ms(), Instant::now());
    if outcome.missed {
        let who = call.name.clone().or_else(|| call.number.clone()).unwrap_or_else(|| i18n::t(app, "unknown_number"));
        events::toast(app, &i18n::t(app, "missed_call"), &who);
    }
    if let Some((key, stamp, after)) = outcome.expire {
        let app = app.clone();
        std::thread::spawn(move || {
            std::thread::sleep(after);
            if BOOK.lock().unwrap().expire(&key, stamp) {
                refresh(&app);
            }
        });
    }
    if outcome.changed {
        refresh(app);
    }
}

/// A call this computer asked a phone to place.
pub fn dialed(device: &str, number: &str) {
    BOOK.lock().unwrap().dialed(device, number, Instant::now());
}

fn snapshot() -> (Value, usize) {
    let book = BOOK.lock().unwrap();
    let cards: Vec<Value> = book
        .cards
        .iter()
        .map(|c| {
            json!({
                "key": c.key(),
                "device": c.device,
                "call": c.call,
                "deviceName": c.device_name,
                "name": c.name,
                "number": c.number,
                "phase": c.phase.name(),
                "since": c.since_ms,
            })
        })
        .collect();
    let count = cards.len();
    (Value::Array(cards), count)
}

fn refresh(app: &AppHandle) {
    let (cards, count) = snapshot();
    let _ = app.emit("call-cards", cards);
    let round = ROUND.fetch_add(1, Ordering::SeqCst) + 1;
    if count >= ROOM.load(Ordering::SeqCst) {
        let handle = app.clone();
        let _ = app.run_on_main_thread(move || window(&handle, count));
        return;
    }
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(FADE);
        if ROUND.load(Ordering::SeqCst) == round {
            let handle = app.clone();
            let _ = app.run_on_main_thread(move || window(&handle, count));
        }
    });
}

fn wayland() -> bool {
    std::env::var("XDG_SESSION_TYPE").is_ok_and(|s| s.eq_ignore_ascii_case("wayland")) || std::env::var_os("WAYLAND_DISPLAY").is_some()
}

fn height(count: usize) -> f64 {
    let n = count.max(1) as f64;
    n * CARD_HEIGHT + (n - 1.0) * GAP + MARGIN * 2.0
}

fn window(app: &AppHandle, count: usize) {
    let existing = app.get_webview_window(LABEL);
    ROOM.store(count, Ordering::SeqCst);
    if count == 0 {
        if let Some(window) = existing {
            if let Ok(position) = window.outer_position() {
                *PLACE.lock().unwrap() = Some(position);
            }
            let _ = window.close();
        }
        return;
    }
    let size = LogicalSize::new(CARD_WIDTH + MARGIN * 2.0, height(count));
    if let Some(window) = existing {
        // The top left stays where it is, wherever the cards were dragged, and the stack grows downwards.
        let _ = window.set_size(size);
        return;
    }
    let (x, y) = place(app);
    // Placed and shown by the builder itself: on Windows a later `show()` activates the window and takes the keys from what is typed.
    let built = WebviewWindowBuilder::new(app, LABEL, WebviewUrl::App("call.html".into()))
        .title("Tandem")
        .inner_size(size.width, size.height)
        .position(x, y)
        .decorations(false)
        .transparent(true)
        .shadow(false)
        .resizable(false)
        .always_on_top(true)
        .visible_on_all_workspaces(true)
        .skip_taskbar(true)
        .focused(wayland())
        .build();
    if let Err(error) = built {
        log::warn!("the window of a call could not be made: {error}");
    }
}

/// Where the window goes, in points: where the cards were dragged to before, if that is still on a screen, or else the top right of the
/// main screen, the cards 12 points from its right edge and just under its top.
fn place(app: &AppHandle) -> (f64, f64) {
    let monitors = app.available_monitors().unwrap_or_default();
    if let Some(p) = *PLACE.lock().unwrap() {
        let screen = monitors.iter().find(|m| {
            let (at, size) = (m.position(), m.size());
            p.x >= at.x && p.y >= at.y && p.x < at.x + size.width as i32 && p.y < at.y + size.height as i32
        });
        if let Some(screen) = screen {
            let scale = screen.scale_factor();
            return (p.x as f64 / scale, p.y as f64 / scale);
        }
    }
    let Ok(Some(monitor)) = app.primary_monitor() else { return (0.0, 0.0) };
    let area = monitor.work_area();
    let scale = monitor.scale_factor();
    let right = (area.position.x as f64 + area.size.width as f64) / scale;
    let top = area.position.y as f64 / scale;
    (right - 12.0 - CARD_WIDTH - MARGIN, (top + 12.0 - MARGIN).max(top))
}

// ---- What the page asks ----------------------------------------------------------------------------------------------------------

#[tauri::command]
pub fn call_cards() -> Value {
    snapshot().0
}

/// Answer, Decline or Hang up, pressed on a card.
#[tauri::command]
pub fn call_action(app: AppHandle, state: State<'_, AppState>, device: String, call: String, action: String) -> Result<(), String> {
    let action = match action.as_str() {
        "answer" => TandemCallAction::Answer,
        "reject" => TandemCallAction::Reject,
        "hangup" => TandemCallAction::Hangup,
        other => return Err(format!("not an action for a call: {other}")),
    };
    let closes = action != TandemCallAction::Answer;
    if closes && BOOK.lock().unwrap().close(&key(&device, &call), Instant::now()) {
        refresh(&app);
    }
    let engine = state.engine()?;
    tauri::async_runtime::spawn(async move {
        if let Err(error) = engine.call_action(device, call, action).await {
            log::info!("call: the phone could not be told: {error}");
        }
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn call(id: &str, state: TandemCallState, name: Option<&str>, number: Option<&str>, incoming: bool) -> TandemCall {
        TandemCall { id: id.into(), state, incoming, number: number.map(Into::into), name: name.map(Into::into), ts: 0 }
    }

    #[test]
    fn a_second_ringing_fills_in_the_name_on_the_same_card() {
        let mut book = Book::default();
        let now = Instant::now();
        book.received("phone", "Pixel", &call("1", TandemCallState::Ringing, None, None, true), 0, now);
        book.received("phone", "Pixel", &call("1", TandemCallState::Ringing, Some("Sanne"), Some("+316"), true), 0, now);
        assert_eq!(book.cards.len(), 1);
        assert_eq!(book.cards[0].name.as_deref(), Some("Sanne"));
        assert_eq!(book.cards[0].phase, Phase::Ringing);
    }

    #[test]
    fn the_timer_starts_at_the_first_active_and_is_not_reset() {
        let mut book = Book::default();
        let now = Instant::now();
        book.received("phone", "Pixel", &call("1", TandemCallState::Ringing, None, Some("+316"), true), 1_000, now);
        book.received("phone", "Pixel", &call("1", TandemCallState::Active, None, None, true), 5_000, now);
        book.received("phone", "Pixel", &call("1", TandemCallState::Active, None, None, true), 9_000, now);
        assert_eq!(book.cards[0].phase, Phase::Active);
        assert_eq!(book.cards[0].since_ms, Some(5_000));
        assert_eq!(book.cards[0].number.as_deref(), Some("+316"));
    }

    #[test]
    fn two_phones_with_the_same_call_id_get_two_cards() {
        let mut book = Book::default();
        let now = Instant::now();
        book.received("a", "A", &call("1", TandemCallState::Ringing, None, None, true), 0, now);
        book.received("b", "B", &call("1", TandemCallState::Ringing, None, None, true), 0, now);
        assert_eq!(book.cards.len(), 2);
        book.received("a", "A", &call("1", TandemCallState::Ended, None, None, true), 0, now);
        assert_eq!(book.cards.len(), 1);
        assert_eq!(book.cards[0].device, "b");
    }

    #[test]
    fn declined_here_is_not_reported_as_missed() {
        let mut book = Book::default();
        let now = Instant::now();
        book.received("phone", "Pixel", &call("1", TandemCallState::Ringing, None, None, true), 0, now);
        assert!(book.close("phone/1", now));
        // A late ringing does not bring the card back.
        let late = book.received("phone", "Pixel", &call("1", TandemCallState::Ringing, None, None, true), 0, now);
        assert!(!late.changed);
        let missed = book.received("phone", "Pixel", &call("1", TandemCallState::Missed, None, None, true), 0, now);
        assert!(!missed.missed);
        assert!(book.cards.is_empty());
    }

    #[test]
    fn a_missed_call_shows_and_then_goes() {
        let mut book = Book::default();
        let now = Instant::now();
        book.received("phone", "Pixel", &call("1", TandemCallState::Ringing, None, None, true), 0, now);
        let outcome = book.received("phone", "Pixel", &call("1", TandemCallState::Missed, None, None, true), 0, now);
        assert!(outcome.missed);
        assert_eq!(book.cards[0].phase, Phase::Missed);
        let (key, stamp, after) = outcome.expire.unwrap();
        assert_eq!(after, MISSED_SHOWN);
        assert!(book.expire(&key, stamp));
        assert!(book.cards.is_empty());
    }

    #[test]
    fn an_old_timer_does_not_take_a_card_that_changed() {
        let mut book = Book::default();
        let now = Instant::now();
        let first = book.received("phone", "Pixel", &call("1", TandemCallState::Ringing, None, None, true), 0, now);
        book.received("phone", "Pixel", &call("1", TandemCallState::Active, None, None, true), 0, now);
        let (key, stamp, _) = first.expire.unwrap();
        assert!(!book.expire(&key, stamp));
        assert_eq!(book.cards.len(), 1);
    }

    #[test]
    fn an_outgoing_call_takes_the_number_dialled_here() {
        let mut book = Book::default();
        let now = Instant::now();
        book.dialed("phone", "+31612345678", now);
        book.received("phone", "Pixel", &call("7", TandemCallState::Active, None, None, false), 0, now);
        assert_eq!(book.cards[0].number.as_deref(), Some("+31612345678"));
        // Another phone does not.
        book.received("other", "Other", &call("8", TandemCallState::Active, None, None, false), 0, now);
        assert_eq!(book.cards[1].number, None);
    }
}
