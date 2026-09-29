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

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Settings {
    #[serde(default)]
    pub devices: HashMap<String, DeviceSettings>,
}

impl Settings {
    pub fn for_device(&self, id: &DeviceId) -> DeviceSettings {
        self.devices.get(&id.to_string()).cloned().unwrap_or_default()
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
    fn settings_default_to_on() {
        let settings = Settings::default();
        let id = DeviceId::from_bytes([1; 16]);
        assert!(settings.for_device(&id).clipboard);
    }
}
