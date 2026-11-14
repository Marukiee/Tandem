//! Whether the lid of this laptop is closed. A laptop with a closed lid and a screen that is off is not a computer anybody is looking
//! at, so a pointer that was sent to it should come home. Two places say it: the lid button of the kernel, and the login service
//! of the system (systemd), which also knows about lids that are only an input switch.

use std::sync::OnceLock;

/// `Some(true)` when the lid is closed, `Some(false)` when it is open, `None` when this machine has no lid to ask about.
pub fn closed() -> Option<bool> {
    if let Ok(dir) = std::fs::read_dir("/proc/acpi/button/lid") {
        for entry in dir.flatten() {
            if let Some(closed) = parse_acpi(&std::fs::read_to_string(entry.path().join("state")).unwrap_or_default()) {
                return Some(closed);
            }
        }
    }
    logind()
}

/// The text of `/proc/acpi/button/lid/*/state`: "state:      closed" or "state:      open".
fn parse_acpi(text: &str) -> Option<bool> {
    let state = text.split(':').nth(1)?.trim();
    match state {
        "closed" => Some(true),
        "open" => Some(false),
        _ => None,
    }
}

/// Whether the screen of this session is locked. Nobody can use a locked computer from another one: the desktop refuses to open the
/// portals that play keys and the pointer, and a pointer sent here would go nowhere. The login service knows (the lock screen of the
/// desktop tells it); `None` when that cannot be asked.
pub fn locked() -> Option<bool> {
    use zbus::blocking::proxy::Builder;
    use zbus::proxy::CacheProperties;
    static CONNECTION: OnceLock<Option<zbus::blocking::Connection>> = OnceLock::new();
    let connection = CONNECTION.get_or_init(|| zbus::blocking::Connection::system().ok()).as_ref()?;
    // "auto" is the session of the program that asks.
    let proxy = Builder::<zbus::blocking::Proxy>::new(connection)
        .destination("org.freedesktop.login1")
        .ok()?
        .path("/org/freedesktop/login1/session/auto")
        .ok()?
        .interface("org.freedesktop.login1.Session")
        .ok()?
        .cache_properties(CacheProperties::No)
        .build()
        .ok()?;
    proxy.get_property::<bool>("LockedHint").ok()
}

/// Whether logind knows the lid itself, and so says when it opens or closes. Some machines only have the button in `/proc/acpi`.
pub fn signalled() -> bool {
    logind().is_some()
}

/// Listens to logind on the system bus: the lid (`LidClosed`), the lock of a session (`LockedHint`), and sleep (`PrepareForSleep`). `on`
/// hears each. False when the bus or the subscription is not there; when the bus goes away later, `on` hears [`crate::LoginChange::Ended`].
pub fn watch(on: impl Fn(crate::LoginChange) + Send + 'static) -> bool {
    use crate::LoginChange;
    use zbus::blocking::MessageIterator;
    let Ok(connection) = zbus::blocking::Connection::system() else { return false };
    // No sender in the rule: the signals come from the unique name of logind, not the well-known one, and every path below login1 is
    // wanted, since "session/auto" is only an alias and the signals of the session come from its real path.
    let Ok(rule) = zbus::MatchRule::builder().msg_type(zbus::message::Type::Signal).path_namespace("/org/freedesktop/login1").map(|b| b.build()) else {
        return false;
    };
    let Ok(messages) = MessageIterator::for_match_rule(rule, &connection, Some(64)) else { return false };
    std::thread::Builder::new()
        .name("tandem-logind".into())
        .spawn(move || {
            // Kept here, so the subscription lives as long as the thread.
            let _connection = connection;
            for message in messages {
                let Ok(message) = message else { continue };
                let header = message.header();
                match header.member().map(|m| m.as_str()) {
                    Some("PrepareForSleep") => {
                        if let Ok(going) = message.body().deserialize::<bool>() {
                            on(LoginChange::Sleep(going));
                        }
                    }
                    Some("PropertiesChanged") => {
                        type Changed = (String, std::collections::HashMap<String, zbus::zvariant::OwnedValue>, Vec<String>);
                        let Ok((interface, changed, invalidated)) = message.body().deserialize::<Changed>() else { continue };
                        let has = |name: &str| changed.contains_key(name) || invalidated.iter().any(|n| n == name);
                        if interface == "org.freedesktop.login1.Manager" && has("LidClosed") {
                            on(LoginChange::Lid);
                        } else if interface == "org.freedesktop.login1.Session" && has("LockedHint") {
                            on(LoginChange::Lock);
                        }
                    }
                    _ => {}
                }
            }
            on(LoginChange::Ended);
        })
        .is_ok()
}

fn logind() -> Option<bool> {
    use zbus::blocking::proxy::Builder;
    use zbus::proxy::CacheProperties;
    static CONNECTION: OnceLock<Option<zbus::blocking::Connection>> = OnceLock::new();
    let connection = CONNECTION.get_or_init(|| zbus::blocking::Connection::system().ok()).as_ref()?;
    let proxy = Builder::<zbus::blocking::Proxy>::new(connection)
        .destination("org.freedesktop.login1")
        .ok()?
        .path("/org/freedesktop/login1")
        .ok()?
        .interface("org.freedesktop.login1.Manager")
        .ok()?
        .cache_properties(CacheProperties::No)
        .build()
        .ok()?;
    proxy.get_property::<bool>("LidClosed").ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_lid_button_of_the_kernel_is_read() {
        assert_eq!(parse_acpi("state:      closed\n"), Some(true));
        assert_eq!(parse_acpi("state:      open\n"), Some(false));
        assert_eq!(parse_acpi("state:      unknown\n"), None);
        assert_eq!(parse_acpi(""), None);
    }
}
