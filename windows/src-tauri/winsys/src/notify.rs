//! Desktop notifications with buttons (the standard service of the desktop, `org.freedesktop.Notifications`). A window cannot put itself
//! in a corner or in front of the others on Wayland, so a question that must be seen (does this device get to send a file here?) is
//! also asked as a notification, whose buttons come back here.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use zbus::blocking::{Connection, MessageIterator};
use zbus::zvariant::Value;

type Pick = Box<dyn Fn(&str) + Send + Sync>;

/// What to do when a button of a notification is pressed, by the number the service gave the notification.
static PICKS: Mutex<Option<HashMap<u32, Pick>>> = Mutex::new(None);
static CONNECTION: OnceLock<Option<Connection>> = OnceLock::new();

/// The connection to the bus, with a thread that hears which button was pressed.
fn connection() -> Option<&'static Connection> {
    CONNECTION
        .get_or_init(|| {
            let connection = Connection::session().ok()?;
            let rule = zbus::MatchRule::builder().msg_type(zbus::message::Type::Signal).interface("org.freedesktop.Notifications").ok()?.build();
            let messages = MessageIterator::for_match_rule(rule, &connection, Some(64)).ok()?;
            std::thread::Builder::new()
                .name("tandem-notify".into())
                .spawn(move || {
                    for message in messages {
                        let Ok(message) = message else { continue };
                        let member = message.header().member().map(|m| m.as_str().to_string());
                        match member.as_deref() {
                            Some("ActionInvoked") => {
                                if let Ok((id, key)) = message.body().deserialize::<(u32, String)>() {
                                    let pick = PICKS.lock().unwrap().as_mut().and_then(|picks| picks.remove(&id));
                                    if let Some(pick) = pick {
                                        pick(&key);
                                    }
                                }
                            }
                            Some("NotificationClosed") => {
                                if let Ok((id, _reason)) = message.body().deserialize::<(u32, u32)>() {
                                    if let Some(picks) = PICKS.lock().unwrap().as_mut() {
                                        picks.remove(&id);
                                    }
                                }
                            }
                            _ => {}
                        }
                    }
                })
                .ok()?;
            Some(connection)
        })
        .as_ref()
}

/// Shows a notification with buttons, `(key, label)` each. When one is pressed, `pick` is called with its key. Gives the number of the
/// notification (for [`close`]), or nothing when the desktop has no notification service.
pub fn ask(title: &str, body: &str, buttons: &[(&str, &str)], pick: impl Fn(&str) + Send + Sync + 'static) -> Option<u32> {
    let connection = connection()?;
    let mut actions: Vec<&str> = Vec::new();
    for (key, label) in buttons {
        actions.push(key);
        actions.push(label);
    }
    let mut hints: HashMap<&str, Value> = HashMap::new();
    // A question is not a notice that can fade away unseen.
    hints.insert("urgency", Value::U8(2));
    hints.insert("desktop-entry", Value::from("nl.markmaaktmedia.tandem"));
    let reply = connection
        .call_method(
            Some("org.freedesktop.Notifications"),
            "/org/freedesktop/Notifications",
            Some("org.freedesktop.Notifications"),
            "Notify",
            &("Tandem", 0u32, "nl.markmaaktmedia.tandem", title, body, actions, hints, 0i32),
        )
        .ok()?;
    let id: u32 = reply.body().deserialize().ok()?;
    PICKS.lock().unwrap().get_or_insert_with(HashMap::new).insert(id, Box::new(pick));
    Some(id)
}

/// Takes a notification away, when what it asked has been answered some other way.
pub fn close(id: u32) {
    if let Some(connection) = connection() {
        let _ = connection.call_method(
            Some("org.freedesktop.Notifications"),
            "/org/freedesktop/Notifications",
            Some("org.freedesktop.Notifications"),
            "CloseNotification",
            &(id,),
        );
    }
    if let Some(picks) = PICKS.lock().unwrap().as_mut() {
        picks.remove(&id);
    }
}
