//! Desktop notifications with buttons (the standard service of the desktop, `org.freedesktop.Notifications`). A window cannot put itself
//! in a corner or in front of the others on Wayland, so a question that must be seen (does this device get to send a file here?) is
//! also asked as a notification, whose buttons come back here. A press on a notification also gives a token that lets another program
//! come to the front (the desktop does not let an app do that by itself), which `show_in_folder` hands to the file manager.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use zbus::blocking::{Connection, MessageIterator};
use zbus::zvariant::Value;

type Pick = Box<dyn Fn(&str, Option<&str>) + Send + Sync>;

/// What to do when a button of a notification is pressed, by the number the service gave the notification.
static PICKS: Mutex<Option<HashMap<u32, Pick>>> = Mutex::new(None);
/// The token that came with a press, before the press itself is told.
static TOKENS: Mutex<Option<HashMap<u32, String>>> = Mutex::new(None);
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
                            Some("ActivationToken") => {
                                if let Ok((id, token)) = message.body().deserialize::<(u32, String)>() {
                                    TOKENS.lock().unwrap().get_or_insert_with(HashMap::new).insert(id, token);
                                }
                            }
                            Some("ActionInvoked") => {
                                if let Ok((id, key)) = message.body().deserialize::<(u32, String)>() {
                                    let pick = PICKS.lock().unwrap().as_mut().and_then(|picks| picks.remove(&id));
                                    let token = TOKENS.lock().unwrap().as_mut().and_then(|tokens| tokens.remove(&id));
                                    if let Some(pick) = pick {
                                        pick(&key, token.as_deref());
                                    }
                                }
                            }
                            Some("NotificationClosed") => {
                                if let Ok((id, _reason)) = message.body().deserialize::<(u32, u32)>() {
                                    if let Some(picks) = PICKS.lock().unwrap().as_mut() {
                                        picks.remove(&id);
                                    }
                                    if let Some(tokens) = TOKENS.lock().unwrap().as_mut() {
                                        tokens.remove(&id);
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
    // A question is not a notice that can fade away unseen.
    notify(title, body, buttons, 2, Box::new(move |key, _| pick(key)))
}

/// A notice with buttons, which may fade into the list of the desktop like any other. `pick` gets the key of the button (`default` for
/// a press on the notice itself, when a button with that key is given) and the token for bringing a program to the front.
pub fn tell(title: &str, body: &str, buttons: &[(&str, &str)], pick: impl Fn(&str, Option<&str>) + Send + Sync + 'static) -> Option<u32> {
    notify(title, body, buttons, 1, Box::new(pick))
}

fn notify(title: &str, body: &str, buttons: &[(&str, &str)], urgency: u8, pick: Pick) -> Option<u32> {
    let connection = connection()?;
    let mut actions: Vec<&str> = Vec::new();
    for (key, label) in buttons {
        actions.push(key);
        actions.push(label);
    }
    let mut hints: HashMap<&str, Value> = HashMap::new();
    hints.insert("urgency", Value::U8(urgency));
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
    PICKS.lock().unwrap().get_or_insert_with(HashMap::new).insert(id, pick);
    Some(id)
}

/// Opens the folder of a file in the file manager with the file selected (`org.freedesktop.FileManager1`), in front when a token from a
/// press is given. False when there is no file manager that takes the request.
pub fn show_in_folder(path: &std::path::Path, token: Option<&str>) -> bool {
    let Some(connection) = connection() else { return false };
    let uri = crate::file_uri(&path.to_string_lossy());
    connection
        .call_method(
            Some("org.freedesktop.FileManager1"),
            "/org/freedesktop/FileManager1",
            Some("org.freedesktop.FileManager1"),
            "ShowItems",
            &(vec![uri.as_str()], token.unwrap_or("")),
        )
        .is_ok()
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
