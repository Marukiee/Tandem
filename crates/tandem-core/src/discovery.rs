//! Finding devices on the local network with mDNS.
//!
//! The announcement does not carry the device id. It carries a short hint that
//! changes every hour and can only be matched by someone who already knows the id,
//! so a stranger on the same Wi-Fi cannot follow a device around.

use std::net::{IpAddr, SocketAddr};

use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
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

pub struct Discovery {
    daemon: ServiceDaemon,
    registered: Option<String>,
    my_id: DeviceId,
    port: u16,
    pairing: bool,
}

impl Discovery {
    pub fn start(my_id: DeviceId, port: u16, sightings: mpsc::UnboundedSender<Sighting>) -> Result<Self> {
        let daemon = ServiceDaemon::new().map_err(|e| Error::Connection(format!("mdns: {e}")))?;
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

        let mut discovery = Discovery { daemon, registered: None, my_id, port, pairing: false };
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
        let mut properties = vec![("h", hint_text.as_str()), ("v", "1")];
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
