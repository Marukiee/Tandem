//! The Wi-Fi of this computer: which network it is on, the password the system saved for it, and joining a network another
//! device handed over. Linux asks NetworkManager (`nmcli` to read, its D-Bus service to join, so a password never stands on a
//! command line where every user can see it), Windows its WLAN service through `netsh`, with the profile as XML, which reads the
//! same in every language. Elsewhere there is no Wi-Fi to share.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Security {
    Open,
    /// WPA or WPA2 with a password, and a network that takes WPA2 and WPA3 alike.
    Wpa2,
    /// WPA3 only.
    Wpa3,
    /// 802.1X: a sign-in for each person.
    Enterprise,
    /// WEP, OWE, or something unknown.
    Other,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Network {
    pub ssid: String,
    pub security: Security,
    pub hidden: bool,
    /// The name the system keeps the network under (a connection of NetworkManager, a profile of Windows).
    pub profile: String,
}

/// Whether this computer has Wi-Fi that Tandem can read and join.
pub fn available() -> bool {
    imp::available()
}

/// The network this computer is on now.
pub fn current() -> Option<Network> {
    imp::current()
}

/// The password the system saved for `network`. `None` when it did not hand it over.
pub fn password(network: &Network) -> Option<String> {
    imp::password(network)
}

/// Joins a network and keeps it, as if it was picked by hand. Waits until this computer is on it.
pub fn join(network: &Network, password: &str) -> Result<(), String> {
    imp::join(network, password)?;
    for _ in 0..30 {
        std::thread::sleep(std::time::Duration::from_secs(1));
        if current().is_some_and(|now| now.ssid == network.ssid) {
            return Ok(());
        }
    }
    Err(format!("not on {} after half a minute", network.ssid))
}

/// Whether this computer has any network now: Wi-Fi, a cable or anything else with a way out. A computer without one is offered to
/// take over the Wi-Fi of another device.
pub fn connected() -> bool {
    imp::connected().unwrap_or_else(routed)
}

/// The Wi-Fi networks this computer has saved: it joins those by itself, so they are not offered.
pub fn known() -> Vec<String> {
    imp::known()
}

/// Whether the system has a route out. Connecting a UDP socket sends nothing; without a network it fails at once.
fn routed() -> bool {
    std::net::UdpSocket::bind("0.0.0.0:0").and_then(|socket| socket.connect("192.0.2.1:9")).is_ok()
}

/// From `nmcli -t -f STATE general`: "connected", "connected (site only)" and "connected (local only)" all have a network.
pub fn nm_connected(text: &str) -> bool {
    text.trim().starts_with("connected")
}

/// The Wi-Fi connections in `nmcli -t -f NAME,TYPE connection show`. NetworkManager names a connection after its network unless
/// someone renamed it.
pub fn nm_known(text: &str) -> Vec<String> {
    text.lines()
        .map(split_terse)
        .filter(|fields| fields.len() == 2 && fields[1] == "802-11-wireless" && !fields[0].is_empty())
        .map(|mut fields| fields.swap_remove(0))
        .collect()
}

/// The profiles in `netsh wlan show profiles`: every "label : name" line, the label in the language of Windows.
pub fn netsh_profiles(text: &str) -> Vec<String> {
    text.lines().filter_map(|line| line.split_once(" : ")).map(|(_, name)| name.trim().to_string()).filter(|n| !n.is_empty()).collect()
}

/// The kind of security from what NetworkManager calls its key management.
pub fn security_of_key_mgmt(key_mgmt: &str) -> Security {
    match key_mgmt.trim() {
        "" => Security::Open,
        "wpa-psk" => Security::Wpa2,
        "sae" => Security::Wpa3,
        "wpa-eap" | "wpa-eap-suite-b-192" | "ieee8021x" => Security::Enterprise,
        _ => Security::Other,
    }
}

/// The connection of the Wi-Fi device that is up, from `nmcli -t -f DEVICE,TYPE,STATE,CONNECTION device`. A name may hold a `:`,
/// which terse output writes as `\:`.
pub fn nm_active_connection(devices: &str) -> Option<String> {
    devices.lines().find_map(|line| {
        let fields = split_terse(line);
        (fields.len() >= 4 && fields[1] == "wifi" && fields[2] == "connected" && !fields[3].is_empty()).then(|| fields[3].clone())
    })
}

fn split_terse(line: &str) -> Vec<String> {
    let mut fields = vec![String::new()];
    let mut chars = line.chars();
    while let Some(c) = chars.next() {
        match c {
            '\\' => {
                if let Some(next) = chars.next() {
                    fields.last_mut().unwrap().push(next);
                }
            }
            ':' => fields.push(String::new()),
            _ => fields.last_mut().unwrap().push(c),
        }
    }
    fields
}

/// The network of a connection, from `nmcli -e no -t -f 802-11-wireless,802-11-wireless-security connection show id <name>`: one
/// `property:value` line each. A connection without the security part is an open network.
pub fn nm_network(profile: &str, text: &str) -> Option<Network> {
    let mut ssid = None;
    let mut hidden = false;
    let mut key_mgmt = String::new();
    for line in text.lines() {
        let Some((key, value)) = line.split_once(':') else { continue };
        match key {
            "802-11-wireless.ssid" => ssid = Some(value.to_string()),
            "802-11-wireless.hidden" => hidden = value == "yes",
            "802-11-wireless-security.key-mgmt" => key_mgmt = value.to_string(),
            _ => {}
        }
    }
    let ssid = ssid.filter(|s| !s.is_empty())?;
    Some(Network { ssid, security: security_of_key_mgmt(&key_mgmt), hidden, profile: profile.to_string() })
}

/// The SSID in `netsh wlan show interfaces`. The words of that output are in the language of Windows, but "SSID" is the same in all.
pub fn netsh_ssid(text: &str) -> Option<String> {
    text.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        (key.trim() == "SSID").then(|| value.trim().to_string()).filter(|v| !v.is_empty())
    })
}

/// What a Windows profile (as `netsh wlan export profile` writes it) says: the network, and the password when it was asked in clear.
pub fn netsh_profile(profile: &str, xml: &str) -> Option<(Network, Option<String>)> {
    let ssid = tag(after(xml, "<SSIDConfig>")?, "name")?;
    let security = match tag(xml, "authentication").as_deref() {
        Some("open") => Security::Open,
        Some("WPAPSK" | "WPA2PSK") => Security::Wpa2,
        Some("WPA3SAE") => Security::Wpa3,
        Some("WPA" | "WPA2" | "WPA3" | "WPA3ENT" | "WPA3ENT192") => Security::Enterprise,
        _ => Security::Other,
    };
    let hidden = tag(xml, "nonBroadcast").as_deref() == Some("true");
    let clear = tag(xml, "protected").as_deref() == Some("false");
    let key = if clear { tag(xml, "keyMaterial") } else { None };
    Some((Network { ssid, security, hidden, profile: profile.to_string() }, key))
}

/// A Windows profile for a network another device handed over. The password goes in the file and not on a command line.
pub fn netsh_profile_xml(network: &Network, password: &str) -> String {
    let (authentication, encryption) = match network.security {
        Security::Wpa3 => ("WPA3SAE", "AES"),
        Security::Open => ("open", "none"),
        _ => ("WPA2PSK", "AES"),
    };
    let key = if network.security == Security::Open {
        String::new()
    } else {
        format!(
            "<sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{}</keyMaterial></sharedKey>",
            escape(password)
        )
    };
    let ssid = escape(&network.ssid);
    format!(
        "<?xml version=\"1.0\"?>\n<WLANProfile xmlns=\"http://www.microsoft.com/networking/WLAN/profile/v1\"><name>{ssid}</name>\
<SSIDConfig><SSID><name>{ssid}</name></SSID><nonBroadcast>{}</nonBroadcast></SSIDConfig><connectionType>ESS</connectionType>\
<connectionMode>auto</connectionMode><MSM><security><authEncryption><authentication>{authentication}</authentication>\
<encryption>{encryption}</encryption><useOneX>false</useOneX></authEncryption>{key}</security></MSM></WLANProfile>\n",
        network.hidden
    )
}

/// The network of a profile, read again only when the profile changes: it is asked for every few seconds.
#[cfg(any(target_os = "linux", windows))]
fn remembered(profile: &str, read: impl FnOnce() -> Option<Network>) -> Option<Network> {
    static LAST: std::sync::Mutex<Option<Network>> = std::sync::Mutex::new(None);
    let mut last = LAST.lock().unwrap();
    if let Some(known) = last.as_ref().filter(|n| n.profile == profile) {
        return Some(known.clone());
    }
    *last = read();
    last.clone()
}

fn after<'a>(text: &'a str, marker: &str) -> Option<&'a str> {
    text.find(marker).map(|at| &text[at + marker.len()..])
}

fn tag(xml: &str, name: &str) -> Option<String> {
    let open = format!("<{name}>");
    let close = format!("</{name}>");
    let start = xml.find(&open)? + open.len();
    let end = start + xml[start..].find(&close)?;
    Some(unescape(&xml[start..end]))
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&apos;")
}

fn unescape(text: &str) -> String {
    text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

#[cfg(any(target_os = "linux", windows))]
fn run(program: &str, args: &[&str]) -> Option<String> {
    let mut command = std::process::Command::new(program);
    command.args(args).stdin(std::process::Stdio::null()).env("LC_ALL", "C");
    crate::system_env(&mut command);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // No console window flashing up.
        command.creation_flags(0x0800_0000);
    }
    let output = command.output().ok()?;
    output.status.success().then(|| String::from_utf8_lossy(&output.stdout).into_owned())
}

#[cfg(target_os = "linux")]
mod imp {
    use std::collections::HashMap;

    use zbus::blocking::{Connection, Proxy};
    use zbus::zvariant::{ObjectPath, OwnedObjectPath, Value};

    use super::{Network, Security, run};

    pub fn available() -> bool {
        run("nmcli", &["-t", "-f", "TYPE", "device"]).is_some_and(|text| text.lines().any(|l| l == "wifi"))
    }

    pub fn connected() -> Option<bool> {
        run("nmcli", &["-t", "-f", "STATE", "general"]).map(|text| super::nm_connected(&text))
    }

    pub fn known() -> Vec<String> {
        run("nmcli", &["-t", "-f", "NAME,TYPE", "connection", "show"]).map(|text| super::nm_known(&text)).unwrap_or_default()
    }

    pub fn current() -> Option<Network> {
        let devices = run("nmcli", &["-t", "-f", "DEVICE,TYPE,STATE,CONNECTION", "device"])?;
        let profile = super::nm_active_connection(&devices)?;
        super::remembered(&profile, || {
            let text = run("nmcli", &["-e", "no", "-t", "-f", "802-11-wireless,802-11-wireless-security", "connection", "show", "id", &profile])?;
            super::nm_network(&profile, &text)
        })
    }

    pub fn password(network: &Network) -> Option<String> {
        if network.security == Security::Open {
            return Some(String::new());
        }
        let text = run("nmcli", &["-e", "no", "-s", "-g", "802-11-wireless-security.psk", "connection", "show", "id", &network.profile])?;
        // Only the line end is the tool's: a password may begin or end with a space.
        let password = text.strip_suffix('\n').unwrap_or(&text).to_string();
        (!password.is_empty()).then_some(password)
    }

    pub fn join(network: &Network, password: &str) -> Result<(), String> {
        let text = |e: zbus::Error| e.to_string();
        let bus = Connection::system().map_err(text)?;
        let nm = Proxy::new(&bus, "org.freedesktop.NetworkManager", "/org/freedesktop/NetworkManager", "org.freedesktop.NetworkManager")
            .map_err(text)?;
        let devices: Vec<OwnedObjectPath> = nm.call("GetDevices", &()).map_err(text)?;
        // 2 is a Wi-Fi device.
        let device = devices
            .into_iter()
            .find(|path| {
                Proxy::new(&bus, "org.freedesktop.NetworkManager", path.as_str(), "org.freedesktop.NetworkManager.Device")
                    .and_then(|device| device.get_property::<u32>("DeviceType"))
                    .is_ok_and(|kind| kind == 2)
            })
            .ok_or_else(|| "no Wi-Fi device".to_string())?;
        let mut connection: HashMap<&str, Value> = HashMap::new();
        connection.insert("id", Value::from(network.ssid.as_str()));
        connection.insert("type", Value::from("802-11-wireless"));
        let mut wireless: HashMap<&str, Value> = HashMap::new();
        wireless.insert("ssid", Value::from(network.ssid.as_bytes().to_vec()));
        wireless.insert("mode", Value::from("infrastructure"));
        wireless.insert("hidden", Value::from(network.hidden));
        let mut settings: HashMap<&str, HashMap<&str, Value>> = HashMap::new();
        settings.insert("connection", connection);
        settings.insert("802-11-wireless", wireless);
        let key_mgmt = match network.security {
            Security::Wpa2 => Some("wpa-psk"),
            Security::Wpa3 => Some("sae"),
            _ => None,
        };
        if let Some(key_mgmt) = key_mgmt {
            let mut security: HashMap<&str, Value> = HashMap::new();
            security.insert("key-mgmt", Value::from(key_mgmt));
            security.insert("psk", Value::from(password));
            settings.insert("802-11-wireless-security", security);
        }
        let none = ObjectPath::try_from("/").map_err(|e| e.to_string())?;
        let _: (OwnedObjectPath, OwnedObjectPath) = nm.call("AddAndActivateConnection", &(settings, device, none)).map_err(text)?;
        Ok(())
    }
}

#[cfg(windows)]
mod imp {
    use super::{Network, run};

    pub fn available() -> bool {
        // Without a wireless interface netsh answers with one sentence, and with one it lists its fields as "name : value".
        run("netsh", &["wlan", "show", "interfaces"]).is_some_and(|text| text.lines().any(|l| l.contains(" : ")))
    }

    pub fn connected() -> Option<bool> {
        None
    }

    pub fn known() -> Vec<String> {
        run("netsh", &["wlan", "show", "profiles"]).map(|text| super::netsh_profiles(&text)).unwrap_or_default()
    }

    /// The profile of the network, written to a folder of its own and read back. With `clear` the password is in it.
    fn export(profile: &str, clear: bool) -> Option<(Network, Option<String>)> {
        let folder = std::env::temp_dir().join(format!("tandem-wifi-{}", std::process::id()));
        std::fs::create_dir_all(&folder).ok()?;
        let folder_arg = format!("folder={}", folder.display());
        let name_arg = format!("name={profile}");
        let mut args = vec!["wlan", "export", "profile", name_arg.as_str(), folder_arg.as_str()];
        if clear {
            args.push("key=clear");
        }
        let exported = run("netsh", &args);
        let found = exported.and_then(|_| {
            let file = std::fs::read_dir(&folder).ok()?.flatten().find(|e| e.path().extension().is_some_and(|x| x == "xml"))?;
            std::fs::read_to_string(file.path()).ok()
        });
        let _ = std::fs::remove_dir_all(&folder);
        super::netsh_profile(profile, &found?)
    }

    pub fn current() -> Option<Network> {
        let ssid = super::netsh_ssid(&run("netsh", &["wlan", "show", "interfaces"])?)?;
        // Windows names a profile after its network; the profile is where the kind of security is written down.
        super::remembered(&ssid, || export(&ssid, false).map(|(network, _)| network))
    }

    pub fn password(network: &Network) -> Option<String> {
        if network.security == super::Security::Open {
            return Some(String::new());
        }
        export(&network.profile, true).and_then(|(_, key)| key).filter(|k| !k.is_empty())
    }

    pub fn join(network: &Network, password: &str) -> Result<(), String> {
        let folder = std::env::temp_dir().join(format!("tandem-wifi-{}", std::process::id()));
        std::fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
        let file = folder.join("profile.xml");
        std::fs::write(&file, super::netsh_profile_xml(network, password)).map_err(|e| e.to_string())?;
        let added = run("netsh", &["wlan", "add", "profile", &format!("filename={}", file.display()), "user=current"]);
        let _ = std::fs::remove_dir_all(&folder);
        added.ok_or_else(|| "Windows did not take the profile".to_string())?;
        run("netsh", &["wlan", "connect", &format!("name={}", network.ssid)]).map(|_| ()).ok_or_else(|| "Windows did not connect".to_string())
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    use super::Network;

    pub fn available() -> bool {
        false
    }

    pub fn connected() -> Option<bool> {
        None
    }

    pub fn known() -> Vec<String> {
        Vec::new()
    }

    pub fn current() -> Option<Network> {
        None
    }

    pub fn password(_: &Network) -> Option<String> {
        None
    }

    pub fn join(_: &Network, _: &str) -> Result<(), String> {
        Err("no Wi-Fi here".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saved_networks_and_the_state_are_read_from_the_tools() {
        let nm = "Home:802-11-wireless\nWired connection 1:802-3-ethernet\nCafe\\: upstairs:802-11-wireless\nlo:loopback\n";
        assert_eq!(nm_known(nm), vec!["Home".to_string(), "Cafe: upstairs".to_string()]);
        assert!(nm_connected("connected (site only)\n"));
        assert!(!nm_connected("disconnected\n"));
        assert!(!nm_connected("connecting\n"));
        let netsh = "Profiles on interface Wi-Fi:\r\n\r\nGroup policy profiles (read only)\r\n---------------------------------\r\n    <None>\r\n\r\nUser profiles\r\n-------------\r\n    All User Profile     : Home\r\n    Alle gebruikersprofielen : Cafe 2\r\n";
        assert_eq!(netsh_profiles(netsh), vec!["Home".to_string(), "Cafe 2".to_string()]);
    }

    #[test]
    fn the_wifi_connection_that_is_up_is_found_also_with_a_colon_in_its_name() {
        let devices = "eth0:ethernet:unavailable:\nwlp2s0:wifi:connected:Cafe\\: guests\nlo:loopback:connected (externally):lo\n";
        assert_eq!(nm_active_connection(devices).as_deref(), Some("Cafe: guests"));
        assert_eq!(nm_active_connection("wlp2s0:wifi:disconnected:\n"), None);
    }

    #[test]
    fn a_connection_reads_as_a_network() {
        let text = "802-11-wireless.ssid:Home\n802-11-wireless.mode:infrastructure\n802-11-wireless.hidden:yes\n802-11-wireless-security.key-mgmt:sae\n";
        let network = nm_network("Home 1", text).unwrap();
        assert_eq!(network, Network { ssid: "Home".into(), security: Security::Wpa3, hidden: true, profile: "Home 1".into() });
        // No security part at all: an open network.
        let open = nm_network("Free", "802-11-wireless.ssid:Free\n802-11-wireless.hidden:no\n").unwrap();
        assert_eq!((open.security, open.hidden), (Security::Open, false));
        assert_eq!(nm_network("x", "802-11-wireless.ssid:\n"), None);
    }

    #[test]
    fn key_management_says_what_can_be_shared() {
        assert_eq!(security_of_key_mgmt("wpa-psk"), Security::Wpa2);
        assert_eq!(security_of_key_mgmt("wpa-eap"), Security::Enterprise);
        assert_eq!(security_of_key_mgmt("none"), Security::Other);
        assert_eq!(security_of_key_mgmt("owe"), Security::Other);
    }

    #[test]
    fn netsh_names_the_network_in_any_language() {
        let dutch = "    Naam                   : Wi-Fi\n    Status                 : verbonden\n    SSID                   : Thuis\n    BSSID                  : aa:bb:cc:dd:ee:ff\n";
        assert_eq!(netsh_ssid(dutch).as_deref(), Some("Thuis"));
        assert_eq!(netsh_ssid("    Status : niet verbonden\n"), None);
    }

    #[test]
    fn a_profile_written_here_reads_back_the_same() {
        let network = Network { ssid: "Tom & Jerry's".into(), security: Security::Wpa2, hidden: true, profile: "Tom & Jerry's".into() };
        let xml = netsh_profile_xml(&network, "p<a>ss");
        assert!(!xml.contains("p<a>ss"), "the password is escaped in the XML");
        let (back, key) = netsh_profile("Tom & Jerry's", &xml).unwrap();
        assert_eq!(back, network);
        assert_eq!(key.as_deref(), Some("p<a>ss"));
    }

    #[test]
    fn a_protected_key_is_not_a_password() {
        let xml = "<WLANProfile><name>Office</name><SSIDConfig><SSID><hex>4F</hex><name>Office</name></SSID></SSIDConfig><MSM><security>\
<authEncryption><authentication>WPA2</authentication></authEncryption><sharedKey><protected>true</protected><keyMaterial>01000000D08C</keyMaterial>\
</sharedKey></security></MSM></WLANProfile>";
        let (network, key) = netsh_profile("Office", xml).unwrap();
        assert_eq!(network.security, Security::Enterprise);
        assert_eq!(key, None);
    }
}
