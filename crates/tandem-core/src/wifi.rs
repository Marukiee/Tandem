//! Handing the Wi-Fi a device is on to another device of the circle, the way an iPhone offers a friend its password.
//!
//! A device says which network it is on (`WifiMsg::Network`) when that changes, and to every device that comes up, also
//! over a Bluetooth link: a device without a network is exactly the one that may only be reachable that way. A device that
//! has no network offers its person to take that one over ([`worth_offering`]); a yes there asks (`Ask`), and the password comes
//! back (`Share`) or does not (`Refuse`), without a second question at the sender. The password only travels inside an authenticated connection or a sealed Bluetooth frame, and its
//! `Debug` never shows it, so a message that is logged by mistake does not give it away.
//!
//! A phone reads its password only with Shizuku (the shell may read saved networks); without, it says its network as `locked`.

use std::fmt;

use serde::{Deserialize, Serialize};

use crate::engine::Inner;
use crate::ids::DeviceId;
use crate::proto::Msg;

/// A computer that can read the password of its network and say which network it is on.
pub const CAP_SHARE: &str = "wifi.share";
/// A device that can join a network another one hands it.
pub const CAP_JOIN: &str = "wifi.join";

/// The longest a network name can be (802.11), and a little room for a password: WPA takes 63 characters, or 64 hex digits.
const MAX_SSID: usize = 32;
const MAX_PASSWORD: usize = 64;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WifiSecurity {
    /// No password.
    Open,
    /// WPA or WPA2 with a password, and a network that takes WPA2 and WPA3 alike.
    #[default]
    Wpa2,
    /// WPA3 only.
    Wpa3,
    /// 802.1X: a name and a certificate for each person, nothing that can be handed over.
    Enterprise,
    /// WEP, or something this version does not know. Not shared either.
    #[serde(other)]
    Other,
}

impl WifiSecurity {
    /// Whether a password (or none) is all another device needs to join.
    pub fn shareable(self) -> bool {
        matches!(self, WifiSecurity::Open | WifiSecurity::Wpa2 | WifiSecurity::Wpa3)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiNetwork {
    pub ssid: String,
    #[serde(default)]
    pub security: WifiSecurity,
    /// The network does not announce its name, so the joiner has to look for it by name.
    #[serde(default)]
    pub hidden: bool,
    /// The sender is on it but cannot hand its password over now: a phone without Shizuku. Others then do not offer it.
    #[serde(default)]
    pub locked: bool,
}

/// Whether a device offers to take over `network`, the one another device of the circle is on. Only a device with no network at all
/// (`connected` false), as a network it knows it joins by itself, and one the sender can hand over. Whether the sender can be reached
/// now, and what the person already said no to, is up to the app.
pub fn worth_offering(network: &WifiNetwork, connected: bool, known: &[String]) -> bool {
    !connected && !network.locked && !network.ssid.is_empty() && network.security.shareable() && !known.iter().any(|k| *k == network.ssid)
}

/// A Wi-Fi password. Empty for an open network.
#[derive(Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct WifiPassword(pub String);

impl fmt::Debug for WifiPassword {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("<hidden>")
    }
}

/// Why no password came back.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WifiRefusal {
    /// The person said no, or did not answer.
    #[default]
    Declined,
    /// The computer is not on that network (any more).
    NotOnIt,
    /// An enterprise network, or one that cannot be handed over for another reason.
    CannotShare,
    /// The system did not give the password: the person did not allow it, or it was not saved.
    Unreadable,
    #[serde(other)]
    Other,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum WifiMsg {
    /// The network the sender is on now, or none.
    Network {
        #[serde(default)]
        network: Option<WifiNetwork>,
    },
    /// Asks for the password of `ssid`, the network the other device said it is on. The person there decides.
    Ask { ssid: String },
    /// The password of a network, after a yes, or because the person at the sender pressed Share Wi-Fi.
    Share {
        network: WifiNetwork,
        #[serde(default)]
        password: WifiPassword,
    },
    Refuse {
        ssid: String,
        #[serde(default)]
        why: WifiRefusal,
    },
}

impl WifiMsg {
    /// A name or a password longer than Wi-Fi allows is a mistake or an abuse, and goes no further.
    pub fn sane(&self) -> bool {
        let name = |ssid: &str| !ssid.is_empty() && ssid.len() <= MAX_SSID;
        match self {
            WifiMsg::Network { network } => network.as_ref().is_none_or(|n| name(&n.ssid)),
            WifiMsg::Ask { ssid } | WifiMsg::Refuse { ssid, .. } => name(ssid),
            WifiMsg::Share { network, password } => name(&network.ssid) && password.0.len() <= MAX_PASSWORD,
        }
    }
}

impl Inner {
    /// Whether this device is one that says which network it is on: a computer whose app switched it on.
    fn wifi_announces(&self) -> bool {
        self.caps().iter().any(|c| c == CAP_SHARE)
    }

    /// Whether `peer` can do anything with the network of this one. A device whose hello this run has not seen yet (it is
    /// only reachable over Bluetooth since a restart) gets it too: an older version drops what it does not know.
    fn wifi_takes(&self, peer: &DeviceId) -> bool {
        let peers = self.peers.lock().unwrap();
        match peers.get(peer).and_then(|p| p.hello.as_ref()) {
            Some(hello) => hello.caps.iter().any(|c| c == CAP_JOIN),
            None => peers.contains_key(peer),
        }
    }

    fn wifi_now(&self) -> Msg {
        Msg::Wifi(WifiMsg::Network { network: self.wifi.lock().unwrap().clone() })
    }

    /// A device just connected: it learns the network of this one, also when there is none, because it may still remember an old one.
    pub(crate) async fn wifi_on_connected(&self, peer: DeviceId) {
        if self.wifi_announces() && self.wifi_takes(&peer) {
            let _ = self.send_to(&peer, self.wifi_now()).await;
        }
    }

    /// A Bluetooth link to `peer` just came up.
    pub(crate) fn wifi_on_link(&self, peer: DeviceId) {
        if self.wifi_announces() && self.wifi_takes(&peer) {
            self.ble_queue(&peer, self.wifi_now());
        }
    }

    /// The app says which network this device is on now. Everybody who can be reached hears of a change.
    pub(crate) async fn set_wifi(&self, network: Option<WifiNetwork>) {
        {
            let mut current = self.wifi.lock().unwrap();
            if *current == network {
                return;
            }
            *current = network;
        }
        if !self.wifi_announces() {
            return;
        }
        let mut reached = Vec::new();
        for id in self.connected_ids() {
            if self.wifi_takes(&id) && self.send_to(&id, self.wifi_now()).await.is_ok() {
                reached.push(id);
            }
        }
        for id in self.ble_linked_ids() {
            if !reached.contains(&id) && self.wifi_takes(&id) {
                self.ble_queue(&id, self.wifi_now());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::{decode_msg, encode};

    fn round_trip(msg: WifiMsg) -> WifiMsg {
        match decode_msg(&encode(&Msg::Wifi(msg)).unwrap()).unwrap() {
            Msg::Wifi(back) => back,
            other => panic!("wrong message: {other:?}"),
        }
    }

    fn home() -> WifiNetwork {
        WifiNetwork { ssid: "Home".into(), security: WifiSecurity::Wpa3, hidden: true, locked: false }
    }

    #[test]
    fn only_a_device_without_a_network_is_offered_one_it_does_not_know_and_can_have() {
        let home = home();
        assert!(worth_offering(&home, false, &[]));
        assert!(!worth_offering(&home, true, &[]), "it has a network");
        assert!(!worth_offering(&home, false, &["Home".into()]), "it joins a known one by itself");
        assert!(!worth_offering(&WifiNetwork { locked: true, ..home.clone() }, false, &[]), "a phone without Shizuku");
        assert!(!worth_offering(&WifiNetwork { security: WifiSecurity::Enterprise, ..home }, false, &[]));
    }

    #[test]
    fn every_message_survives_the_trip() {
        for msg in [
            WifiMsg::Network { network: Some(home()) },
            WifiMsg::Network { network: None },
            WifiMsg::Ask { ssid: "Home".into() },
            WifiMsg::Share { network: home(), password: WifiPassword("correct horse".into()) },
            WifiMsg::Refuse { ssid: "Home".into(), why: WifiRefusal::CannotShare },
        ] {
            assert_eq!(round_trip(msg.clone()), msg);
        }
    }

    #[test]
    fn the_password_never_shows_in_a_log() {
        let msg = Msg::Wifi(WifiMsg::Share { network: home(), password: WifiPassword("correct horse".into()) });
        let printed = format!("{msg:?}");
        assert!(!printed.contains("correct horse"), "{printed}");
        assert!(printed.contains("Home"));
    }

    #[test]
    fn a_security_from_the_future_is_not_shared_and_missing_fields_have_defaults() {
        let value = ciborium::Value::Map(vec![(
            ciborium::Value::Text("Wifi".into()),
            ciborium::Value::Map(vec![(
                ciborium::Value::Text("Network".into()),
                ciborium::Value::Map(vec![(
                    ciborium::Value::Text("network".into()),
                    ciborium::Value::Map(vec![
                        (ciborium::Value::Text("ssid".into()), ciborium::Value::Text("Cafe".into())),
                        (ciborium::Value::Text("security".into()), ciborium::Value::Text("quantum".into())),
                    ]),
                )]),
            )]),
        )]);
        let mut bytes = Vec::new();
        ciborium::into_writer(&value, &mut bytes).unwrap();
        let Msg::Wifi(WifiMsg::Network { network: Some(network) }) = decode_msg(&bytes).unwrap() else { panic!("not a network") };
        assert_eq!(network.security, WifiSecurity::Other);
        assert!(!network.security.shareable());
        assert!(!network.hidden);
        assert!(!network.locked, "an older sender can always hand it over");

        let refuse = ciborium::Value::Map(vec![(
            ciborium::Value::Text("Wifi".into()),
            ciborium::Value::Map(vec![(
                ciborium::Value::Text("Refuse".into()),
                ciborium::Value::Map(vec![(ciborium::Value::Text("ssid".into()), ciborium::Value::Text("Cafe".into()))]),
            )]),
        )]);
        let mut bytes = Vec::new();
        ciborium::into_writer(&refuse, &mut bytes).unwrap();
        assert!(matches!(decode_msg(&bytes).unwrap(), Msg::Wifi(WifiMsg::Refuse { why: WifiRefusal::Declined, .. })));
    }

    #[test]
    fn only_what_wifi_allows_is_sane() {
        assert!(WifiMsg::Ask { ssid: "Home".into() }.sane());
        assert!(WifiMsg::Network { network: None }.sane());
        assert!(!WifiMsg::Ask { ssid: String::new() }.sane());
        assert!(!WifiMsg::Ask { ssid: "x".repeat(33) }.sane());
        let long = WifiMsg::Share { network: home(), password: WifiPassword("p".repeat(65)) };
        assert!(!long.sane());
        let hex = WifiMsg::Share { network: home(), password: WifiPassword("a".repeat(64)) };
        assert!(hex.sane());
    }

    #[test]
    fn enterprise_and_wep_cannot_be_handed_over() {
        assert!(WifiSecurity::Open.shareable());
        assert!(WifiSecurity::Wpa2.shareable());
        assert!(WifiSecurity::Wpa3.shareable());
        assert!(!WifiSecurity::Enterprise.shareable());
        assert!(!WifiSecurity::Other.shareable());
    }
}
