//! Small persistent state: the circle, remembered addresses, settings, and the
//! identity key. Files are written atomically so a crash never leaves half a file.

use std::collections::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::Result;
use crate::ids::DeviceId;

#[derive(Clone, Debug)]
pub struct Store {
    dir: PathBuf,
}

impl Store {
    pub fn new(dir: impl Into<PathBuf>) -> Result<Self> {
        let dir = dir.into();
        std::fs::create_dir_all(&dir)?;
        Ok(Store { dir })
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn read(&self, name: &str) -> Result<Option<Vec<u8>>> {
        match std::fs::read(self.dir.join(name)) {
            Ok(bytes) => Ok(Some(bytes)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e.into()),
        }
    }

    pub fn write(&self, name: &str, bytes: &[u8]) -> Result<()> {
        let target = self.dir.join(name);
        let temp = self.dir.join(format!(".{name}.tmp"));
        {
            let mut file = std::fs::File::create(&temp)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                file.set_permissions(std::fs::Permissions::from_mode(0o600))?;
            }
            file.write_all(bytes)?;
            file.sync_all()?;
        }
        std::fs::rename(&temp, &target)?;
        Ok(())
    }

    pub fn read_cbor<T: for<'de> Deserialize<'de>>(&self, name: &str) -> Result<Option<T>> {
        match self.read(name)? {
            Some(bytes) => Ok(Some(crate::proto::decode(&bytes)?)),
            None => Ok(None),
        }
    }

    pub fn write_cbor<T: Serialize>(&self, name: &str, value: &T) -> Result<()> {
        self.write(name, &crate::proto::encode(value)?)
    }
}

/// Where the identity key lives. Desktop keeps it in a private file; Android and
/// macOS hand it to their keystore through this trait.
pub trait SecretStore: Send + Sync {
    fn load(&self) -> Result<Option<Vec<u8>>>;
    fn save(&self, secret: &[u8]) -> Result<()>;
}

pub struct FileSecretStore {
    store: Store,
}

impl FileSecretStore {
    pub fn new(store: Store) -> Self {
        FileSecretStore { store }
    }
}

impl SecretStore for FileSecretStore {
    fn load(&self) -> Result<Option<Vec<u8>>> {
        self.store.read("identity.pkcs8")
    }

    fn save(&self, secret: &[u8]) -> Result<()> {
        self.store.write("identity.pkcs8", secret)
    }
}

/// Choices a person makes per device.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceSettings {
    #[serde(default = "yes")]
    pub clipboard: bool,
    #[serde(default = "yes")]
    pub auto_accept: bool,
    #[serde(default = "yes")]
    pub notifications: bool,
}

fn yes() -> bool {
    true
}

impl Default for DeviceSettings {
    fn default() -> Self {
        DeviceSettings { clipboard: true, auto_accept: true, notifications: true }
    }
}

impl DeviceSettings {
    /// What a guest gets: nothing of theirs comes into the clipboard, no notifications, and files are asked about first.
    pub fn guest() -> DeviceSettings {
        DeviceSettings { clipboard: false, auto_accept: false, notifications: false }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Settings {
    #[serde(default)]
    pub devices: HashMap<String, DeviceSettings>,
    /// A device that has no settings of its own yet starts as a guest (see [`DeviceSettings::guest`]) instead of as one of your own.
    /// Turning this on first writes down what each device that is there already has, so none of them changes.
    #[serde(default)]
    pub guests_by_default: bool,
}

impl Settings {
    pub fn for_device(&self, id: &DeviceId) -> DeviceSettings {
        match self.devices.get(&id.to_string()) {
            Some(own) => own.clone(),
            None if self.guests_by_default => DeviceSettings::guest(),
            None => DeviceSettings::default(),
        }
    }

    /// Switches the guest start on or off. `known` are the devices of the circle now: they keep what they have.
    pub fn set_guests_by_default(&mut self, on: bool, known: &[DeviceId]) {
        // What each one has now is what it keeps, whichever way this goes.
        for id in known {
            let own = self.for_device(id);
            self.devices.entry(id.to_string()).or_insert(own);
        }
        self.guests_by_default = on;
    }

    pub fn set_device(&mut self, id: &DeviceId, settings: DeviceSettings) {
        self.devices.insert(id.to_string(), settings);
    }
}

pub fn load_or_create_identity(secrets: &dyn SecretStore) -> Result<crate::identity::Identity> {
    if let Some(bytes) = secrets.load()? {
        return crate::identity::Identity::from_pkcs8(&bytes);
    }
    let identity = crate::identity::Identity::generate()?;
    secrets.save(identity.to_pkcs8())?;
    Ok(identity)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_and_reads_back() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path()).unwrap();
        assert!(store.read("missing").unwrap().is_none());
        store.write("a", b"hello").unwrap();
        assert_eq!(store.read("a").unwrap().unwrap(), b"hello");
        store.write("a", b"again").unwrap();
        assert_eq!(store.read("a").unwrap().unwrap(), b"again");
    }

    #[test]
    fn identity_is_created_once_and_kept() {
        let dir = tempfile::tempdir().unwrap();
        let secrets = FileSecretStore::new(Store::new(dir.path()).unwrap());
        let first = load_or_create_identity(&secrets).unwrap();
        let second = load_or_create_identity(&secrets).unwrap();
        assert_eq!(first.id(), second.id());
    }

    #[test]
    fn a_guest_start_changes_only_devices_that_come_later() {
        let mut settings = Settings::default();
        let mine = DeviceId::from_bytes([1; 16]);
        let later = DeviceId::from_bytes([2; 16]);
        settings.set_guests_by_default(true, &[mine]);
        // The device that was there keeps everything on; the one that comes after starts as a guest.
        assert_eq!(settings.for_device(&mine), DeviceSettings::default());
        assert_eq!(settings.for_device(&later), DeviceSettings::guest());
        assert!(!settings.for_device(&later).clipboard);
        // Turning it off again leaves a device that got its settings as they are, and the next one back to everything on.
        settings.set_guests_by_default(false, &[mine, later]);
        assert_eq!(settings.for_device(&later), DeviceSettings::guest());
        assert_eq!(settings.for_device(&DeviceId::from_bytes([3; 16])), DeviceSettings::default());
    }

    #[test]
    fn settings_default_to_on() {
        let settings = Settings::default();
        let id = DeviceId::from_bytes([1; 16]);
        assert!(settings.for_device(&id).clipboard);
    }
}
