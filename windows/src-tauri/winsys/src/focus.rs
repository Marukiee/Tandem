//! Do Not Disturb of this computer, for a Focus that is the same on every device: whether it is on, hearing when that changes, and
//! turning on one of Tandem's own when another device asks.
//!
//! GNOME has one switch (`show-banners` in `org.gnome.desktop.notifications`, off is Do Not Disturb), which Tandem reads and sets.
//! KDE Plasma lets an app hold an inhibition of the notification server, which is what Tandem turns on and off; the person's own Do
//! Not Disturb lives in `plasmanotifyrc` and is read from there. Windows has no public way to set it; the shell announces it as a WNF
//! state, which is read. Other desktops have nothing an app can use.

/// What the desktop lets an app do with its Do Not Disturb.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Abilities {
    pub read: bool,
    pub set: bool,
}

pub fn abilities() -> Abilities {
    imp::abilities()
}

/// Whether Do Not Disturb is on now, whoever turned it on. Nothing when it cannot be read.
pub fn is_on() -> Option<bool> {
    imp::is_on()
}

/// Turns Tandem's own Do Not Disturb on or off. False when the desktop does not let it.
pub fn set(on: bool) -> bool {
    imp::set(on)
}

/// Lets go of what Tandem holds itself (the inhibition on KDE). GNOME's one switch is the person's as much as Tandem's, so it stays.
pub fn release() {
    imp::release();
}

/// Calls `changed` with the state whenever it may have changed, on a thread of its own, for as long as the app runs.
pub fn watch(changed: impl Fn(bool) + Send + 'static) {
    imp::watch(Box::new(changed));
}

type Changed = Box<dyn Fn(bool) + Send>;

#[cfg(target_os = "linux")]
mod imp {
    use std::collections::HashMap;
    use std::process::{Command, Stdio};
    use std::sync::{Mutex, OnceLock};
    use std::time::Duration;

    use zbus::blocking::Connection;
    use zbus::zvariant::{OwnedValue, Value};

    use super::{Abilities, Changed};

    #[derive(Clone, Copy, PartialEq, Eq)]
    enum Desktop {
        Gnome,
        Kde,
        Other,
    }

    const SCHEMA: &str = "org.gnome.desktop.notifications";
    const KEY: &str = "show-banners";
    const SERVICE: &str = "org.freedesktop.Notifications";
    const PATH: &str = "/org/freedesktop/Notifications";

    /// Kept for as long as the app runs: KDE drops an inhibition when the connection that asked for it goes away.
    static BUS: OnceLock<Option<Connection>> = OnceLock::new();
    static COOKIE: Mutex<Option<u32>> = Mutex::new(None);

    fn bus() -> Option<&'static Connection> {
        BUS.get_or_init(|| Connection::session().ok()).as_ref()
    }

    /// The notification server says whose it is; the session's own name is the fallback for when it has not started yet.
    fn desktop() -> Desktop {
        static DESKTOP: OnceLock<Desktop> = OnceLock::new();
        *DESKTOP.get_or_init(|| {
            let server = bus()
                .and_then(|bus| bus.call_method(Some(SERVICE), PATH, Some(SERVICE), "GetServerInformation", &()).ok())
                .and_then(|reply| reply.body().deserialize::<(String, String, String, String)>().ok())
                .map(|(name, ..)| name.to_ascii_lowercase());
            let session = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default().to_ascii_lowercase();
            match server.as_deref() {
                Some("gnome-shell") => Desktop::Gnome,
                Some("plasma") => Desktop::Kde,
                Some(_) => Desktop::Other,
                None if session.contains("gnome") => Desktop::Gnome,
                None if session.contains("kde") => Desktop::Kde,
                None => Desktop::Other,
            }
        })
    }

    pub fn abilities() -> Abilities {
        let usable = desktop() != Desktop::Other;
        Abilities { read: usable, set: usable }
    }

    fn gsettings(args: &[&str]) -> Command {
        let mut command = Command::new("gsettings");
        command.args(args);
        crate::system_env(&mut command);
        command
    }

    pub fn is_on() -> Option<bool> {
        match desktop() {
            Desktop::Gnome => {
                let out = gsettings(&["get", SCHEMA, KEY]).output().ok()?;
                parse_banners(&String::from_utf8_lossy(&out.stdout))
            }
            Desktop::Kde => Some(kde_inhibited().unwrap_or(false) || plasma_dnd_until().is_some_and(|until| until > now_local())),
            Desktop::Other => None,
        }
    }

    pub fn set(on: bool) -> bool {
        match desktop() {
            Desktop::Gnome => {
                let banners = if on { "false" } else { "true" };
                gsettings(&["set", SCHEMA, KEY, banners]).status().is_ok_and(|s| s.success())
            }
            Desktop::Kde => {
                let Some(bus) = bus() else { return false };
                let mut cookie = COOKIE.lock().unwrap();
                if on {
                    if cookie.is_some() {
                        return true;
                    }
                    let hints: HashMap<&str, Value> = HashMap::new();
                    let reply = bus.call_method(Some(SERVICE), PATH, Some(SERVICE), "Inhibit", &("nl.markmaaktmedia.tandem", "Focus", hints));
                    *cookie = reply.ok().and_then(|r| r.body().deserialize::<u32>().ok());
                    cookie.is_some()
                } else {
                    if let Some(old) = cookie.take() {
                        let _ = bus.call_method(Some(SERVICE), PATH, Some(SERVICE), "UnInhibit", &(old,));
                    }
                    true
                }
            }
            Desktop::Other => false,
        }
    }

    pub fn release() {
        if desktop() == Desktop::Kde {
            set(false);
        }
    }

    pub fn watch(changed: Changed) {
        match desktop() {
            Desktop::Gnome => {
                let _ = std::thread::Builder::new().name("tandem-focus".into()).spawn(move || loop {
                    // `monitor` prints a line for each change; when it ends (the session restarts) it is started again.
                    if let Ok(mut child) = gsettings(&["monitor", SCHEMA, KEY]).stdout(Stdio::piped()).stderr(Stdio::null()).spawn() {
                        if let Some(stdout) = child.stdout.take() {
                            use std::io::BufRead;
                            for line in std::io::BufReader::new(stdout).lines().map_while(Result::ok) {
                                if let Some(on) = line.rsplit(':').next().and_then(parse_banners) {
                                    changed(on);
                                }
                            }
                        }
                        let _ = child.wait();
                    }
                    std::thread::sleep(Duration::from_secs(5));
                });
            }
            Desktop::Kde => {
                // The inhibition is a property, the person's own switch a file with a time that runs out by itself: looked at often,
                // since both are cheap to read, and passed on only when it changed.
                let _ = std::thread::Builder::new().name("tandem-focus".into()).spawn(move || {
                    let mut last = None;
                    loop {
                        let now = is_on();
                        if now.is_some() && now != last {
                            last = now;
                            changed(now.unwrap_or(false));
                        }
                        std::thread::sleep(Duration::from_secs(2));
                    }
                });
            }
            Desktop::Other => {}
        }
    }

    /// `show-banners` as `gsettings` prints it: false means Do Not Disturb is on.
    fn parse_banners(text: &str) -> Option<bool> {
        match text.trim() {
            "true" => Some(false),
            "false" => Some(true),
            _ => None,
        }
    }

    fn kde_inhibited() -> Option<bool> {
        let reply = bus()?
            .call_method(Some(SERVICE), PATH, Some("org.freedesktop.DBus.Properties"), "Get", &(SERVICE, "Inhibited"))
            .ok()?;
        let value: OwnedValue = reply.body().deserialize().ok()?;
        bool::try_from(value).ok()
    }

    /// Until when the person turned Do Not Disturb on in Plasma, as (year, month, day, hour, minute, second) in local time.
    fn plasma_dnd_until() -> Option<[i64; 6]> {
        let config = std::env::var_os("XDG_CONFIG_HOME")
            .map(std::path::PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|home| std::path::Path::new(&home).join(".config")))?;
        parse_until(&std::fs::read_to_string(config.join("plasmanotifyrc")).ok()?)
    }

    /// The `Until` of the `[DoNotDisturb]` group, which KConfig writes as `2026,10,24,18,30,0`.
    pub(super) fn parse_until(text: &str) -> Option<[i64; 6]> {
        let mut group = false;
        for line in text.lines().map(str::trim) {
            if line.starts_with('[') {
                group = line == "[DoNotDisturb]";
            } else if group {
                if let Some(value) = line.strip_prefix("Until=") {
                    let parts: Vec<i64> = value.split(',').map(|p| p.trim().parse().ok()).collect::<Option<_>>()?;
                    let mut until = [0i64; 6];
                    for (slot, part) in until.iter_mut().zip(parts) {
                        *slot = part;
                    }
                    return Some(until);
                }
            }
        }
        None
    }

    fn now_local() -> [i64; 6] {
        let mut tm: libc::tm = unsafe { std::mem::zeroed() };
        let t = unsafe { libc::time(std::ptr::null_mut()) };
        unsafe { libc::localtime_r(&t, &mut tm) };
        [tm.tm_year as i64 + 1900, tm.tm_mon as i64 + 1, tm.tm_mday as i64, tm.tm_hour as i64, tm.tm_min as i64, tm.tm_sec as i64]
    }
}

#[cfg(windows)]
mod imp {
    use std::time::Duration;

    use windows::Win32::System::LibraryLoader::{GetModuleHandleW, GetProcAddress};
    use windows::core::{s, w};

    use super::{Abilities, Changed};

    /// `WNF_SHEL_QUIETHOURS_ACTIVE_PROFILE_CHANGED`: 0 is off, 1 priority only, 2 alarms only. What Do Not Disturb (Focus assist before
    /// Windows 11) is set to, as the shell announces it.
    const QUIET_HOURS: u64 = 0x0D83_063E_A3BF_1C75;

    type QueryWnf = unsafe extern "system" fn(*const u64, *const u8, *const u8, *mut u32, *mut u8, *mut u32) -> i32;

    fn query() -> Option<QueryWnf> {
        let ntdll = unsafe { GetModuleHandleW(w!("ntdll.dll")) }.ok()?;
        let proc = unsafe { GetProcAddress(ntdll, s!("NtQueryWnfStateData")) }?;
        Some(unsafe { std::mem::transmute::<unsafe extern "system" fn() -> isize, QueryWnf>(proc) })
    }

    pub fn abilities() -> Abilities {
        Abilities { read: is_on().is_some(), set: false }
    }

    pub fn is_on() -> Option<bool> {
        let query = query()?;
        let mut stamp = 0u32;
        let mut profile = 0u32;
        let mut size = std::mem::size_of::<u32>() as u32;
        let status = unsafe {
            query(&QUIET_HOURS, std::ptr::null(), std::ptr::null(), &mut stamp, (&mut profile as *mut u32).cast(), &mut size)
        };
        (status >= 0).then_some(profile != 0)
    }

    pub fn set(_on: bool) -> bool {
        false
    }

    pub fn release() {}

    /// The shell has no event for it that an app may wait on, so it is looked at every few seconds.
    pub fn watch(changed: Changed) {
        let _ = std::thread::Builder::new().name("tandem-focus".into()).spawn(move || {
            let mut last = None;
            loop {
                let now = is_on();
                if now.is_some() && now != last {
                    last = now;
                    changed(now.unwrap_or(false));
                }
                std::thread::sleep(Duration::from_secs(3));
            }
        });
    }
}

#[cfg(not(any(windows, target_os = "linux")))]
mod imp {
    use super::{Abilities, Changed};

    pub fn abilities() -> Abilities {
        Abilities { read: false, set: false }
    }

    pub fn is_on() -> Option<bool> {
        None
    }

    pub fn set(_on: bool) -> bool {
        false
    }

    pub fn release() {}

    pub fn watch(_changed: Changed) {}
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    #[test]
    fn the_until_of_plasma_is_found_in_its_group_only() {
        let text = "[General]\nUntil=1,1,1,1,1,1\n\n[DoNotDisturb]\nWhenScreensMirrored=false\nUntil=2026,10,24,18,30,0\n";
        assert_eq!(super::imp::parse_until(text), Some([2026, 10, 24, 18, 30, 0]));
        assert_eq!(super::imp::parse_until("[DoNotDisturb]\nWhenFullscreen=true\n"), None);
        assert_eq!(super::imp::parse_until("[DoNotDisturb]\nUntil=soon\n"), None);
    }
}
