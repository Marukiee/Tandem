//! Sockets, addresses and how devices find each other's addresses.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use crate::identity::Identity;
use crate::tls::{self, Tuning};

pub const DEFAULT_PORT: u16 = 47820;

/// How an address was learned, which decides how eagerly it is tried.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Route {
    Lan,
    Tailnet,
    Other,
}

/// Whether it is time to ask the app to turn Tailscale on for a device that does not answer: it has failed to be reached a few times
/// in a row, one of its known addresses is a Tailscale one, this device has none of its own, and it was not asked a minute ago.
pub fn needs_tailscale(fails: u32, addrs: &[KnownAddr], has_own_tailnet_address: bool, since_last_ask: Option<std::time::Duration>) -> bool {
    fails >= 2
        && !has_own_tailnet_address
        && addrs.iter().any(|a| a.route == Route::Tailnet)
        && since_last_ask.is_none_or(|t| t >= std::time::Duration::from_secs(90))
}

/// True for an address in Tailscale's range: 100.64.0.0/10 or fd7a:115c:a1e0::/48.
pub fn is_tailnet(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            o[0] == 100 && (o[1] & 0b1100_0000) == 0b0100_0000
        }
        IpAddr::V6(v6) => {
            let s = v6.segments();
            s[0] == 0xfd7a && s[1] == 0x115c && s[2] == 0xa1e0
        }
    }
}

pub fn route_of(ip: IpAddr) -> Route {
    if is_tailnet(ip) {
        Route::Tailnet
    } else if is_lan(ip) {
        Route::Lan
    } else {
        Route::Other
    }
}

fn is_lan(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_private() || v4.is_link_local(),
        IpAddr::V6(v6) => (v6.segments()[0] & 0xfe00) == 0xfc00 || (v6.segments()[0] & 0xffc0) == 0xfe80,
    }
}

fn usable(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => !v4.is_loopback() && !v4.is_unspecified() && !v4.is_link_local() && !v4.is_broadcast(),
        // Link-local IPv6 needs a scope id and is not worth the trouble.
        IpAddr::V6(v6) => !v6.is_loopback() && !v6.is_unspecified() && (v6.segments()[0] & 0xffc0) != 0xfe80,
    }
}

/// The addresses other devices could reach this one on, as `ip:port` strings.
pub fn local_candidates(port: u16, include_loopback: bool) -> Vec<String> {
    let mut found: Vec<SocketAddr> = Vec::new();
    if let Ok(interfaces) = if_addrs::get_if_addrs() {
        for interface in interfaces {
            let ip = interface.ip();
            if usable(ip) || (include_loopback && ip.is_loopback() && ip.is_ipv4()) {
                found.push(SocketAddr::new(ip, port));
            }
        }
    }
    // Tailscale and private LAN addresses first: they are the ones that work.
    found.sort_by_key(|addr| match route_of(addr.ip()) {
        Route::Lan => 0,
        Route::Tailnet => 1,
        Route::Other => 2,
    });
    found.dedup();
    found.into_iter().map(|a| a.to_string()).take(8).collect()
}

/// The address a device is reachable at, and how good it has been so far.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct KnownAddr {
    pub addr: String,
    pub route: Route,
    /// Unix ms of the last successful connection over this address, 0 if never.
    pub last_ok: u64,
    /// Unix ms when this address was last seen or advertised.
    pub seen: u64,
}

impl KnownAddr {
    pub fn socket(&self) -> Option<SocketAddr> {
        self.addr.parse().ok()
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct AddrBook {
    addrs: Vec<KnownAddr>,
}

const MAX_ADDRS: usize = 12;

impl AddrBook {
    pub fn learn(&mut self, addr: SocketAddr, now: u64) {
        let text = addr.to_string();
        if let Some(existing) = self.addrs.iter_mut().find(|a| a.addr == text) {
            existing.seen = now;
        } else {
            self.addrs.push(KnownAddr { addr: text, route: route_of(addr.ip()), last_ok: 0, seen: now });
        }
        self.trim();
    }

    pub fn succeeded(&mut self, addr: SocketAddr, now: u64) {
        self.learn(addr, now);
        let text = addr.to_string();
        if let Some(existing) = self.addrs.iter_mut().find(|a| a.addr == text) {
            existing.last_ok = now;
        }
    }

    fn trim(&mut self) {
        if self.addrs.len() > MAX_ADDRS {
            self.addrs.sort_by_key(|a| std::cmp::Reverse(a.last_ok.max(a.seen)));
            self.addrs.truncate(MAX_ADDRS);
        }
    }

    /// Addresses in the order they should be tried: what worked most recently, then
    /// LAN before Tailscale before anything else, newest first.
    pub fn ordered(&self) -> Vec<KnownAddr> {
        let mut all = self.addrs.clone();
        all.sort_by_key(|a| {
            let route = match a.route {
                Route::Lan => 0u8,
                Route::Tailnet => 1,
                Route::Other => 2,
            };
            (std::cmp::Reverse(a.last_ok), route, std::cmp::Reverse(a.seen))
        });
        all
    }

    pub fn is_empty(&self) -> bool {
        self.addrs.is_empty()
    }
}

/// One UDP socket serves both directions, so an outgoing connection and an
/// incoming one share a port and NATs see a single flow.
pub fn make_endpoint(identity: &Identity, port: u16, tuning: Tuning) -> Result<quinn::Endpoint> {
    let server = tls::server_config(identity, tuning)?;
    let socket = bind_socket(port)?;
    let runtime = quinn::default_runtime().ok_or_else(|| Error::Connection("no async runtime".into()))?;
    let mut endpoint =
        quinn::Endpoint::new(quinn::EndpointConfig::default(), Some(server), socket, runtime)
            .map_err(|e| Error::connection(e))?;
    // Clients built per connection carry their own pinned verifier, so the endpoint
    // default is never used.
    let _ = &mut endpoint;
    Ok(endpoint)
}

fn bind_socket(port: u16) -> Result<std::net::UdpSocket> {
    use socket2::{Domain, Protocol, Socket, Type};

    // Dual stack: one socket for IPv4 and IPv6. Falls back to IPv4 alone where the
    // system has no IPv6.
    let attempt = |domain: Domain, addr: SocketAddr| -> std::io::Result<std::net::UdpSocket> {
        let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
        if domain == Domain::IPV6 {
            socket.set_only_v6(false)?;
        }
        // Big buffers so a fast Wi-Fi burst is not dropped before we read it.
        let _ = socket.set_recv_buffer_size(4 * 1024 * 1024);
        let _ = socket.set_send_buffer_size(4 * 1024 * 1024);
        socket.bind(&addr.into())?;
        Ok(socket.into())
    };

    let v6 = SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), port);
    let v4 = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), port);
    attempt(Domain::IPV6, v6)
        .or_else(|_| attempt(Domain::IPV4, v4))
        .or_else(|_| {
            if port != 0 {
                // The preferred port is taken (another instance): let the system pick.
                attempt(Domain::IPV6, SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0))
                    .or_else(|_| attempt(Domain::IPV4, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)))
            } else {
                Err(std::io::Error::other("could not bind a UDP socket"))
            }
        })
        .map_err(Error::from)
}

/// Dials one address and returns the connection once TLS is done. `alpn` chooses
/// between normal traffic and pairing.
pub async fn dial(
    endpoint: &quinn::Endpoint,
    identity: &Identity,
    expected: Option<[u8; 32]>,
    alpn: &[u8],
    addr: SocketAddr,
    tuning: Tuning,
    timeout: std::time::Duration,
) -> Result<quinn::Connection> {
    let config = tls::client_config(identity, expected, alpn, tuning)?;
    // An IPv4 target has to be mapped when the socket is IPv6 dual stack.
    let target = match (endpoint.local_addr(), addr) {
        (Ok(local), SocketAddr::V4(v4)) if local.is_ipv6() => {
            SocketAddr::new(IpAddr::V6(v4.ip().to_ipv6_mapped()), v4.port())
        }
        _ => addr,
    };
    let connecting = endpoint
        .connect_with(config, target, tls::SERVER_NAME)
        .map_err(Error::connection)?;
    match tokio::time::timeout(timeout, connecting).await {
        Ok(Ok(connection)) => Ok(connection),
        Ok(Err(e)) => Err(Error::connection(e)),
        Err(_) => Err(Error::Connection(format!("timed out reaching {addr}"))),
    }
}

/// The endpoint's actual port, after the system has picked one for port 0.
pub fn local_port(endpoint: &quinn::Endpoint) -> u16 {
    endpoint.local_addr().map(|a| a.port()).unwrap_or(0)
}

pub type SharedEndpoint = Arc<quinn::Endpoint>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tailscale_is_asked_for_only_when_the_device_is_out_of_reach_and_known_by_a_tailnet_address() {
        let lan = KnownAddr { addr: "192.168.1.5:47820".into(), route: Route::Lan, last_ok: 0, seen: 0 };
        let tail = KnownAddr { addr: "100.100.1.1:47820".into(), route: Route::Tailnet, last_ok: 0, seen: 0 };
        let minute = std::time::Duration::from_secs(60);
        // Not yet, it may only be slow.
        assert!(!needs_tailscale(1, &[lan.clone(), tail.clone()], false, None));
        // A device on the same network that answers never gets here; one that fails twice and has a tailnet address does.
        assert!(needs_tailscale(2, &[lan.clone(), tail.clone()], false, None));
        // Nothing to turn on when this device already has a tailnet address, or the other one has none.
        assert!(!needs_tailscale(5, &[lan.clone(), tail.clone()], true, None));
        assert!(!needs_tailscale(5, &[lan.clone()], false, None));
        // Not again within a minute and a half.
        assert!(!needs_tailscale(5, &[tail.clone()], false, Some(minute)));
        assert!(needs_tailscale(5, &[tail], false, Some(minute * 2)));
    }

    #[test]
    fn tailscale_range_is_recognised() {
        assert!(is_tailnet("100.64.0.1".parse().unwrap()));
        assert!(is_tailnet("100.101.102.103".parse().unwrap()));
        assert!(is_tailnet("100.127.255.254".parse().unwrap()));
        assert!(!is_tailnet("100.128.0.1".parse().unwrap()));
        assert!(!is_tailnet("100.63.255.255".parse().unwrap()));
        assert!(!is_tailnet("192.168.1.5".parse().unwrap()));
        assert!(is_tailnet("fd7a:115c:a1e0::1".parse().unwrap()));
    }

    #[test]
    fn routes_are_classified() {
        assert_eq!(route_of("192.168.1.5".parse().unwrap()), Route::Lan);
        assert_eq!(route_of("10.0.0.2".parse().unwrap()), Route::Lan);
        assert_eq!(route_of("100.100.1.1".parse().unwrap()), Route::Tailnet);
        assert_eq!(route_of("8.8.8.8".parse().unwrap()), Route::Other);
    }

    #[test]
    fn address_book_prefers_what_worked() {
        let mut book = AddrBook::default();
        let lan: SocketAddr = "192.168.1.5:47820".parse().unwrap();
        let tailnet: SocketAddr = "100.100.1.1:47820".parse().unwrap();
        book.learn(lan, 10);
        book.learn(tailnet, 20);
        assert_eq!(book.ordered()[0].addr, lan.to_string(), "LAN first when nothing has worked yet");
        book.succeeded(tailnet, 30);
        assert_eq!(book.ordered()[0].addr, tailnet.to_string(), "the one that worked wins");
    }

    #[test]
    fn address_book_stays_small() {
        let mut book = AddrBook::default();
        for i in 0..40u8 {
            book.learn(format!("10.0.0.{i}:1").parse().unwrap(), i as u64);
        }
        assert!(book.ordered().len() <= MAX_ADDRS);
    }
}
