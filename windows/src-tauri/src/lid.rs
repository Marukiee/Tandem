//! The lid of a laptop, and the lock of the screen. With the lid closed nobody is looking at the screen, so nothing may keep using this
//! computer from another one: a pointer that was sent here comes home at once, and does not come over while the lid is down. A person
//! who works with the lid closed on purpose (the laptop on a dock, with another screen) turns that on in the settings. A locked screen
//! cannot be used from outside at all (the desktop refuses to let a program play keys and the pointer then), so it is the same.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Sender, channel};
use std::sync::Mutex;
use std::time::{Duration, SystemTime};

use tandem_winsys::LoginChange;
use tauri::{AppHandle, Manager};

use crate::state::{AppState, Held};

use crate::settings;

/// Whether this machine has a lid that can be asked about.
pub fn present() -> bool {
    static PRESENT: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *PRESENT.get_or_init(|| tandem_winsys::lid_closed().is_some())
}

/// Whether other computers must stay away from this one now.
pub fn blocked(app: &AppHandle) -> bool {
    locked() || (!usable_closed(app) && tandem_winsys::lid_closed() == Some(true))
}

/// Keep awake with the lid closed is there to reach a closed laptop, so it keeps it usable too.
fn usable_closed(app: &AppHandle) -> bool {
    let s = settings::get(app);
    s.keep_when_lid_closed || (s.keep_awake && s.keep_awake_lid)
}

/// Whether the screen is locked now.
pub fn locked() -> bool {
    tandem_winsys::screen_locked() == Some(true)
}

/// What the watcher remembers between two looks.
#[derive(Default)]
struct Look {
    was_closed: bool,
    was_locked: bool,
}

/// What a look asks for.
#[derive(Debug, Default, PartialEq)]
struct Steps {
    /// The screen was just unlocked: the portals for keys and the pointer are asked for again now, not when the next pointer comes.
    warm_up: bool,
    /// Nobody can use this computer now: a pointer that was sent here goes home.
    stop_here: bool,
    /// And the screen that others look at stops.
    stop_hosting: bool,
}

impl Look {
    fn step(&mut self, closed: bool, locked: bool, usable_closed: bool, remote_input: bool) -> Steps {
        let warm_up = self.was_locked && !locked && remote_input;
        self.was_locked = locked;
        let away = locked || (closed && !usable_closed);
        let steps = Steps { warm_up, stop_here: away, stop_hosting: away && !self.was_closed };
        self.was_closed = closed;
        steps
    }
}

enum Wake {
    Login(LoginChange),
    /// A setting that matters here changed.
    Look,
}

static NUDGE: Mutex<Option<Sender<Wake>>> = Mutex::new(None);
static LISTENING: AtomicBool = AtomicBool::new(false);

/// Whether the lid and the lock are heard from logind, rather than looked at every second.
pub fn listening() -> bool {
    LISTENING.load(Ordering::Relaxed)
}

/// Looks again now: a setting about the lid or the pointer changed.
pub fn nudge() {
    if let Some(tx) = NUDGE.held().as_ref() {
        let _ = tx.send(Wake::Look);
    }
}

/// Watches the lid and the lock. When the lid closes while another computer has the pointer, the pointer goes back. On Linux it listens
/// to logind, which says when the lid, the lock or sleep changes; where that cannot be subscribed to (Windows, no system bus), it looks
/// every second as before.
pub fn start(app: AppHandle) {
    // A desktop without a lid still has a lock.
    std::thread::Builder::new()
        .name("tandem-lid".into())
        .spawn(move || {
            let (tx, rx) = channel::<Wake>();
            *NUDGE.held() = Some(tx.clone());
            let mut heard = tandem_winsys::watch_login(move |change| {
                let _ = tx.send(Wake::Login(change));
            });
            LISTENING.store(heard, Ordering::Relaxed);
            // Some laptops have the lid only in /proc/acpi, which says nothing when it changes: that is still looked at.
            let lid_heard = tandem_winsys::lid_signalled();
            let mut look = Look::default();
            let mut last_look = SystemTime::now();
            // The first look is now, before any signal: the lid may be closed already.
            let mut first = true;
            loop {
                let wait = if std::mem::take(&mut first) {
                    Some(Duration::ZERO)
                } else if !heard {
                    Some(Duration::from_secs(1))
                } else if present() && !lid_heard {
                    Some(Duration::from_secs(2))
                } else {
                    None
                };
                let woke = match wait {
                    Some(wait) => rx.recv_timeout(wait).ok(),
                    None => rx.recv().ok(),
                };
                let mut woke_up = false;
                match woke {
                    Some(Wake::Login(LoginChange::Ended)) => {
                        log::info!("logind went away, the lid and the lock are looked at every second again");
                        heard = false;
                        LISTENING.store(false, Ordering::Relaxed);
                    }
                    Some(Wake::Login(LoginChange::Sleep(going))) => woke_up = !going,
                    _ => {}
                }
                // Far more time went by than a look takes: the computer was asleep. Only needed without logind, which says so itself.
                if !heard && last_look.elapsed().is_ok_and(|gone| gone > Duration::from_secs(20)) {
                    woke_up = true;
                }
                last_look = SystemTime::now();
                // The others have long given up on a computer that slept. It tries them now.
                if woke_up {
                    if let Ok(engine) = app.state::<AppState>().engine() {
                        engine.network_changed();
                    }
                }
                let steps = look.step(tandem_winsys::lid_closed() == Some(true), locked(), usable_closed(&app), settings::get(&app).remote_input);
                if steps.warm_up {
                    crate::input::warm_up();
                }
                if steps.stop_here {
                    crate::input::shared_stop_here();
                }
                if steps.stop_hosting {
                    #[cfg(feature = "screen-host")]
                    crate::host::stop_all(&app);
                }
                // Without the look every two seconds of unlock.rs, the other devices hear of a lock from here.
                if heard {
                    crate::unlock::announce(&app);
                }
            }
        })
        .ok();
}

#[cfg(test)]
mod tests {
    use super::{Look, Steps};

    #[test]
    fn a_closed_lid_or_a_lock_sends_the_pointer_home() {
        let mut look = Look::default();
        assert_eq!(look.step(false, false, false, true), Steps::default());
        // The lid closes: the pointer goes home and the screen others watch stops, once.
        assert_eq!(look.step(true, false, false, true), Steps { warm_up: false, stop_here: true, stop_hosting: true });
        assert_eq!(look.step(true, false, false, true), Steps { warm_up: false, stop_here: true, stop_hosting: false });
        // Kept usable with the lid closed (a laptop on a dock), nothing happens.
        let mut docked = Look::default();
        assert_eq!(docked.step(true, false, true, true), Steps::default());
    }

    #[test]
    fn after_the_unlock_the_portals_are_asked_for_again() {
        let mut look = Look::default();
        assert!(look.step(false, true, false, true).stop_here);
        assert_eq!(look.step(false, false, false, true), Steps { warm_up: true, stop_here: false, stop_hosting: false });
        // Not when the pointer of others is switched off here.
        let mut off = Look::default();
        off.step(false, true, false, false);
        assert!(!off.step(false, false, false, false).warm_up);
    }
}
