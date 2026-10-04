//! What a person has decided about sharing a screen or a camera with another device, kept like the file
//! policy: per device, with a default for the devices that have no choices of their own.

use std::collections::HashMap;
use std::sync::RwLock;

use serde::{Deserialize, Serialize};

use crate::error::Result;
use crate::ids::DeviceId;
use crate::live::wire::MediaKind;
use crate::store::Store;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaPermission {
    /// The person said yes once and for all. The app must not ask again.
    Always,
    /// The person said no once and for all. The core answers without bothering the app.
    Never,
    /// Every session asks the person. Also what a value from a newer version reads as.
    #[default]
    #[serde(other)]
    Ask,
}

/// What one device may ask of this one.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaPolicy {
    /// Showing this device's screen.
    #[serde(default)]
    pub screen: MediaPermission,
    /// Showing this device's camera.
    #[serde(default)]
    pub camera: MediaPermission,
    /// Letting the other device control this device's screen.
    #[serde(default)]
    pub control: MediaPermission,
}

impl MediaPolicy {
    pub fn for_kind(&self, kind: MediaKind) -> MediaPermission {
        match kind {
            MediaKind::Screen => self.screen,
            MediaKind::Camera => self.camera,
            // Not something that can be shared, so nothing is permitted.
            MediaKind::Other => MediaPermission::Never,
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
struct Policies {
    #[serde(default)]
    default: MediaPolicy,
    #[serde(default)]
    devices: HashMap<String, MediaPolicy>,
}

const POLICY_FILE: &str = "media.cbor";

/// The policies of this device. Lives in the engine.
pub struct MediaService {
    store: Store,
    policies: RwLock<Policies>,
}

impl MediaService {
    pub fn new(store: Store) -> MediaService {
        // A file that cannot be read is no reason to refuse to start. Everything asks again, which is the safe default.
        let policies = store.read_cbor::<Policies>(POLICY_FILE).ok().flatten().unwrap_or_default();
        MediaService { store, policies: RwLock::new(policies) }
    }

    pub fn policy(&self, id: &DeviceId) -> MediaPolicy {
        let policies = self.policies.read().unwrap();
        policies.devices.get(&id.to_string()).copied().unwrap_or(policies.default)
    }

    pub fn has_own_policy(&self, id: &DeviceId) -> bool {
        self.policies.read().unwrap().devices.contains_key(&id.to_string())
    }

    pub fn default_policy(&self) -> MediaPolicy {
        self.policies.read().unwrap().default
    }

    pub fn set_policy(&self, id: &DeviceId, policy: MediaPolicy) -> Result<()> {
        self.change(|policies| {
            policies.devices.insert(id.to_string(), policy);
        })
    }

    /// Back to the default for this device.
    pub fn clear_policy(&self, id: &DeviceId) -> Result<()> {
        self.change(|policies| {
            policies.devices.remove(&id.to_string());
        })
    }

    pub fn set_default_policy(&self, policy: MediaPolicy) -> Result<()> {
        self.change(|policies| policies.default = policy)
    }

    fn change(&self, edit: impl FnOnce(&mut Policies)) -> Result<()> {
        let mut policies = self.policies.write().unwrap();
        edit(&mut policies);
        self.store.write_cbor(POLICY_FILE, &*policies)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn everything_asks_until_a_person_decides() {
        let dir = tempfile::tempdir().unwrap();
        let service = MediaService::new(Store::new(dir.path()).unwrap());
        let id = DeviceId::from_bytes([3; 16]);
        assert_eq!(service.policy(&id), MediaPolicy::default());
        assert!(!service.has_own_policy(&id));
        assert_eq!(service.policy(&id).for_kind(MediaKind::Screen), MediaPermission::Ask);
        assert_eq!(service.policy(&id).for_kind(MediaKind::Other), MediaPermission::Never);
    }

    #[test]
    fn choices_survive_a_restart_and_clear() {
        let dir = tempfile::tempdir().unwrap();
        let id = DeviceId::from_bytes([4; 16]);
        let other = DeviceId::from_bytes([5; 16]);
        {
            let service = MediaService::new(Store::new(dir.path()).unwrap());
            service.set_policy(&id, MediaPolicy { screen: MediaPermission::Always, control: MediaPermission::Never, ..Default::default() }).unwrap();
            service.set_default_policy(MediaPolicy { camera: MediaPermission::Never, ..Default::default() }).unwrap();
        }
        let service = MediaService::new(Store::new(dir.path()).unwrap());
        assert_eq!(service.policy(&id).screen, MediaPermission::Always);
        assert_eq!(service.policy(&id).control, MediaPermission::Never);
        // The device has its own choices, so the default does not reach it.
        assert_eq!(service.policy(&id).camera, MediaPermission::Ask);
        assert_eq!(service.policy(&other).camera, MediaPermission::Never);
        service.clear_policy(&id).unwrap();
        assert!(!service.has_own_policy(&id));
        assert_eq!(service.policy(&id).camera, MediaPermission::Never);
    }

    #[test]
    fn a_file_that_is_not_ours_means_everything_asks() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path()).unwrap();
        store.write(POLICY_FILE, b"\xff\xff not cbor").unwrap();
        let service = MediaService::new(store);
        assert_eq!(service.default_policy(), MediaPolicy::default());
    }
}
