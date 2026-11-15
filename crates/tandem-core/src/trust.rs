//! What each device of the circle may do here (P6, R-I6). A permission per device, checked by the core where the request comes in, so
//! the four apps have it without each doing it. Everything is allowed until a person switches something off, so nothing changes for
//! anyone before an app sets it. Where a switch per device already existed (the clipboard, files without asking, messages, the file
//! policy) that switch is the permission, so there is never a second one to keep in step.
//!
//! Each device puts its whole table in its status (`Status::trust`), so every device can draw who may do what across the circle. A
//! device that refuses tells the one that asked with `Msg::Refused`, but only one that has [`CAP`]: an older one would not understand.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tracing::info;

use crate::engine::Inner;
use crate::error::Result;
use crate::events::Event;
use crate::ids::DeviceId;
use crate::proto::{Msg, Status};
use crate::store::DeviceSettings;

/// This device checks what others may do here, says so when it refuses, and puts its table in its status.
pub const CAP: &str = "trust";

/// The only reason a request is refused for now. Apps translate the codes in [`REASONS`]; one they do not know is a plain no.
pub const REASON_NOT_ALLOWED: &str = "not allowed";
pub const REASONS: &[&str] = &[REASON_NOT_ALLOWED];

/// A refusal of the same kind is said once in this time: a pointer that is refused sends dozens of moves a second.
const SAY_AGAIN_AFTER: Duration = Duration::from_secs(10);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Permission {
    /// Files it sends are taken without asking. Off, they are asked about (`DeviceSettings::auto_accept`).
    FilesWithoutAsking,
    /// Its clipboard comes in here and this one goes to it (`DeviceSettings::clipboard`).
    Clipboard,
    /// Reading and sending the text messages of this phone (`DeviceSettings::messages`).
    Messages,
    /// Looking at the files this device offers (`FilePolicy::enabled`).
    BrowseFiles,
    /// Removing files of this device (`FilePolicy::delete`).
    DeleteFiles,
    /// The notifications of this device go to it.
    SeeNotifications,
    /// Moving the pointer and typing here: the shared mouse, remote input, and control of a screen it looks at.
    Control,
    Unlock,
    Lock,
    /// Asking for the password of the Wi-Fi this device is on.
    WifiPassword,
    /// Starting, answering and ending calls of this phone.
    Calls,
    /// Restarting Tandem on this device.
    Restart,
}

impl Permission {
    pub const ALL: [Permission; 12] = [
        Permission::FilesWithoutAsking,
        Permission::Clipboard,
        Permission::Messages,
        Permission::BrowseFiles,
        Permission::DeleteFiles,
        Permission::SeeNotifications,
        Permission::Control,
        Permission::Unlock,
        Permission::Lock,
        Permission::WifiPassword,
        Permission::Calls,
        Permission::Restart,
    ];

    /// The name on the wire and in a table. Fixed: other devices read it.
    pub fn code(self) -> &'static str {
        match self {
            Permission::FilesWithoutAsking => "files_without_asking",
            Permission::Clipboard => "clipboard",
            Permission::Messages => "messages",
            Permission::BrowseFiles => "browse_files",
            Permission::DeleteFiles => "delete_files",
            Permission::SeeNotifications => "see_notifications",
            Permission::Control => "control",
            Permission::Unlock => "unlock",
            Permission::Lock => "lock",
            Permission::WifiPassword => "wifi_password",
            Permission::Calls => "calls",
            Permission::Restart => "restart",
        }
    }

    /// None for a name from a newer version.
    pub fn from_code(code: &str) -> Option<Permission> {
        Permission::ALL.into_iter().find(|p| p.code() == code)
    }
}

/// A few ready choices. The level is never stored: it is read from the permissions, so the two cannot disagree.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TrustLevel {
    /// One of your own devices: everything, as before P6.
    Own,
    /// No removing files, no unlocking, no control, no Wi-Fi password.
    Limited,
    /// It may only send things here, and they are asked about.
    SendOnly,
    /// Something else than the three above.
    Custom,
}

impl TrustLevel {
    /// What the level switches off. None for `Custom`, which is not a choice of its own.
    pub fn denies(self) -> Option<&'static [Permission]> {
        match self {
            TrustLevel::Own => Some(&[]),
            TrustLevel::Limited => Some(&[Permission::DeleteFiles, Permission::Unlock, Permission::Control, Permission::WifiPassword]),
            TrustLevel::SendOnly => Some(&Permission::ALL),
            TrustLevel::Custom => None,
        }
    }

    pub fn of(denied: &[Permission]) -> TrustLevel {
        for level in [TrustLevel::Own, TrustLevel::Limited, TrustLevel::SendOnly] {
            let wanted = level.denies().unwrap_or_default();
            if wanted.len() == denied.len() && wanted.iter().all(|p| denied.contains(p)) {
                return level;
            }
        }
        TrustLevel::Custom
    }
}

/// One line of the table in the status: what this device does not let `device` do. A device with nothing switched off has no line.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TrustRow {
    pub device: DeviceId,
    /// Codes of [`Permission`]. Strings, so a newer one is skipped by an older reader instead of breaking the status.
    #[serde(default)]
    pub denied: Vec<String>,
}

/// What `from` lets `to` do, as far as this device knows. `denied` is None when `from` is of a version without permissions.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TrustEdge {
    pub from: DeviceId,
    pub to: DeviceId,
    pub denied: Option<Vec<Permission>>,
}

/// The permission a message from another device needs, for the ones that make this device do something. The rest needs none here: a
/// clipboard that comes in is checked where it lands, files and offers by their own switches.
pub fn needed_for(msg: &Msg) -> Option<Permission> {
    use crate::pointer_share::PointerShareMsg as P;
    match msg {
        // Only what hands the pointer over or drops something: `Leave` and `Pong` come back from the computer that has the pointer.
        Msg::Input(_) | Msg::MediaInput { .. } | Msg::PointerShare(P::Enter { .. } | P::Carry { .. }) => Some(Permission::Control),
        Msg::Unlock {} => Some(Permission::Unlock),
        Msg::Lock {} => Some(Permission::Lock),
        Msg::Wifi(crate::wifi::WifiMsg::Ask { .. }) => Some(Permission::WifiPassword),
        Msg::Dial { .. } | Msg::CallAction(_) => Some(Permission::Calls),
        Msg::OpenSettings { page } if page == "restart" => Some(Permission::Restart),
        _ => None,
    }
}

/// The permission a message this device sends needs from the one it goes to.
pub fn needed_to_send(msg: &Msg) -> Option<Permission> {
    match msg {
        Msg::Notification(_) | Msg::NotificationRemoved { .. } => Some(Permission::SeeNotifications),
        _ => None,
    }
}

fn get(settings: &DeviceSettings, policy: &crate::files::FilePolicy, permission: Permission) -> bool {
    match permission {
        Permission::FilesWithoutAsking => settings.auto_accept,
        Permission::Clipboard => settings.clipboard,
        Permission::Messages => settings.messages,
        Permission::BrowseFiles => policy.enabled,
        Permission::DeleteFiles => policy.delete,
        Permission::SeeNotifications => settings.see_notifications,
        Permission::Control => settings.control,
        Permission::Unlock => settings.unlock,
        Permission::Lock => settings.lock,
        Permission::WifiPassword => settings.wifi_password,
        Permission::Calls => settings.calls,
        Permission::Restart => settings.restart,
    }
}

/// Puts `on` in the switch behind `permission`. Says whether that switch is one of the file policy.
fn put(settings: &mut DeviceSettings, policy: &mut crate::files::FilePolicy, permission: Permission, on: bool) -> bool {
    let slot = match permission {
        Permission::FilesWithoutAsking => &mut settings.auto_accept,
        Permission::Clipboard => &mut settings.clipboard,
        Permission::Messages => &mut settings.messages,
        Permission::BrowseFiles => &mut policy.enabled,
        Permission::DeleteFiles => &mut policy.delete,
        Permission::SeeNotifications => &mut settings.see_notifications,
        Permission::Control => &mut settings.control,
        Permission::Unlock => &mut settings.unlock,
        Permission::Lock => &mut settings.lock,
        Permission::WifiPassword => &mut settings.wifi_password,
        Permission::Calls => &mut settings.calls,
        Permission::Restart => &mut settings.restart,
    };
    *slot = on;
    matches!(permission, Permission::BrowseFiles | Permission::DeleteFiles)
}

impl Inner {
    pub(crate) fn allowed(&self, peer: &DeviceId, permission: Permission) -> bool {
        let settings = self.settings.read().unwrap().for_device(peer);
        get(&settings, &self.file_service.policy(peer), permission)
    }

    pub(crate) fn denied(&self, peer: &DeviceId) -> Vec<Permission> {
        let settings = self.settings.read().unwrap().for_device(peer);
        let policy = self.file_service.policy(peer);
        Permission::ALL.into_iter().filter(|p| !get(&settings, &policy, *p)).collect()
    }

    /// Switches the permissions in `changes` for `peer`, each in the switch it lives in, and lets the others know.
    pub(crate) fn set_permissions(&self, peer: &DeviceId, changes: &[(Permission, bool)]) -> Result<()> {
        let mut settings = self.settings.read().unwrap().for_device(peer);
        let mut policy = self.file_service.policy(peer);
        let mut files = false;
        for (permission, on) in changes {
            files |= put(&mut settings, &mut policy, *permission, *on);
        }
        if files && policy != self.file_service.policy(peer) {
            self.file_service.set_policy(peer, policy)?;
        }
        // Also says the table changed.
        self.set_device_settings(peer, settings)
    }

    /// The table of this device: a line for each other device of the circle that has something switched off.
    pub(crate) fn trust_table(&self) -> Vec<TrustRow> {
        let members = self.circle.read().unwrap().members();
        members
            .into_iter()
            .filter(|m| m.id != self.my_id)
            .filter_map(|m| {
                let denied = self.denied(&m.id);
                (!denied.is_empty()).then(|| TrustRow { device: m.id, denied: denied.into_iter().map(|p| p.code().to_string()).collect() })
            })
            .collect()
    }

    /// Puts the table in what this device reports and sends it to the devices that are connected.
    pub(crate) async fn publish_trust(&self) {
        let change = Status { trust: Some(self.trust_table()), ..Status::default() };
        let same = self.my_status.lock().unwrap().trust == change.trust;
        if same {
            return;
        }
        self.my_status.lock().unwrap().merge(&change);
        self.broadcast(Msg::Status(change)).await;
    }

    /// Waits for a change of a permission and sends the new table on, for as long as the engine runs. A change can come from a call
    /// that is not async (`set_device_settings`), so it only rings here.
    pub(crate) async fn keep_trust_published(self: Arc<Self>) {
        loop {
            tokio::select! {
                _ = self.cancel.cancelled() => return,
                _ = self.trust_changed.notified() => self.publish_trust().await,
            }
        }
    }

    /// Something `peer` asked was refused: a line in the log, and a word to it when it can understand one.
    pub(crate) async fn refuse(&self, peer: DeviceId, permission: Permission) {
        let say = {
            let mut said = self.refused_said.lock().unwrap();
            let now = Instant::now();
            let fresh = said.get(&(peer, permission)).is_none_or(|at| now.duration_since(*at) >= SAY_AGAIN_AFTER);
            if fresh {
                said.insert((peer, permission), now);
            }
            fresh
        };
        if !say {
            return;
        }
        info!(%peer, permission = permission.code(), "refused: that device may not do this here");
        if self.peer_has_cap(&peer, CAP) {
            let msg = Msg::Refused { action: permission.code().to_string(), reason: REASON_NOT_ALLOWED.to_string() };
            let _ = self.send_to(&peer, msg).await;
        }
    }

    pub(crate) fn on_refused(&self, from: DeviceId, action: &str, reason: &str) {
        // A permission from a newer version cannot be named to the app.
        let Some(permission) = Permission::from_code(action) else { return };
        let reason = REASONS.iter().find(|r| **r == reason).copied().unwrap_or(REASON_NOT_ALLOWED);
        self.emit(Event::Refused { from, permission, reason: reason.to_string() });
    }

    /// Who lets whom do what across the circle: this device from its own settings, the others from the table in their status.
    pub(crate) fn trust_web(&self) -> Vec<TrustEdge> {
        let members: Vec<DeviceId> = self.circle.read().unwrap().members().into_iter().map(|m| m.id).collect();
        let tables: HashMap<DeviceId, Option<Vec<TrustRow>>> =
            self.peers.lock().unwrap().iter().map(|(id, peer)| (*id, peer.status.trust.clone())).collect();
        let mut web = Vec::new();
        for from in &members {
            for to in members.iter().filter(|to| *to != from) {
                let denied = if *from == self.my_id {
                    Some(self.denied(to))
                } else {
                    tables.get(from).cloned().flatten().map(|rows| {
                        rows.iter()
                            .find(|row| row.device == *to)
                            .map(|row| row.denied.iter().filter_map(|code| Permission::from_code(code)).collect())
                            .unwrap_or_default()
                    })
                };
                web.push(TrustEdge { from: *from, to: *to, denied });
            }
        }
        web
    }
}

impl crate::engine::Engine {
    /// Whether `peer` may do `permission` on this device.
    pub fn allowed(&self, peer: &DeviceId, permission: Permission) -> bool {
        self.inner.allowed(peer, permission)
    }

    /// What `peer` may not do on this device. Empty, the default, is everything allowed.
    pub fn denied_permissions(&self, peer: &DeviceId) -> Vec<Permission> {
        self.inner.denied(peer)
    }

    pub fn set_permission(&self, peer: &DeviceId, permission: Permission, allowed: bool) -> Result<()> {
        self.inner.set_permissions(peer, &[(permission, allowed)])
    }

    pub fn trust_level(&self, peer: &DeviceId) -> TrustLevel {
        TrustLevel::of(&self.inner.denied(peer))
    }

    /// Sets the permissions of `peer` to those of `level`. `Custom` changes nothing.
    pub fn set_trust_level(&self, peer: &DeviceId, level: TrustLevel) -> Result<()> {
        let Some(denies) = level.denies() else { return Ok(()) };
        let changes: Vec<_> = Permission::ALL.into_iter().map(|p| (p, !denies.contains(&p))).collect();
        self.inner.set_permissions(peer, &changes)
    }

    pub fn trust_web(&self) -> Vec<TrustEdge> {
        self.inner.trust_web()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::files::FilePolicy;

    #[test]
    fn every_permission_has_its_own_code_and_comes_back_from_it() {
        let codes: std::collections::HashSet<_> = Permission::ALL.iter().map(|p| p.code()).collect();
        assert_eq!(codes.len(), Permission::ALL.len());
        for p in Permission::ALL {
            assert_eq!(Permission::from_code(p.code()), Some(p));
        }
        assert_eq!(Permission::from_code("teleport"), None);
    }

    #[test]
    fn by_default_everything_is_allowed_as_before() {
        let (settings, policy) = (DeviceSettings::default(), FilePolicy::default());
        for p in Permission::ALL {
            assert!(get(&settings, &policy, p), "{} must be allowed by default", p.code());
        }
        assert_eq!(TrustLevel::of(&[]), TrustLevel::Own);
    }

    #[test]
    fn each_permission_switches_its_own_switch_and_no_other() {
        for p in Permission::ALL {
            let (mut settings, mut policy) = (DeviceSettings::default(), FilePolicy::default());
            let in_policy = put(&mut settings, &mut policy, p, false);
            assert_eq!(in_policy, matches!(p, Permission::BrowseFiles | Permission::DeleteFiles));
            for other in Permission::ALL {
                assert_eq!(get(&settings, &policy, other), other != p, "switching off {} touched {}", p.code(), other.code());
            }
        }
    }

    #[test]
    fn existing_switches_are_the_permissions() {
        let settings = DeviceSettings { clipboard: false, auto_accept: false, messages: false, ..DeviceSettings::default() };
        let policy = FilePolicy { enabled: false, delete: false, ..FilePolicy::default() };
        for p in [Permission::Clipboard, Permission::FilesWithoutAsking, Permission::Messages, Permission::BrowseFiles, Permission::DeleteFiles] {
            assert!(!get(&settings, &policy, p));
        }
    }

    #[test]
    fn levels_are_read_from_the_permissions() {
        for level in [TrustLevel::Own, TrustLevel::Limited, TrustLevel::SendOnly] {
            assert_eq!(TrustLevel::of(level.denies().unwrap()), level);
        }
        assert_eq!(TrustLevel::of(&[Permission::Unlock]), TrustLevel::Custom);
        assert_eq!(TrustLevel::Custom.denies(), None);
    }

    #[test]
    fn requests_need_their_permission_and_answers_need_none() {
        use crate::pointer_share::{Edge, PointerShareMsg as P};
        let cases: Vec<(Msg, Option<Permission>)> = vec![
            (Msg::Unlock {}, Some(Permission::Unlock)),
            (Msg::Lock {}, Some(Permission::Lock)),
            (Msg::Dial { number: "112".into() }, Some(Permission::Calls)),
            (Msg::OpenSettings { page: "restart".into() }, Some(Permission::Restart)),
            (Msg::OpenSettings { page: "accessibility".into() }, None),
            (Msg::Wifi(crate::wifi::WifiMsg::Ask { ssid: "Home".into() }), Some(Permission::WifiPassword)),
            (Msg::Input(crate::proto::InputMsg::Pointer { dx: 1, dy: 1 }), Some(Permission::Control)),
            (Msg::PointerShare(P::Enter { edge: Edge::Left, along: 0.5 }), Some(Permission::Control)),
            (Msg::PointerShare(P::Leave { along: 0.5 }), None),
            (Msg::PointerShare(P::Pong), None),
            (Msg::Ping { nonce: 1 }, None),
        ];
        for (msg, wanted) in cases {
            assert_eq!(needed_for(&msg), wanted, "{msg:?}");
        }
        assert_eq!(needed_to_send(&Msg::NotificationRemoved { key: "k".into() }), Some(Permission::SeeNotifications));
        assert_eq!(needed_to_send(&Msg::Unlock {}), None);
    }
}
