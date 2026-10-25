//! This computer as the Bluetooth speaker of a phone it is paired with (A2DP sink), connected from this side. The phone then
//! sends what it plays the way it would to headphones, and needs no permission or question of its own.
//!
//! Linux: BlueZ on the system bus. PipeWire (WirePlumber) registers the Audio Sink endpoint when its `a2dp_sink` role is on, and
//! the adapter then lists the Audio Sink UUID; with that, `Device1.ConnectProfile` with the phone's Audio Source UUID starts it.
//! Windows 10 2004 and later: `AudioPlaybackConnection`. A Mac cannot be an A2DP sink, and elsewhere there is nothing to do.
//!
//! The phone is found among the paired devices by its Bluetooth name, since Android hands apps no Bluetooth address.

/// The Audio Source profile of the phone, the one that is connected and disconnected.
pub const A2DP_SOURCE: &str = "0000110a-0000-1000-8000-00805f9b34fb";
/// What the adapter lists once a sound server offers this computer as a Bluetooth speaker.
pub const A2DP_SINK: &str = "0000110b-0000-1000-8000-00805f9b34fb";

/// The phone's sound coming in over Bluetooth. Dropping it does not end the link; [Speaker::stop] does.
pub struct Speaker {
    inner: imp::Speaker,
}

impl Speaker {
    /// Connects to the paired phone that goes by one of `names` (the first that matches wins). The error is for the log and for the
    /// phone, in plain words.
    pub fn connect(names: &[String]) -> Result<Speaker, String> {
        imp::Speaker::connect(names).map(|inner| Speaker { inner })
    }

    /// Whether the sound still comes in this way.
    pub fn connected(&self) -> bool {
        self.inner.connected()
    }

    /// Ends the sound from the phone, and only that: the pairing and any other link stay.
    pub fn stop(self) {
        self.inner.stop()
    }

    /// The name the phone has on this computer.
    pub fn name(&self) -> &str {
        self.inner.name()
    }
}

/// Whether this computer could be a Bluetooth speaker at all (Bluetooth is there). Whether it works is only known when it is tried.
pub fn available() -> bool {
    imp::available()
}

/// Whether `name` is one of `names`, the way people see a name: without case and spaces around it.
pub fn same_name(name: &str, names: &[String]) -> bool {
    let name = name.trim().to_lowercase();
    !name.is_empty() && names.iter().any(|n| n.trim().to_lowercase() == name)
}

/// Linux: from the text of WirePlumber's configuration, whether `bluez5.roles` is set to a list that leaves out `a2dp_sink`. Only
/// for saying why it did not work; whether it works is read from BlueZ.
pub fn roles_leave_out_sink(config: &str) -> bool {
    for line in config.lines() {
        // `#` starts a comment in the .conf files of WirePlumber 0.5, `--` in the Lua of 0.4.
        let line = line.split('#').next().unwrap_or("").trim();
        if line.starts_with("--") {
            continue;
        }
        let Some(at) = line.find("bluez5.roles") else { continue };
        let rest = &line[at + "bluez5.roles".len()..];
        let Some(open) = rest.find('[') else { continue };
        let Some(close) = rest[open..].find(']') else { continue };
        let list = &rest[open + 1..open + close];
        return !list.split(|c: char| c == ',' || c == '"' || c.is_whitespace()).any(|role| role == "a2dp_sink");
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_match_without_case_or_spaces() {
        let names = vec!["Pixel 9 ".to_string(), "Mark's phone".to_string()];
        assert!(same_name("pixel 9", &names));
        assert!(same_name("Mark's Phone", &names));
        assert!(!same_name("Pixel", &names));
        assert!(!same_name("  ", &names));
    }

    #[test]
    fn a_roles_list_without_the_sink_is_seen() {
        assert!(roles_leave_out_sink("monitor.bluez.properties = {\n  bluez5.roles = [ a2dp_source hfp_ag ]\n}"));
        assert!(!roles_leave_out_sink("  bluez5.roles = [ a2dp_sink a2dp_source bap_sink ]"));
        // WirePlumber 0.4 writes it in Lua, quoted, and a comment does not count.
        assert!(!roles_leave_out_sink("--[\"bluez5.roles\"] = \"[ a2dp_source ]\",\n"));
        assert!(roles_leave_out_sink("  [\"bluez5.roles\"] = \"[ a2dp_source hsp_ag ]\","));
        assert!(!roles_leave_out_sink("# bluez5.roles = [ a2dp_source ]"));
        assert!(!roles_leave_out_sink("nothing about it"));
    }
}

#[cfg(target_os = "linux")]
mod imp {
    use std::collections::HashMap;
    use std::time::{Duration, Instant};

    use zbus::blocking::Connection;
    use zbus::zvariant::{OwnedObjectPath, OwnedValue};

    use super::{A2DP_SINK, A2DP_SOURCE, roles_leave_out_sink, same_name};

    const BLUEZ: &str = "org.bluez";
    type Managed = HashMap<OwnedObjectPath, HashMap<String, HashMap<String, OwnedValue>>>;

    fn text<E: std::fmt::Display>(error: E) -> String {
        error.to_string()
    }

    fn managed(connection: &Connection) -> Result<Managed, String> {
        let reply = connection
            .call_method(Some(BLUEZ), "/", Some("org.freedesktop.DBus.ObjectManager"), "GetManagedObjects", &())
            .map_err(|_| "Bluetooth (BlueZ) is not running on this computer".to_string())?;
        reply.body().deserialize::<Managed>().map_err(text)
    }

    fn string_of(value: Option<&OwnedValue>) -> Option<String> {
        value.and_then(|v| String::try_from(v.clone()).ok())
    }

    fn bool_of(value: Option<&OwnedValue>) -> bool {
        value.and_then(|v| bool::try_from(v).ok()).unwrap_or(false)
    }

    fn uuids_of(value: Option<&OwnedValue>) -> Vec<String> {
        value.and_then(|v| Vec::<String>::try_from(v.clone()).ok()).unwrap_or_default()
    }

    fn has(uuids: &[String], uuid: &str) -> bool {
        uuids.iter().any(|u| u.eq_ignore_ascii_case(uuid))
    }

    /// Why the sink is not offered, from WirePlumber's configuration when it says so. Read only, nothing is changed.
    fn why_no_sink() -> String {
        let home = std::env::var("HOME").unwrap_or_default();
        let config = std::env::var("XDG_CONFIG_HOME").unwrap_or_else(|_| format!("{home}/.config"));
        let mut places = vec![format!("{config}/wireplumber"), "/etc/wireplumber".to_string(), "/usr/share/wireplumber".to_string()];
        places.retain(|p| std::path::Path::new(p).exists());
        for place in &places {
            for file in files_in(std::path::Path::new(place)) {
                if let Ok(content) = std::fs::read_to_string(&file) {
                    if roles_leave_out_sink(&content) {
                        return format!("the sound server does not offer this computer as a Bluetooth speaker: bluez5.roles in {} leaves out a2dp_sink", file.display());
                    }
                }
            }
        }
        "the sound server does not offer this computer as a Bluetooth speaker (PipeWire with WirePlumber does, with its a2dp_sink role)".into()
    }

    fn files_in(dir: &std::path::Path) -> Vec<std::path::PathBuf> {
        let mut out = Vec::new();
        let Ok(entries) = std::fs::read_dir(dir) else { return out };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                out.extend(files_in(&path));
            } else if path.extension().is_some_and(|e| e == "conf" || e == "lua") {
                out.push(path);
            }
        }
        out
    }

    pub struct Speaker {
        connection: Connection,
        device: String,
        name: String,
        since: Instant,
    }

    impl Speaker {
        pub fn connect(names: &[String]) -> Result<Speaker, String> {
            let connection = Connection::system().map_err(|_| "no system bus".to_string())?;
            let objects = managed(&connection)?;
            let adapters: Vec<(&OwnedObjectPath, &HashMap<String, OwnedValue>)> =
                objects.iter().filter_map(|(path, interfaces)| interfaces.get("org.bluez.Adapter1").map(|a| (path, a))).collect();
            if adapters.is_empty() {
                return Err("this computer has no Bluetooth".into());
            }
            let Some(adapter) = adapters.iter().find(|(_, a)| bool_of(a.get("Powered"))) else {
                return Err("Bluetooth is off on this computer".into());
            };
            if !has(&uuids_of(adapter.1.get("UUIDs")), A2DP_SINK) {
                return Err(why_no_sink());
            }
            let adapter_path = adapter.0.as_str();
            let mut found: Vec<(String, String, bool)> = objects
                .iter()
                .filter_map(|(path, interfaces)| {
                    let device = interfaces.get("org.bluez.Device1")?;
                    let on_adapter = device.get("Adapter").and_then(|v| OwnedObjectPath::try_from(v.clone()).ok()).is_some_and(|a| a.as_str() == adapter_path);
                    if !on_adapter || !bool_of(device.get("Paired")) {
                        return None;
                    }
                    let name = string_of(device.get("Alias")).filter(|n| same_name(n, names)).or_else(|| string_of(device.get("Name")).filter(|n| same_name(n, names)))?;
                    if !has(&uuids_of(device.get("UUIDs")), A2DP_SOURCE) {
                        return None;
                    }
                    Some((path.to_string(), name, bool_of(device.get("Connected"))))
                })
                .collect();
            // Two paired devices with the same name: the one that is near (connected) is the likelier one.
            found.sort_by_key(|(_, _, connected)| !*connected);
            let Some((device, name, _)) = found.into_iter().next() else {
                let wanted = names.first().cloned().unwrap_or_default();
                return Err(format!("{wanted} is not paired with this computer over Bluetooth"));
            };
            let connected = connection.call_method(Some(BLUEZ), device.as_str(), Some("org.bluez.Device1"), "ConnectProfile", &(A2DP_SOURCE,));
            if let Err(error) = connected {
                let message = error.to_string();
                if !message.contains("AlreadyConnected") {
                    return Err(format!("could not connect to {name}: {message}"));
                }
            }
            Ok(Speaker { connection, device, name, since: Instant::now() })
        }

        /// Connected, and with a sound transport under the device once the phone had time to set one up.
        pub fn connected(&self) -> bool {
            let Ok(objects) = managed(&self.connection) else { return false };
            let Some(device) = objects.iter().find(|(p, _)| p.as_str() == self.device).and_then(|(_, i)| i.get("org.bluez.Device1")) else {
                return false;
            };
            if !bool_of(device.get("Connected")) {
                return false;
            }
            if self.since.elapsed() < Duration::from_secs(15) {
                return true;
            }
            let prefix = format!("{}/", self.device);
            objects.iter().any(|(path, interfaces)| path.as_str().starts_with(&prefix) && interfaces.contains_key("org.bluez.MediaTransport1"))
        }

        pub fn stop(self) {
            let _ = self.connection.call_method(Some(BLUEZ), self.device.as_str(), Some("org.bluez.Device1"), "DisconnectProfile", &(A2DP_SOURCE,));
        }

        pub fn name(&self) -> &str {
            &self.name
        }
    }

    pub fn available() -> bool {
        Connection::system().ok().and_then(|c| managed(&c).ok()).is_some_and(|objects| objects.values().any(|i| i.contains_key("org.bluez.Adapter1")))
    }
}

#[cfg(windows)]
mod imp {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc;
    use std::time::Duration;

    use windows::Devices::Enumeration::DeviceInformation;
    use windows::Media::Audio::{AudioPlaybackConnection, AudioPlaybackConnectionOpenResultStatus, AudioPlaybackConnectionState};
    use windows::Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize};

    use super::same_name;

    pub struct Speaker {
        stop: mpsc::Sender<()>,
        open: Arc<AtomicBool>,
        name: String,
    }

    fn text(error: windows::core::Error) -> String {
        error.message().to_string()
    }

    /// Finds the phone and opens the connection. The connection object must live as long as the sound plays, so it stays on the
    /// thread that made it, which also keeps WinRT's threading rules out of the rest of the app.
    fn open(names: &[String]) -> Result<(AudioPlaybackConnection, String), String> {
        let selector = AudioPlaybackConnection::GetDeviceSelector().map_err(|_| "this version of Windows cannot be a Bluetooth speaker (it needs Windows 10 2004 or later)".to_string())?;
        let devices = DeviceInformation::FindAllAsyncAqsFilter(&selector).and_then(|op| op.join()).map_err(text)?;
        let mut chosen = None;
        for device in devices {
            let name = device.Name().map(|n| n.to_string()).unwrap_or_default();
            if same_name(&name, names) {
                chosen = Some((device.Id().map_err(text)?, name));
                break;
            }
        }
        let Some((id, name)) = chosen else {
            let wanted = names.first().cloned().unwrap_or_default();
            return Err(format!("{wanted} is not paired with this computer over Bluetooth"));
        };
        let connection = AudioPlaybackConnection::TryCreateFromId(&id).map_err(text)?;
        connection.StartAsync().and_then(|op| op.join()).map_err(text)?;
        let result = connection.OpenAsync().and_then(|op| op.join()).map_err(text)?;
        match result.Status().map_err(text)? {
            AudioPlaybackConnectionOpenResultStatus::Success => Ok((connection, name)),
            AudioPlaybackConnectionOpenResultStatus::RequestTimedOut => Err(format!("{name} did not answer over Bluetooth")),
            AudioPlaybackConnectionOpenResultStatus::DeniedBySystem => Err("Windows did not allow the Bluetooth sound".into()),
            _ => {
                let code = result.ExtendedError().map(|e| e.0).unwrap_or(0);
                Err(format!("the Bluetooth sound could not start (0x{code:08x})"))
            }
        }
    }

    impl Speaker {
        pub fn connect(names: &[String]) -> Result<Speaker, String> {
            let names = names.to_vec();
            let (answer_tx, answer_rx) = mpsc::channel::<Result<String, String>>();
            let (stop_tx, stop_rx) = mpsc::channel::<()>();
            let open_flag = Arc::new(AtomicBool::new(false));
            let flag = open_flag.clone();
            std::thread::Builder::new()
                .name("tandem-bt-speaker".into())
                .spawn(move || {
                    unsafe {
                        let _ = RoInitialize(RO_INIT_MULTITHREADED);
                    }
                    let connection = match open(&names) {
                        Ok((connection, name)) => {
                            flag.store(true, Ordering::SeqCst);
                            let _ = answer_tx.send(Ok(name));
                            connection
                        }
                        Err(error) => {
                            let _ = answer_tx.send(Err(error));
                            return;
                        }
                    };
                    loop {
                        match stop_rx.recv_timeout(Duration::from_secs(2)) {
                            Ok(()) | Err(mpsc::RecvTimeoutError::Disconnected) => break,
                            Err(mpsc::RecvTimeoutError::Timeout) => {
                                let opened = connection.State().map(|s| s == AudioPlaybackConnectionState::Opened).unwrap_or(false);
                                flag.store(opened, Ordering::SeqCst);
                                if !opened {
                                    break;
                                }
                            }
                        }
                    }
                    flag.store(false, Ordering::SeqCst);
                    let _ = connection.Close();
                })
                .map_err(|e| e.to_string())?;
            let name = answer_rx.recv_timeout(Duration::from_secs(30)).map_err(|_| "Windows did not answer".to_string())??;
            Ok(Speaker { stop: stop_tx, open: open_flag, name })
        }

        pub fn connected(&self) -> bool {
            self.open.load(Ordering::SeqCst)
        }

        pub fn stop(self) {
            let _ = self.stop.send(());
        }

        pub fn name(&self) -> &str {
            &self.name
        }
    }

    pub fn available() -> bool {
        true
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    pub struct Speaker {
        name: String,
    }

    impl Speaker {
        pub fn connect(_names: &[String]) -> Result<Speaker, String> {
            Err("this computer cannot be a Bluetooth speaker".into())
        }

        pub fn connected(&self) -> bool {
            false
        }

        pub fn stop(self) {}

        pub fn name(&self) -> &str {
            &self.name
        }
    }

    pub fn available() -> bool {
        false
    }
}
