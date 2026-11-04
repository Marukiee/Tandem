//! Finding devices on the local network with mDNS.
//!
//! The announcement does not carry the device id. It carries a short hint that
//! changes every hour and can only be matched by someone who already knows the id,
//! so a stranger on the same Wi-Fi cannot follow a device around.

use std::collections::HashSet;
use std::net::{IpAddr, SocketAddr};

use mdns_sd::{IfKind, ServiceDaemon, ServiceEvent, ServiceInfo};
use ring::digest;
use tokio::sync::mpsc;

use crate::error::{Error, Result};
use crate::ids::{DeviceId, now_ms};

const SERVICE_TYPE: &str = "_tandem._udp.local.";
const HOUR_MS: u64 = 3_600_000;

pub type Hint = [u8; 8];

pub fn hint_for(id: &DeviceId, hour: u64) -> Hint {
    let mut input = Vec::with_capacity(40);
    input.extend_from_slice(b"tandem-hint-v1");
    input.extend_from_slice(id.as_bytes());
    input.extend_from_slice(&hour.to_be_bytes());
    let hash = digest::digest(&digest::SHA256, &input);
    let mut hint = [0u8; 8];
    hint.copy_from_slice(&hash.as_ref()[..8]);
    hint
}

pub fn current_hour() -> u64 {
    now_ms() / HOUR_MS
}

/// The hints a device may be announcing right now. The neighbouring hours cover
/// clocks that are a little off and announcements that have not refreshed yet.
pub fn hints_around(id: &DeviceId, hour: u64) -> [Hint; 3] {
    [
        hint_for(id, hour.saturating_sub(1)),
        hint_for(id, hour),
        hint_for(id, hour + 1),
    ]
}

/// Someone announced themselves.
#[derive(Debug, Clone)]
pub struct Sighting {
    pub hint: Hint,
    pub addrs: Vec<SocketAddr>,
    /// The device is showing a pairing code right now.
    pub pairing: bool,
}

/// mDNS only reaches who is on the same local network. On a mobile connection every message keeps the radio awake for seconds and reaches
/// nobody (measured: 80% of the time on battery, with the phone doing nothing). A tunnel and a container bridge have nobody either, and
/// their addresses in an announcement are ones the others then keep dialling. A hotspot and a normal bridge stay in.
fn reaches_nobody(name: &str, ip: IpAddr) -> bool {
    const PREFIXES: [&str; 15] = [
        "rmnet", "ccmni", "pdp_ip", "wwan", "wwp", "ppp", "v4-", "tun", "utun", "ipsec", "wg", "tailscale", "docker", "br-", "veth",
    ];
    let name = name.to_ascii_lowercase();
    PREFIXES.iter().any(|prefix| name.starts_with(prefix)) || crate::net::is_tailnet(ip)
}

/// Keeps a daemon off those networks. They come and go (mobile data, Tailscale), so this is called again now and then.
#[derive(Default)]
pub(crate) struct QuietInterfaces(HashSet<String>);

impl QuietInterfaces {
    pub(crate) fn apply(&mut self, daemon: &ServiceDaemon) {
        let Ok(interfaces) = if_addrs::get_if_addrs() else { return };
        for interface in interfaces {
            if reaches_nobody(&interface.name, interface.ip()) && self.0.insert(interface.name.clone()) {
                let _ = daemon.disable_interface(IfKind::Name(interface.name));
            }
        }
    }
}

/// How often a daemon looks for new addresses: 5 seconds by default. A new network is noticed by the apps at once and devices are dialled
/// on the addresses they had, so this only adds finding a device that has a new address.
pub(crate) const ADDRESS_CHECK_SECS: u32 = 15;

pub struct Discovery {
    daemon: ServiceDaemon,
    quiet: QuietInterfaces,
    /// Goes up each time this device says it is back: a changed announcement is news to the others, the same one is not.
    back: u32,
    registered: Option<String>,
    my_id: DeviceId,
    port: u16,
    pairing: bool,
}

impl Discovery {
    pub fn start(my_id: DeviceId, port: u16, sightings: mpsc::UnboundedSender<Sighting>) -> Result<Self> {
        let daemon = ServiceDaemon::new().map_err(|e| Error::Connection(format!("mdns: {e}")))?;
        let _ = daemon.set_ip_check_interval(ADDRESS_CHECK_SECS);
        let mut quiet = QuietInterfaces::default();
        quiet.apply(&daemon);
        let receiver = daemon
            .browse(SERVICE_TYPE)
            .map_err(|e| Error::Connection(format!("mdns browse: {e}")))?;

        // The receiver is a blocking channel, so it gets its own thread.
        std::thread::Builder::new()
            .name("tandem-mdns".into())
            .spawn(move || {
                while let Ok(event) = receiver.recv() {
                    if let ServiceEvent::ServiceResolved(service) = event {
                        let Some(hint_text) = service.get_property_val_str("h") else { continue };
                        let Ok(raw) = data_encoding::BASE32_NOPAD
                            .decode(hint_text.to_ascii_uppercase().as_bytes())
                        else {
                            continue;
                        };
                        let Ok(hint) = <Hint>::try_from(raw.as_slice()) else { continue };
                        let port = service.get_port();
                        let addrs: Vec<SocketAddr> = service
                            .get_addresses()
                            .iter()
                            .map(|ip| SocketAddr::new(ip.to_ip_addr(), port))
                            .filter(|addr| !is_useless(addr.ip()))
                            .collect();
                        let pairing = service.get_property_val_str("p").is_some();
                        if !addrs.is_empty() && sightings.send(Sighting { hint, addrs, pairing }).is_err() {
                            break;
                        }
                    }
                }
            })
            .map_err(Error::from)?;

        let mut discovery = Discovery { daemon, quiet, back: 0, registered: None, my_id, port, pairing: false };
        discovery.announce()?;
        Ok(discovery)
    }

    /// Says whether this device is showing a pairing code, so a device that is given the short code can find it.
    pub fn set_pairing(&mut self, open: bool) -> Result<()> {
        if self.pairing == open {
            return Ok(());
        }
        self.pairing = open;
        self.announce()
    }

    /// Publishes the current hint. Call again when the hour turns over.
    pub fn announce(&mut self) -> Result<()> {
        if let Some(old) = self.registered.take() {
            let _ = self.daemon.unregister(&old);
        }
        let hint = hint_for(&self.my_id, current_hour());
        let hint_text = data_encoding::BASE32_NOPAD.encode(&hint).to_ascii_lowercase();
        // The instance name is random too, so it reveals nothing stable.
        let instance = format!("t{}{}", &hint_text[..8], if self.pairing { "p" } else { "" });
        let host = format!("{instance}.local.");
        let back = self.back.to_string();
        let mut properties = vec![("h", hint_text.as_str()), ("v", "1"), ("b", back.as_str())];
        if self.pairing {
            properties.push(("p", "1"));
        }
        let info = ServiceInfo::new(SERVICE_TYPE, &instance, &host, "", self.port, &properties[..])
        .map_err(|e| Error::Connection(format!("mdns service: {e}")))?
        .enable_addr_auto();
        let fullname = info.get_fullname().to_string();
        self.daemon
            .register(info)
            .map_err(|e| Error::Connection(format!("mdns register: {e}")))?;
        self.registered = Some(fullname);
        Ok(())
    }

    /// This device woke or has another network. Who is on the same network hears it and dials at once, whatever its back-off says.
    pub fn say_back(&mut self) -> Result<()> {
        self.back = self.back.wrapping_add(1);
        self.announce()
    }

    /// Mobile data or Tailscale may have come up since the last look.
    pub fn stay_quiet(&mut self) {
        self.quiet.apply(&self.daemon);
    }

    pub fn shutdown(&self) {
        let _ = self.daemon.shutdown();
    }
}

fn is_useless(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback() || v4.is_unspecified() || v4.is_link_local(),
        IpAddr::V6(v6) => v6.is_loopback() || v6.is_unspecified() || (v6.segments()[0] & 0xffc0) == 0xfe80,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Uses the real network of the computer, so it only runs on request: `cargo test -p tandem-core back_is_heard -- --ignored`.
    #[test]
    #[ignore]
    fn back_is_heard() {
        let me = DeviceId::from_bytes([7; 16]);
        let (tx, mut heard) = mpsc::unbounded_channel();
        let listener = Discovery::start(DeviceId::from_bytes([8; 16]), 47999, tx).unwrap();
        let (quiet_tx, _unused) = mpsc::unbounded_channel();
        let mut back = Discovery::start(me, 47998, quiet_tx).unwrap();
        let hint = hint_for(&me, current_hour());
        let wait_for_it = |heard: &mut mpsc::UnboundedReceiver<Sighting>| {
            let end = std::time::Instant::now() + std::time::Duration::from_secs(10);
            while std::time::Instant::now() < end {
                match heard.try_recv() {
                    Ok(sighting) if sighting.hint == hint => return true,
                    Ok(_) => {}
                    Err(_) => std::thread::sleep(std::time::Duration::from_millis(50)),
                }
            }
            false
        };
        assert!(wait_for_it(&mut heard), "the first announcement");
        std::thread::sleep(std::time::Duration::from_secs(2));
        while heard.try_recv().is_ok() {}
        back.say_back().unwrap();
        assert!(wait_for_it(&mut heard), "the device that says it is back");
        back.shutdown();
        listener.shutdown();
    }

    #[test]
    fn only_a_local_network_gets_mdns() {
        let lan: IpAddr = "192.168.1.5".parse().unwrap();
        for name in ["rmnet_data0", "v4-rmnet_data2", "pdp_ip0", "wwan0", "tun0", "utun4", "tailscale0", "docker0", "br-1a2b", "veth12", "vEthernet (WSL)", "Tailscale"] {
            assert!(reaches_nobody(name, lan), "{name}");
        }
        for name in ["wlan0", "en0", "ap0", "swlan0", "bridge100", "eth0", "enp3s0", "Wi-Fi", "Ethernet"] {
            assert!(!reaches_nobody(name, lan), "{name}");
        }
        assert!(reaches_nobody("Some VPN", "100.100.1.1".parse().unwrap()));
    }

    #[test]
    fn hints_change_every_hour_and_per_device() {
        let a = DeviceId::from_bytes([1; 16]);
        let b = DeviceId::from_bytes([2; 16]);
        assert_ne!(hint_for(&a, 100), hint_for(&a, 101));
        assert_ne!(hint_for(&a, 100), hint_for(&b, 100));
        assert_eq!(hint_for(&a, 100), hint_for(&a, 100));
    }

    #[test]
    fn neighbouring_hours_are_matched() {
        let id = DeviceId::from_bytes([3; 16]);
        assert!(hints_around(&id, 500).contains(&hint_for(&id, 499)));
        assert!(hints_around(&id, 500).contains(&hint_for(&id, 501)));
    }
}
